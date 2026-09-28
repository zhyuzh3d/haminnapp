package life.airen.haminn.notification

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import life.airen.haminn.HaminnApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class OnlineNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as HaminnApplication
        if (!app.notifications.scheduler.notificationsAvailable()) return@withContext Result.success()
        var retryableFailure = false
        app.notifications.repository.allSyncs().forEach { sync ->
            val instance = app.registry.getInstance(sync.instanceId)
            if (instance?.notificationEnabled != true || instance.liveUrl == null || originOf(instance.liveUrl) != sync.endpointOrigin) return@forEach
            runCatching { fetch(app, instance.appId, sync) }.onFailure { retryableFailure = true }
        }
        app.notifications.repository.pruneDelivered(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        if (retryableFailure) Result.retry() else Result.success()
    }

    private fun fetch(app: HaminnApplication, instanceId: String, sync: NotificationSync) {
        val endpoint = sync.endpoint.toHttpUrl()
        val addresses = InetAddress.getAllByName(endpoint.host).toList()
        if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }) return
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
            .dns(Dns { host -> if (host.equals(endpoint.host, true)) addresses else throw java.net.UnknownHostException(host) }).build()
        val requestUrl = endpoint.newBuilder().apply { sync.cursor?.let { setQueryParameter("cursor", it) } }.build()
        val request = Request.Builder().url(requestUrl).get().header("Accept", "application/json").apply {
            notifyToken(sync.endpoint)?.let { header("Cookie", "notify-token=$it") }
            sync.etag?.let { header("If-None-Match", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) { app.notifications.repository.updateSync(instanceId, sync.cursor, sync.etag); return }
            if (response.code == 204) { app.notifications.repository.updateSync(instanceId, sync.cursor, response.header("ETag") ?: sync.etag); return }
            if (!response.isSuccessful) error("notification endpoint HTTP ${response.code}")
            if (response.body.contentLength() > MAX_RESPONSE_BYTES) error("notification response too large")
            val bytes = readAtMost(response.body.byteStream(), MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) error("notification response too large")
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            rejectUnknown(json, setOf("notifications", "cancel", "cursor"), "notification response")
            val instance = app.registry.getInstance(instanceId) ?: return
            val items = json.optJSONArray("notifications")
            if (items != null && items.length() > MAX_ITEMS) error("too many notifications")
            for (index in 0 until (items?.length() ?: 0)) {
                val spec = NotificationSpec.fromJson(items!!.getJSONObject(index))
                if (app.notifications.repository.markDelivered(instanceId, spec.id)) app.notifications.dispatcher.post(instance, spec)
            }
            val cancel = json.optJSONArray("cancel")
            if (cancel != null && cancel.length() > MAX_ITEMS) error("too many cancellations")
            for (index in 0 until (cancel?.length() ?: 0)) {
                val id = cancel!!.getString(index)
                app.notifications.dispatcher.cancel(instanceId, id)
                app.notifications.repository.cancel(instanceId, id)
            }
            val cursor = json.optString("cursor").takeIf { json.has("cursor") && it.length <= 512 }
            app.notifications.repository.updateSync(instanceId, cursor ?: sync.cursor, response.header("ETag"))
        }
    }

    private fun notifyToken(endpoint: String): String? = CookieManager.getInstance().getCookie(endpoint)?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.substringBefore('=') == "notify-token" }?.substringAfter('=', "")

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        val port = if (uri.port != -1 && !((uri.scheme == "https" && uri.port == 443) || (uri.scheme == "http" && uri.port == 80))) ":${uri.port}" else ""
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray = input.use {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < limit) {
            val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ITEMS = 50
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("haminn-online-notifications-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OnlineNotificationWorker>().build())
        }
    }
}
