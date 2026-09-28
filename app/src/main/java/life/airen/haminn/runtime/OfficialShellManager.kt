package life.airen.haminn.runtime

import android.content.Context
import life.airen.haminn.BuildConfig
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** The small, deliberately separate update mechanism for Haminn's own HTML shell. */
class OfficialShellManager(private val context: Context) {
    enum class Mode(val value: String) { ONLINE("online"), LOCAL("local") }

    private val preferences = context.getSharedPreferences("official-shell", Context.MODE_PRIVATE)
    private val updateLock = Mutex()
    private val shellRoot = File(context.filesDir, "official-shell")
    val downloadedRoot: File get() = File(shellRoot, "current")

    fun mode(): Mode = if (preferences.getString(KEY_MODE, Mode.LOCAL.value) == Mode.ONLINE.value) Mode.ONLINE else Mode.LOCAL

    fun setMode(value: String): Mode {
        val selected = Mode.entries.firstOrNull { it.value == value }
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "未知界面模式")
        preferences.edit().putString(KEY_MODE, selected.value).apply()
        return selected
    }

    /** APK replacement restores the embedded control plane without touching ordinary happs or their data. */
    fun resetToEmbedded() {
        downloadedRoot.deleteRecursively()
        setMode(Mode.LOCAL.value)
    }

    suspend fun activateOnline(): Mode = withContext(Dispatchers.IO) {
        val remoteVersion = fetchManifest().optLong("version", -1)
        val embeddedVersion = readEmbeddedMetadata()?.optLong("version", -1) ?: -1
        if (remoteVersion < embeddedVersion) {
            throw HaminnException(ErrorCodes.NETWORK, "官网界面版本尚未同步，请稍后重试", true)
        }
        setMode(Mode.ONLINE.value)
    }

    fun hasDownloadedShell(): Boolean {
        if (!File(downloadedRoot, "index.html").isFile) return false
        val downloadedMetadata = readMetadata() ?: return false
        val embeddedMetadata = readEmbeddedMetadata() ?: return false
        val downloadedVersion = downloadedMetadata.optLong("version", -1)
        val embeddedVersion = embeddedMetadata.optLong("version", -1)
        if (downloadedVersion < embeddedVersion) return false
        if (downloadedVersion == embeddedVersion) {
            val expected = embeddedMetadata.optString("sha256").lowercase()
            val downloaded = downloadedMetadata.optString("bundleSha256").lowercase()
            if (expected.matches(SHA256_PATTERN) && downloaded != expected) return false
        }
        return true
    }

    fun status(runningMode: String): JSONObject {
        val downloaded = hasDownloadedShell()
        val metadata = if (downloaded) readMetadata() else readEmbeddedMetadata()
        return JSONObject()
            .put("configuredMode", mode().value)
            .put("runningMode", runningMode)
            .put("onlineUrl", ONLINE_URL)
            .put("localSource", if (downloaded) "downloaded" else "embedded")
            .put("localVersion", metadata?.optString("versionName")?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME)
            .put("localVersionCode", metadata?.optLong("version")?.takeIf { it >= 0 } ?: JSONObject.NULL)
    }

    suspend fun updateLocal(): JSONObject = withContext(Dispatchers.IO) {
        updateLock.withLock {
            val manifest = fetchManifest()
            val version = manifest.optLong("version", -1)
            if (version < 0) throw HaminnException(ErrorCodes.NETWORK, "官网界面清单缺少有效版本")
            val expectedSha256 = manifest.optString("sha256").lowercase()
            if (!expectedSha256.matches(SHA256_PATTERN)) {
                throw HaminnException(ErrorCodes.NETWORK, "官网界面清单缺少有效包摘要")
            }
            val bundle = manifest.optString("bundle")
            val bundleUrl = MANIFEST_URL.toHttpUrl().resolve(bundle)
                ?: throw HaminnException(ErrorCodes.NETWORK, "官网界面包地址无效")
            if (bundleUrl.scheme != "https" || bundleUrl.host != OFFICIAL_HOST || bundleUrl.port != 443) {
                throw HaminnException(ErrorCodes.ORIGIN_DENIED, "官网界面包必须来自 $OFFICIAL_ORIGIN")
            }

            val archive = File(context.cacheDir, "official-shell-${UUID.randomUUID()}.zip")
            val staging = File(shellRoot, "incoming-${UUID.randomUUID()}")
            try {
                download(bundleUrl.toString(), archive)
                if (sha256(archive) != expectedSha256) {
                    throw HaminnException(ErrorCodes.NETWORK, "官网界面包摘要不匹配，请稍后重试", true)
                }
                extract(archive, staging)
                if (!File(staging, "index.html").isFile) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "官网界面包缺少 index.html")
                }
                File(staging, METADATA_FILE).writeText(JSONObject()
                    .put("version", version)
                    .put("versionName", manifest.optString("versionName", version.toString()))
                    .put("bundleSha256", expectedSha256)
                    .put("updatedAt", manifest.optString("updatedAt"))
                    .toString())
                shellRoot.mkdirs()
                if (downloadedRoot.exists() && !downloadedRoot.deleteRecursively()) {
                    throw HaminnException(ErrorCodes.STORAGE, "无法替换现有本地界面")
                }
                if (!staging.renameTo(downloadedRoot)) {
                    throw HaminnException(ErrorCodes.STORAGE, "无法启用新的本地界面")
                }
                status(Mode.LOCAL.value).put("updated", true)
            } finally {
                archive.delete()
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }

    private fun readMetadata(): JSONObject? = runCatching {
        File(downloadedRoot, METADATA_FILE).takeIf { it.isFile }?.readText()?.let(::JSONObject)
    }.getOrNull()

    private fun readEmbeddedMetadata(): JSONObject? = runCatching {
        context.assets.open("store/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun fetchManifest(): JSONObject {
        val request = Request.Builder().url(MANIFEST_URL).header("Cache-Control", "no-cache").build()
        return execute(request) { responseBytes ->
            if (responseBytes.size > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网界面清单过大")
            runCatching { JSONObject(responseBytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw HaminnException(ErrorCodes.NETWORK, "官网界面清单格式无效") }
        }
    }

    private fun download(url: String, destination: File) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        execute(request) { bytes ->
            if (bytes.size > MAX_BUNDLE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网界面包超过 32 MiB")
            FileOutputStream(destination).use { output -> output.write(bytes); output.fd.sync() }
        }
    }

    private fun <T> execute(request: Request, consume: (ByteArray) -> T): T {
        try {
            CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "官网下载失败：HTTP ${response.code}", response.code >= 500)
                val declared = response.body.contentLength()
                if (declared > MAX_BUNDLE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网下载内容过大")
                val input = response.body.byteStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BUNDLE_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网下载内容过大")
                    output.write(buffer, 0, read)
                }
                return consume(output.toByteArray())
            }
        } catch (error: Throwable) {
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "无法连接 Haminn 官网", true)
        }
    }

    private fun extract(archive: File, destination: File) {
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        var fileCount = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (name.startsWith('/') || name.split('/').any { it == ".." || it == "." }) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "官网界面包包含非法路径")
                }
                val target = File(canonicalRoot, name).canonicalFile
                if (target != canonicalRoot && !target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "官网界面包路径越界")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    fileCount++
                    if (fileCount > MAX_FILES) throw HaminnException(ErrorCodes.QUOTA, "官网界面包文件过多")
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_EXPANDED_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网界面包展开后过大")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val OFFICIAL_ORIGIN = "https://haminn.airen.life"
        const val OFFICIAL_HOST = "haminn.airen.life"
        const val ONLINE_URL = "$OFFICIAL_ORIGIN/shell/index.html"
        const val MANIFEST_URL = "$OFFICIAL_ORIGIN/shell/manifest.json"
        private const val KEY_MODE = "mode"
        private const val METADATA_FILE = ".haminn-shell.json"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
        private const val MAX_FILES = 512
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
