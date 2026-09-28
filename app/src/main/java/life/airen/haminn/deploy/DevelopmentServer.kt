package life.airen.haminn.deploy

import android.content.Context
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.security.SecureRandom
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

class DevelopmentServer(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val scope: CoroutineScope,
) {
    @Volatile private var active: Active? = null
    @Volatile private var reloadHandler: ((String) -> Boolean)? = null
    @Volatile private var reloadHandlerOwner: Any? = null
    private var expiryJob: Job? = null

    private data class Active(
        val appId: String,
        val token: String,
        val server: DeployHttpServer,
        val startedAt: Long,
        val mode: String,
        val tlsAlias: String? = null,
        val pin: String? = null,
        val address: String,
    )

    @Synchronized
    fun start(appId: String, mode: String = "adb"): JSONObject {
        val app = registry.getInstance(appId)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        if (app.activeReleaseId == null) throw HaminnException(ErrorCodes.CONFLICT, "只有已安装本地代码的 happ 可以使用开发部署")
        if (mode !in setOf("adb", "lan")) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "开发连接模式无效")
        stop("Replaced")
        val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.NO_WRAP or Base64.URL_SAFE)
        val lanHost = if (mode == "lan") lanAddress() else null
        val identity = if (mode == "lan") TlsIdentity.create() else null
        val allowedHosts = if (lanHost == null) setOf("127.0.0.1:8765", "localhost:8765") else setOf("$lanHost:8765")
        val server = DeployHttpServer(
            if (mode == "lan") "0.0.0.0" else "127.0.0.1",
            allowedHosts,
            appId,
            token,
            registry,
            installer,
        ) { target -> reloadHandler?.invoke(target) == true }
        try {
            identity?.let { server.makeSecure(it.socketFactory, null) }
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            registry.setDeveloperEnabled(appId, true)
        } catch (error: Throwable) {
            server.stop()
            identity?.alias?.let(TlsIdentity::delete)
            registry.setDeveloperEnabled(appId, false)
            throw HaminnException(ErrorCodes.NETWORK, error.message ?: "无法启动开发连接", true)
        }
        val address = if (mode == "lan") "https://$lanHost:${server.listeningPort}" else "http://127.0.0.1:${server.listeningPort}"
        active = Active(appId, token, server, System.currentTimeMillis(), mode, identity?.alias, identity?.pin, address)
        expiryJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val current = active ?: break
                if (System.currentTimeMillis() - current.server.lastAccessAt > IDLE_TIMEOUT_MS) {
                    stop("Developer session expired")
                    break
                }
            }
        }
        return JSONObject()
            .put("mode", mode)
            .put("address", address)
            .put("token", token)
            .put("spkiPin", identity?.pin ?: JSONObject.NULL)
            .put("expiresAfterSeconds", IDLE_TIMEOUT_MS / 1000)
            .put("message", "已开启${if (mode == "lan") "局域网 TLS" else " ADB"}开发连接；Haminn 进入后台后自动关闭")
    }

    @Synchronized
    fun setReloadHandler(owner: Any, handler: (String) -> Boolean) {
        reloadHandlerOwner = owner
        reloadHandler = handler
    }

    @Synchronized
    fun clearReloadHandler(owner: Any) {
        if (reloadHandlerOwner !== owner) return
        reloadHandlerOwner = null
        reloadHandler = null
    }

    @Synchronized
    fun stop(reason: String): JSONObject {
        val previous = active
        active = null
        expiryJob?.cancel()
        expiryJob = null
        previous?.server?.stop()
        previous?.tlsAlias?.let(TlsIdentity::delete)
        previous?.let { registry.setDeveloperEnabled(it.appId, false) }
        return JSONObject().put("stopped", previous != null).put("reason", reason)
    }

    fun status(): JSONObject {
        val value = active ?: return JSONObject().put("active", false)
        return JSONObject().put("active", value.server.wasStarted()).put("appId", value.appId).put("mode", value.mode)
            .put("address", value.address).put("spkiPin", value.pin ?: JSONObject.NULL).put("startedAt", value.startedAt)
            .put("lastAccessAt", value.server.lastAccessAt).put("expiresAt", value.server.lastAccessAt + IDLE_TIMEOUT_MS)
    }

    private fun lanAddress(): String = NetworkInterface.getNetworkInterfaces().toList().asSequence()
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress
        ?: throw IllegalStateException("没有可用的局域网 IPv4 地址")

    companion object { private const val IDLE_TIMEOUT_MS = 15L * 60 * 1000 }
}

