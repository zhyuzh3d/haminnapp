package life.airen.haminn.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.deploy.DevWorkspaceManager
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.install.IconProcessor
import life.airen.haminn.install.PackageManifest
import life.airen.haminn.install.PackageManifestReader
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.LaunchChannel
import life.airen.haminn.registry.AppRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One explicit, short-lived device-to-device happ package sharing session. */
class HappShareManager(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val devWorkspaces: DevWorkspaceManager,
    private val scope: CoroutineScope,
) {
    data class Asset(val file: File, val mime: String)
    data class InboundPackage(val shareId: String, val file: File, val name: String, val sha256: String)

    private data class Outbound(
        val id: String,
        val appId: String,
        val name: String,
        val fileName: String,
        val archive: File,
        val icon: File,
        val qr: File?,
        val password: String,
        val address: String?,
        val sha256: String,
        val bytes: Long,
        val expiresAt: Long,
        val metadata: JSONObject,
        val directory: File,
        val server: ShareServer?,
    )

    private data class Inbound(
        val id: String,
        val baseUrl: String,
        val password: String,
        val name: String,
        val sha256: String,
        val bytes: Long,
        val expiresAt: Long,
        val directory: File,
        val icon: File?,
        val metadata: JSONObject,
        val createdAt: Long,
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val images = HostImageStore(context)
    private val inbound = ConcurrentHashMap<String, Inbound>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    @Volatile private var outbound: Outbound? = null
    private var expiryJob: Job? = null

    fun start(appId: String, allowNetwork: Boolean): JSONObject = synchronized(lock) {
        // Keep an existing foreground service alive while replacing a network share. The
        // service is updated with the new session id below, which avoids a stop/start race.
        stopLocked(stopForegroundService = false)
        cleanupInbound()
        val app = registry.getInstance(appId)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val release = app.activeReleaseId?.let(registry::getRelease)
            ?: throw HaminnException(ErrorCodes.CONFLICT, "此 happ 没有可分享的本地安装包")
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "shared/happ-share/$id").apply { mkdirs() }
        try {
            val staging = File(directory, "package.zip")
            val snapshotKind: String
            val snapshotRevision: Long?
            if (app.launchChannel == LaunchChannel.DEV) {
                val workspace = registry.getDevWorkspace(appId)
                    ?: throw HaminnException(ErrorCodes.DEV_MODE_REQUIRED, "开发工作副本不存在")
                val artifact = devWorkspaces.buildShare(appId, workspace.revision)
                artifact.file.copyTo(staging, overwrite = true)
                snapshotKind = "development"
                snapshotRevision = artifact.revision
            } else {
                zipDirectory(installer.releaseWebRoot(release), staging)
                snapshotKind = "release"
                snapshotRevision = null
            }
            if (staging.length() !in 1..MAX_ZIP_BYTES) {
                throw HaminnException(ErrorCodes.QUOTA, "分享 ZIP 为空或超过 64 MiB")
            }
            val sha256 = hash(staging)
            val manifest = readManifest(staging, directory)
            val signed = containsEntry(staging, "haminn.sig")
            val versionLabel = manifest?.versionName ?: release.versionName
            // The file keeps the name the receiver will see, so the save dialog and the
            // system share sheet both offer "<happ name>-<version>.zip" instead of a
            // generic package.zip that says nothing about what is inside.
            val fileName = packageFileName(app.name, versionLabel)
            val archive = File(directory, fileName)
            if (archive.path != staging.path) {
                if (!staging.renameTo(archive)) {
                    staging.copyTo(archive, overwrite = true)
                    staging.delete()
                }
            }
            val icon = File(directory, "icon.png")
            renderIcon(app.effectiveIconUrl, app.name, icon)
            val expiresAt = System.currentTimeMillis() + SESSION_TTL_MS
            val password = random.nextInt(1_000_000).toString().padStart(6, '0')
            val metadata = JSONObject()
                .put("schema", 1)
                .put("name", app.name)
                .put("happId", manifest?.happId ?: app.happId ?: JSONObject.NULL)
                .put("author", manifest?.author ?: JSONObject.NULL)
                .put("versionCode", manifest?.versionCode ?: release.versionCode ?: JSONObject.NULL)
                .put("versionName", manifest?.versionName ?: release.versionName ?: JSONObject.NULL)
                .put("snapshot", snapshotKind)
                .put("devRevision", snapshotRevision ?: JSONObject.NULL)
                .put("signed", signed)
                .put("bytes", archive.length())
                .put("sha256", sha256)
                .put("expiresAt", expiresAt)
                .put("device", Build.MODEL.take(80))
            val host = if (allowNetwork) lanAddresses().firstOrNull() else null
            var server: ShareServer? = null
            var address: String? = null
            var qr: File? = null
            if (host != null) {
                server = ShareServer(host, password, metadata, archive, icon, fileName)
                try {
                    server.start(SOCKET_TIMEOUT_MS, false)
                    address = "http://$host:${server.listeningPort}"
                    val payload = Uri.Builder().scheme("haminn").authority("share")
                        .appendQueryParameter("v", "1")
                        .appendQueryParameter("u", address)
                        .appendQueryParameter("p", password)
                        .build().toString()
                    qr = File(directory, "qr.png")
                    renderQr(payload, icon, qr)
                } catch (error: Throwable) {
                    server.stop()
                    throw HaminnException(ErrorCodes.NETWORK, error.message ?: "无法启动临时分享服务", true)
                }
            }
            val value = Outbound(id, appId, app.name, fileName, archive, icon, qr, password, address,
                sha256, archive.length(), expiresAt, metadata, directory, server)
            outbound = value
            expiryJob = scope.launch {
                delay(SESSION_TTL_MS)
                stop(id)
            }
            if (server != null) {
                startForegroundService(id)
            } else {
                context.stopService(Intent(context, HappShareService::class.java))
            }
            outboundJson(value)
        } catch (error: Throwable) {
            if (outbound?.id == id) {
                stopLocked(stopForegroundService = true)
            } else {
                directory.deleteRecursively()
            }
            throw error
        }
    }

    fun inspect(rawPayload: String): JSONObject {
        cleanupInbound()
        val payload = Uri.parse(rawPayload)
        if (!payload.scheme.equals("haminn", true) || !payload.host.equals("share", true) ||
            payload.getQueryParameter("v") != "1" || payload.queryParameterNames.any { it !in setOf("v", "u", "p") }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不是有效的 Haminn happ 分享二维码")
        }
        val password = payload.getQueryParameter("p")?.takeIf { it.matches(Regex("[0-9]{6}")) }
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享二维码密码无效")
        val baseUrl = validateShareBase(payload.getQueryParameter("u") ?: "")
        val metadata = requestJson(baseUrl, "/manifest", password)
        if (metadata.optInt("schema", -1) != 1) throw HaminnException(ErrorCodes.UNSUPPORTED, "不支持的分享协议版本")
        val name = metadata.optString("name").trim().takeIf { it.isNotBlank() && it.length <= 80 }
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享信息缺少 happ 名称")
        val sha256 = metadata.optString("sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享包摘要无效")
        val bytes = metadata.optLong("bytes", -1L).takeIf { it in 1..MAX_ZIP_BYTES }
            ?: throw HaminnException(ErrorCodes.QUOTA, "分享包大小无效")
        val expiresAt = metadata.optLong("expiresAt", 0L)
        if (expiresAt <= System.currentTimeMillis()) throw HaminnException(ErrorCodes.SESSION_EXPIRED, "分享已过期，请重新扫码")
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "shared/happ-share-inbound/$id").apply { mkdirs() }
        val icon = runCatching {
            val target = File(directory, "icon.png")
            requestFile(baseUrl, "/icon", password, target, MAX_ICON_BYTES, null, null)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) throw IllegalArgumentException("Invalid icon")
            target
        }.getOrNull()
        val incoming = Inbound(id, baseUrl, password, name, sha256, bytes, expiresAt, directory, icon, metadata, System.currentTimeMillis())
        inbound[id] = incoming
        return JSONObject(metadata.toString())
            .put("kind", "happ-share")
            .put("shareId", id)
            .put("iconUrl", icon?.let { "/__haminn/share/in/$id/icon.png" } ?: JSONObject.NULL)
    }

    fun download(shareId: String): InboundPackage {
        cleanupInbound()
        val value = inbound[shareId]
            ?: throw HaminnException(ErrorCodes.SESSION_EXPIRED, "分享信息已失效，请重新扫码")
        if (value.expiresAt <= System.currentTimeMillis()) {
            finishInbound(shareId)
            throw HaminnException(ErrorCodes.SESSION_EXPIRED, "分享已过期，请重新扫码")
        }
        val target = File(value.directory, "package.zip")
        requestFile(value.baseUrl, "/package", value.password, target, MAX_ZIP_BYTES, value.bytes, value.sha256)
        return InboundPackage(value.id, target, value.name, value.sha256)
    }

    fun finishInbound(shareId: String) {
        inbound.remove(shareId)?.directory?.deleteRecursively()
    }

    fun packageFile(sessionId: String): Pair<File, String> = synchronized(lock) {
        val value = outbound?.takeIf { it.id == sessionId && it.expiresAt > System.currentTimeMillis() }
            ?: throw HaminnException(ErrorCodes.SESSION_EXPIRED, "分享会话已结束")
        value.archive to value.fileName
    }

    fun hasLanAddress(): Boolean = lanAddresses().isNotEmpty()

    fun openAsset(path: String): Asset? {
        OUT_QR.matchEntire(path)?.groupValues?.get(1)?.let { id ->
            val value = outbound?.takeIf { it.id == id } ?: return null
            return value.qr?.takeIf(File::isFile)?.let { Asset(it, "image/png") }
        }
        OUT_ICON.matchEntire(path)?.groupValues?.get(1)?.let { id ->
            val value = outbound?.takeIf { it.id == id } ?: return null
            return value.icon.takeIf(File::isFile)?.let { Asset(it, "image/png") }
        }
        IN_ICON.matchEntire(path)?.groupValues?.get(1)?.let { id ->
            val value = inbound[id] ?: return null
            return value.icon?.takeIf(File::isFile)?.let { Asset(it, "image/png") }
        }
        return null
    }

    fun foregroundInfo(sessionId: String?): Pair<String, Long>? = synchronized(lock) {
        outbound?.takeIf { sessionId == null || it.id == sessionId }?.let { it.name to it.expiresAt }
    }

    fun stop(sessionId: String? = null, stopForegroundService: Boolean = true): JSONObject = synchronized(lock) {
        if (sessionId != null && outbound?.id != sessionId) return@synchronized JSONObject().put("stopped", false)
        val stopped = outbound != null
        stopLocked(stopForegroundService)
        JSONObject().put("stopped", stopped)
    }

    fun close() {
        synchronized(lock) { stopLocked(stopForegroundService = false) }
        inbound.values.forEach { it.directory.deleteRecursively() }
        inbound.clear()
    }

    private fun outboundJson(value: Outbound): JSONObject = JSONObject(value.metadata.toString())
        .put("sessionId", value.id)
        .put("fileName", value.fileName)
        .put("networkAvailable", value.address != null)
        .put("address", value.address ?: JSONObject.NULL)
        .put("qrUrl", value.qr?.let { "/__haminn/share/out/${value.id}/qr.png" } ?: JSONObject.NULL)
        .put("iconUrl", "/__haminn/share/out/${value.id}/icon.png")

    private fun stopLocked(stopForegroundService: Boolean) {
        expiryJob?.cancel(); expiryJob = null
        val value = outbound; outbound = null
        value?.server?.stop()
        value?.directory?.deleteRecursively()
        if (stopForegroundService) context.stopService(Intent(context, HappShareService::class.java))
    }

    private fun startForegroundService(sessionId: String) {
        val intent = Intent(context, HappShareService::class.java)
            .setAction(HappShareService.ACTION_START)
            .putExtra(HappShareService.EXTRA_SESSION_ID, sessionId)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun zipDirectory(root: File, output: File) {
        val canonicalRoot = root.canonicalFile
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            canonicalRoot.walkTopDown().filter(File::isFile)
                .map { it.relativeTo(canonicalRoot).invariantSeparatorsPath to it }
                .sortedBy { it.first }
                .forEach { (path, file) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun readManifest(archive: File, directory: File): PackageManifest? {
        val root = File(directory, "manifest-check").apply { mkdirs() }
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name == "haminn.json") {
                        val bytes = readBounded(zip, MAX_MANIFEST_BYTES)
                        File(root, "haminn.json").writeBytes(bytes)
                        break
                    }
                }
            }
            return PackageManifestReader.read(root)
        } finally { root.deleteRecursively() }
    }

    private fun containsEntry(archive: File, name: String): Boolean = ZipInputStream(FileInputStream(archive)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: return@use false
            if (!entry.isDirectory && entry.name == name) return@use true
        }
        false
    }

    private fun renderIcon(url: String?, name: String, output: File) {
        val source = images.open(url)?.file?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val radius = IconProcessor.cornerRadius(size)
        val clip = Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(clip)
        if (source != null) {
            val scale = maxOf(size.toFloat() / source.width, size.toFloat() / source.height)
            val width = (source.width * scale).toInt()
            val height = (source.height * scale).toInt()
            val left = (size - width) / 2
            val top = (size - height) / 2
            canvas.drawBitmap(source, null, Rect(left, top, left + width, top + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(Color.rgb(37, 99, 235))
            val letter = firstCharacter(name)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 92f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val y = size / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letter, size / 2f, y, paint)
        }
        canvas.restore()
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.fd.sync() }
        source?.recycle(); bitmap.recycle()
    }

    private fun renderQr(payload: String, iconFile: File, output: File) {
        val size = 512
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 1,
        ))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val canvas = Canvas(bitmap)
        val panel = RectF(195f, 195f, 317f, 317f)
        canvas.drawRoundRect(panel, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val icon = BitmapFactory.decodeFile(iconFile.absolutePath)
        if (icon != null) {
            val destination = Rect(207, 207, 305, 305)
            val path = Path().apply { addRoundRect(RectF(destination), 22f, 22f, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(path)
            canvas.drawBitmap(icon, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            canvas.restore(); icon.recycle()
        }
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.fd.sync() }
        bitmap.recycle()
    }

    private fun firstCharacter(value: String): String {
        val normalized = value.trim().ifBlank { "H" }
        val end = normalized.offsetByCodePoints(0, 1)
        return normalized.substring(0, end).uppercase()
    }

    private fun validateShareBase(raw: String): String {
        if (raw.length !in 10..160) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享服务地址无效")
        val uri = Uri.parse(raw)
        if (!uri.scheme.equals("http", true) || uri.host.isNullOrBlank() || uri.port !in 1..65535 ||
            uri.userInfo != null || uri.query != null || uri.fragment != null || uri.path !in setOf("", "/")) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享服务地址无效")
        }
        val address = runCatching { InetAddress.getByName(uri.host) }.getOrNull()
        if (address == null || !address.isSiteLocalAddress || address.isLoopbackAddress || address !is Inet4Address) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享服务必须位于当前局域网")
        }
        return "http://${uri.host}:${uri.port}"
    }

    private fun requestJson(baseUrl: String, path: String, password: String): JSONObject {
        val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $password").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw HaminnException(ErrorCodes.ORIGIN_DENIED, "分享密码无效，请重新扫码")
            if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "分享服务暂不可用（HTTP ${response.code}）", true)
            val body = response.body ?: throw HaminnException(ErrorCodes.NETWORK, "分享服务没有返回内容", true)
            val bytes = readBounded(body.byteStream(), MAX_MANIFEST_BYTES)
            return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享信息格式无效") }
        }
    }

    private fun requestFile(
        baseUrl: String,
        path: String,
        password: String,
        target: File,
        maxBytes: Long,
        expectedBytes: Long?,
        expectedSha256: String?,
    ) {
        val request = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $password").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw HaminnException(ErrorCodes.ORIGIN_DENIED, "分享密码无效，请重新扫码")
            if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "下载安装包失败（HTTP ${response.code}）", true)
            val declared = response.body?.contentLength() ?: -1L
            if (declared > maxBytes || expectedBytes != null && declared >= 0 && declared != expectedBytes) {
                throw HaminnException(ErrorCodes.QUOTA, "分享文件大小不符合声明")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            target.parentFile?.mkdirs()
            try {
                FileOutputStream(target).use { output ->
                    val input = response.body?.byteStream() ?: throw HaminnException(ErrorCodes.NETWORK, "分享服务没有返回文件", true)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw HaminnException(ErrorCodes.QUOTA, "分享文件超过大小限制")
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                target.delete(); throw error
            }
            if (expectedBytes != null && total != expectedBytes || expectedSha256 != null && hashBytes(digest) != expectedSha256) {
                target.delete()
                throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "分享文件完整性校验失败")
            }
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw HaminnException(ErrorCodes.QUOTA, "响应超过大小限制")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun lanAddresses(): List<String> = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val activeInterfaces = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = manager.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
            name to (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val lan = activeInterfaces.filter { it.second }.map { it.first }.toSet()
        val allowedPrefixes = listOf("wlan", "swlan", "ap", "eth", "en", "rndis")
        val blockedPrefixes = listOf("rmnet", "ccmni", "pdp", "wwan", "tun", "dummy")
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback && !network.isPointToPoint &&
                    blockedPrefixes.none(name::startsWith) && (network.name in lan || allowedPrefixes.any(name::startsWith))
            }
            .sortedBy { if (it.name in lan) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun cleanupInbound() {
        val cutoff = System.currentTimeMillis() - INBOUND_TTL_MS
        inbound.entries.removeIf { (_, value) ->
            val expired = value.expiresAt <= System.currentTimeMillis() || value.createdAt < cutoff
            if (expired) value.directory.deleteRecursively()
            expired
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        digest.digest().hex()
    }

    private fun hashBytes(digest: MessageDigest) = digest.digest().hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /**
     * "<happ name>-v<version>.zip", matching the released package names so a saved
     * share is not confused with a version downloaded from the happ's own site. The
     * name part gives way first when the pair runs long, because dropping the version
     * would make two shares indistinguishable.
     */
    private fun packageFileName(name: String, version: String?): String {
        val base = safeFileName(name, 32)
        val tag = version?.takeIf { it.isNotBlank() }?.let {
            val clean = safeFileName(it, 16)
            if (clean.startsWith("v", true)) clean else "v$clean"
        }
        return listOfNotNull(base, tag).joinToString("-") + ".zip"
    }

    private fun safeFileName(value: String, limit: Int): String =
        value.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]+"), "-").trim('-').take(limit).ifBlank { "happ" }

    private inner class ShareServer(
        host: String,
        private val password: String,
        private val metadata: JSONObject,
        private val archive: File,
        private val icon: File,
        private val downloadName: String,
    ) : NanoHTTPD(host, 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "Only GET is supported")
            if (!MessageDigest.isEqual("Bearer $password".toByteArray(), (session.headers["authorization"] ?: "").toByteArray())) {
                return text(Response.Status.UNAUTHORIZED, "Invalid share password")
            }
            return when (session.uri) {
                "/manifest" -> bytes(Response.Status.OK, "application/json; charset=utf-8", metadata.toString().toByteArray(Charsets.UTF_8))
                "/icon" -> file("image/png", icon, "icon.png")
                "/package" -> file("application/zip", archive, downloadName)
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }

        private fun file(mime: String, file: File, name: String): Response =
            newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length()).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                addHeader("Connection", "close")
            }

        private fun bytes(status: Response.Status, mime: String, value: ByteArray): Response =
            newFixedLengthResponse(status, mime, value.inputStream(), value.size.toLong()).apply {
                addHeader("Cache-Control", "no-store"); addHeader("Connection", "close")
            }

        private fun text(status: Response.Status, value: String) = bytes(status, "text/plain; charset=utf-8", value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private val OUT_QR = Regex("/__haminn/share/out/([0-9a-fA-F-]{36})/qr\\.png")
        private val OUT_ICON = Regex("/__haminn/share/out/([0-9a-fA-F-]{36})/icon\\.png")
        private val IN_ICON = Regex("/__haminn/share/in/([0-9a-fA-F-]{36})/icon\\.png")
        private const val SESSION_TTL_MS = 60L * 60 * 1000
        private const val INBOUND_TTL_MS = 15L * 60 * 1000
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
        private const val MAX_ICON_BYTES = 512L * 1024
        private const val MAX_MANIFEST_BYTES = 64L * 1024
    }
}