private class DeployHttpServer(
    bindHost: String,
    private val allowedHosts: Set<String>,
    private val targetAppId: String,
    private val bearerToken: String,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val reload: (String) -> Boolean,
) : NanoHTTPD(bindHost, 8765) {
    @Volatile var lastAccessAt: Long = System.currentTimeMillis()
        private set
    private val uploadPermit = Semaphore(1)

    init {
        setAsyncRunner(BoundedAsyncRunner())
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["host"] !in allowedHosts) return json(400, "Invalid Host")
        if (session.headers["origin"] != null) return json(403, "Browser-origin requests are not accepted")
        if (session.headers.entries.sumOf { it.key.length + it.value.length } > MAX_HEADERS) return json(431, "Headers too large")
        if (session.headers["authorization"] != "Bearer $bearerToken") return json(401, "Unauthorized")
        lastAccessAt = System.currentTimeMillis()
        return try {
            when {
                session.method == Method.GET && session.uri == "/v1/status" ->
                    jsonObject(200, JSONObject().put("appId", targetAppId)
                        .put("activeReleaseId", registry.getInstance(targetAppId)?.activeReleaseId))
                session.method == Method.GET && session.uri.startsWith("/v1/operations/") -> {
                    val id = session.uri.substringAfterLast('/')
                    registry.operationJson(id)?.takeIf { it.optString("appId") == targetAppId }
                        ?.let { jsonObject(200, it) } ?: json(404, "Operation not found")
                }
                session.method == Method.PUT && session.uri == "/v1/apps/$targetAppId/release" -> deploy(session)
                session.method == Method.POST && session.uri == "/v1/apps/$targetAppId/reload" -> {
                    val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
                    if (contentLength != 0L || session.headers["transfer-encoding"] != null) {
                        json(400, "Reload request must have an empty body")
                    } else {
                        val accepted = reload(targetAppId)
                        jsonObject(if (accepted) 202 else 409, JSONObject()
                            .put("appId", targetAppId).put("reload", if (accepted) "requested" else "not-visible"))
                    }
                }
                else -> json(404, "Not Found")
            }
        } catch (error: Throwable) {
            json(400, error.message ?: "Deploy failed")
        }
    }

    private fun deploy(session: IHTTPSession): Response {
        if (!uploadPermit.tryAcquire()) return json(409, "Another upload is active")
        try {
        if (!session.headers["content-type"].orEmpty().substringBefore(';').equals("application/zip", true)) {
            return json(415, "Content-Type must be application/zip")
        }
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: return json(411, "Content-Length required")
        if (contentLength !in 1..MAX_UPLOAD) return json(413, "Upload too large")
        if (session.headers["transfer-encoding"] != null) return json(400, "Transfer-Encoding is not accepted")
        val idempotency = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 }
            ?: return json(400, "Idempotency-Key required")
        val expected = session.headers["x-haminn-expected-release"]?.takeIf { it.isNotBlank() }
        val sha = session.headers["x-haminn-content-sha256"]?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: return json(400, "X-Haminn-Content-SHA256 required")
        val result = runBlocking(Dispatchers.IO) {
            installer.installZip(FixedLengthInputStream(session.inputStream, contentLength), null, targetAppId, "deploy", idempotency, expected, sha)
        }
        return jsonObject(200, JSONObject().put("operationId", result.operationId)
            .put("releaseId", result.releaseId).put("treeHash", result.treeHash).put("reload", "not-requested"))
        } finally { uploadPermit.release() }
    }

    private fun json(status: Int, message: String) = jsonObject(status, JSONObject().put("error", message))
    private fun jsonObject(status: Int, body: JSONObject): Response =
        newFixedLengthResponse(Response.Status.lookup(status), "application/json", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    companion object {
        private const val MAX_UPLOAD = 64L * 1024 * 1024
        private const val MAX_HEADERS = 32 * 1024
    }
}

internal class BoundedAsyncRunner(coreThreads: Int = 1) : NanoHTTPD.AsyncRunner {
    private val sequence = AtomicInteger()
    private val running = ConcurrentHashMap.newKeySet<NanoHTTPD.ClientHandler>()
    private val executor = ThreadPoolExecutor(
        coreThreads,
        4,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "haminn-deploy-${sequence.incrementAndGet()}").apply { isDaemon = true } },
    )

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running += clientHandler
        try {
            executor.execute(clientHandler)
        } catch (_: RejectedExecutionException) {
            running -= clientHandler
            clientHandler.close()
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running -= clientHandler
    }

    override fun closeAll() {
        running.toList().forEach(NanoHTTPD.ClientHandler::close)
        running.clear()
        executor.shutdownNow()
    }
}

private data class TlsIdentity(val alias: String, val socketFactory: SSLServerSocketFactory, val pin: String) {
    companion object {
        private const val PREFIX = "haminn-deploy-"

        fun create(): TlsIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.aliases().toList().filter { it.startsWith(PREFIX) }.forEach { store.deleteEntry(it) }
            val alias = PREFIX + java.util.UUID.randomUUID()
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.3/Conscrypt may negotiate SHA-384 or SHA-512 for the
                // CertificateVerify operation even though the SPKI pin itself
                // is SHA-256. Conscrypt signs a pre-hashed transcript with
                // NONEwithECDSA, so NONE is required as well.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .setCertificateSubject(X500Principal("CN=Haminn Developer Deploy"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 60_000))
                .setCertificateNotAfter(Date(now + 24L * 60 * 60 * 1000))
                .build())
            generator.generateKeyPair()
            val certificate = store.getCertificate(alias)
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, null) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
            val pin = "sha256//" + Base64.encodeToString(digest, Base64.NO_WRAP)
            return TlsIdentity(alias, ssl.serverSocketFactory, pin)
        }

        fun delete(alias: String) {
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
        }
    }
}

internal class FixedLengthInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = source.read()
        if (value >= 0) remaining--
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val read = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }
}
