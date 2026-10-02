package life.airen.haminn.runtime

import android.content.Context
import android.content.pm.PackageManager
import life.airen.haminn.BuildConfig
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    data class UpdateOutcome(val status: JSONObject, val apkFile: File? = null)

    private val preferences = context.getSharedPreferences("official-shell", Context.MODE_PRIVATE)
    private val updateLock = Mutex()
    private val shellRoot = File(context.filesDir, "official-shell")
    private val apkUpdateRoot = File(context.filesDir, "official-updates")
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

    suspend fun statusWithOfficialVersions(runningMode: String): JSONObject = withContext(Dispatchers.IO) {
        val result = status(runningMode)
        try {
            val (apk, ui) = coroutineScope {
                val apkResult = async { fetchAndroidManifest(STATUS_CLIENT) }
                val uiResult = async { fetchManifest(STATUS_CLIENT) }
                apkResult.await() to uiResult.await()
            }
            val apkVersion = apk.optString("version")
            val apkVersionCode = apk.optLong("versionCode", -1L)
            if (apk.optString("package") != BuildConfig.APPLICATION_ID ||
                !VERSION_NAME_PATTERN.matches(apkVersion) || apkVersionCode <= 0L
            ) throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本信息无效")

            val uiVersion = ui.optString("versionName")
            val uiVersionCode = ui.optLong("version", -1L)
            if (!VERSION_NAME_PATTERN.matches(uiVersion) || uiVersionCode <= 0L) {
                throw HaminnException(ErrorCodes.NETWORK, "官网 HaminnUI 版本信息无效")
            }
            result.put("officialVersionsAvailable", true)
                .put("officialApkVersion", apkVersion)
                .put("officialApkVersionCode", apkVersionCode)
                .put("officialUiVersion", uiVersion)
                .put("officialUiVersionCode", uiVersionCode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            result.put("officialVersionsAvailable", false)
        }
        result
    }

    fun pendingApkUpdateFile(): File? {
        val name = preferences.getString(KEY_PENDING_APK_FILE, null) ?: return null
        if (!APK_FILENAME_PATTERN.matches(name)) return null
        return File(apkUpdateRoot, name).takeIf { it.isFile }
    }

    fun consumeInstalledApkUpdate(): Boolean {
        val expectedVersionCode = preferences.getLong(KEY_PENDING_APK_CODE, 0L)
        if (expectedVersionCode <= 0L || BuildConfig.VERSION_CODE.toLong() < expectedVersionCode) return false
        preferences.edit().remove(KEY_PENDING_APK_FILE).remove(KEY_PENDING_APK_CODE).apply()
        return true
    }

    suspend fun updateLocal(): UpdateOutcome = withContext(Dispatchers.IO) {
        updateLock.withLock {
            val apkManifest = fetchAndroidManifest()
            val apkVersionCode = apkManifest.optLong("versionCode", -1L)
            if (apkVersionCode <= 0L) throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本清单缺少有效版本")
            if (apkManifest.optString("package") != BuildConfig.APPLICATION_ID) {
                throw HaminnException(ErrorCodes.ORIGIN_DENIED, "官网 Haminn 安装包身份不匹配")
            }
            if (apkVersionCode > BuildConfig.VERSION_CODE.toLong()) {
                val apk = downloadOfficialApk(apkManifest)
                preferences.edit()
                    .putString(KEY_PENDING_APK_FILE, apk.name)
                    .putLong(KEY_PENDING_APK_CODE, apkVersionCode)
                    .apply()
                return@withLock UpdateOutcome(
                    status("local").put("action", "apk-update")
                        .put("latestHaminnVersion", apkManifest.optString("version"))
                        .put("latestHaminnVersionCode", apkVersionCode),
                    apk,
                )
            }

            val manifest = fetchManifest()
            val version = manifest.optLong("version", -1)
            if (version < 0) throw HaminnException(ErrorCodes.NETWORK, "官网界面清单缺少有效版本")
            val expectedSha256 = manifest.optString("sha256").lowercase()
            if (!expectedSha256.matches(SHA256_PATTERN)) {
                throw HaminnException(ErrorCodes.NETWORK, "官网界面清单缺少有效包摘要")
            }
            val localMetadata = currentLocalMetadata()
            val localVersion = localMetadata?.optLong("version", -1L) ?: -1L
            if (version <= localVersion) {
                val localSha = localMetadata?.optString(
                    if (hasDownloadedShell()) "bundleSha256" else "sha256",
                )?.lowercase().orEmpty()
                if (version == localVersion && localSha != expectedSha256) {
                    throw HaminnException(ErrorCodes.NETWORK, "官网 HaminnUI 版本号未递增，但安装包摘要已变化")
                }
                return@withLock UpdateOutcome(status(mode().value).put("updated", false).put("action", "up-to-date"))
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
                UpdateOutcome(status(mode().value).put("updated", true).put("action", "shell-updated"))
            } finally {
                archive.delete()
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }

    private fun currentLocalMetadata(): JSONObject? = if (hasDownloadedShell()) readMetadata() else readEmbeddedMetadata()

    private fun fetchAndroidManifest(client: OkHttpClient = CLIENT): JSONObject {
        val request = Request.Builder().url(APK_MANIFEST_URL).header("Cache-Control", "no-cache").build()
        return execute(request, client) { responseBytes ->
            if (responseBytes.size > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网 Haminn 版本清单过大")
            runCatching { JSONObject(responseBytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本清单格式无效") }
        }
    }

    private fun downloadOfficialApk(manifest: JSONObject): File {
        val fileName = manifest.optString("file")
        if (!APK_FILENAME_PATTERN.matches(fileName)) {
            throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 安装包文件名无效")
        }
        val versionName = manifest.optString("version")
        if (!VERSION_NAME_PATTERN.matches(versionName)) {
            throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本名称无效")
        }
        if (fileName != "haminn-v$versionName-release.apk") {
            throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 安装包文件名与版本不一致")
        }
        val bytes = manifest.optLong("bytes", -1L)
        if (bytes !in 1L..MAX_APK_BYTES) throw HaminnException(ErrorCodes.QUOTA, "官网 Haminn 安装包大小无效")
        val expectedSha256 = manifest.optString("sha256").lowercase()
        if (!expectedSha256.matches(SHA256_PATTERN)) {
            throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本清单缺少有效包摘要")
        }
        val versionCode = manifest.optLong("versionCode", -1L)
        if (versionCode <= 0L) throw HaminnException(ErrorCodes.NETWORK, "官网 Haminn 版本号无效")
        val apkUrl = "$OFFICIAL_ORIGIN/downloads/$fileName".toHttpUrl()
        if (apkUrl.scheme != "https" || apkUrl.host != OFFICIAL_HOST || apkUrl.port != 443 || apkUrl.encodedPath != "/downloads/$fileName") {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "Haminn 安装包必须来自 $OFFICIAL_ORIGIN")
        }

        apkUpdateRoot.mkdirs()
        val destination = File(apkUpdateRoot, fileName)
        if (destination.isFile && destination.length() == bytes && sha256(destination) == expectedSha256) {
            verifyApkArchive(destination, versionCode, versionName)
            return destination
        }
        if (destination.exists() && !destination.delete()) {
            throw HaminnException(ErrorCodes.STORAGE, "无法替换已下载的 Haminn 安装包")
        }
        val temporary = File(apkUpdateRoot, ".$fileName-${UUID.randomUUID()}.part")
        try {
            downloadApk(apkUrl.toString(), temporary, bytes)
            if (sha256(temporary) != expectedSha256) {
                throw HaminnException(ErrorCodes.NETWORK, "Haminn 安装包摘要不匹配，请稍后重试", true)
            }
            verifyApkArchive(temporary, versionCode, versionName)
            if (!temporary.renameTo(destination)) throw HaminnException(ErrorCodes.STORAGE, "无法保存 Haminn 安装包")
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun verifyApkArchive(file: File, expectedVersionCode: Long, expectedVersionName: String) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "官网下载内容不是有效的 Android 安装包")
        if (info.packageName != BuildConfig.APPLICATION_ID || info.longVersionCode != expectedVersionCode || info.versionName != expectedVersionName) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "Haminn 安装包的应用身份或版本与官网清单不一致")
        }
    }

    private fun downloadApk(url: String, destination: File, expectedBytes: Long) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        try {
            APK_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "Haminn 安装包下载失败：HTTP ${response.code}", response.code >= 500)
                val declared = response.body.contentLength()
                if (declared > MAX_APK_BYTES || (declared >= 0L && declared != expectedBytes)) {
                    throw HaminnException(ErrorCodes.QUOTA, "Haminn 安装包大小与官网清单不一致")
                }
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                FileOutputStream(destination).use { output ->
                    val input = response.body.byteStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES || total > expectedBytes) {
                            throw HaminnException(ErrorCodes.QUOTA, "Haminn 安装包超过官网清单大小")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
                if (total != expectedBytes) throw HaminnException(ErrorCodes.NETWORK, "Haminn 安装包未完整下载，请稍后重试", true)
            }
        } catch (error: Throwable) {
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "无法下载 Haminn 安装包", true)
        }
    }

    private fun readMetadata(): JSONObject? = runCatching {
        File(downloadedRoot, METADATA_FILE).takeIf { it.isFile }?.readText()?.let(::JSONObject)
    }.getOrNull()

    private fun readEmbeddedMetadata(): JSONObject? = runCatching {
        context.assets.open("store/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    private fun fetchManifest(client: OkHttpClient = CLIENT): JSONObject {
        val request = Request.Builder().url(MANIFEST_URL).header("Cache-Control", "no-cache").build()
        return execute(request, client) { responseBytes ->
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

    private fun <T> execute(request: Request, client: OkHttpClient = CLIENT, consume: (ByteArray) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
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
        const val APK_MANIFEST_URL = "$OFFICIAL_ORIGIN/downloads/android.json"
        private const val KEY_MODE = "mode"
        private const val KEY_PENDING_APK_FILE = "pending-apk-file"
        private const val KEY_PENDING_APK_CODE = "pending-apk-version-code"
        private const val METADATA_FILE = ".haminn-shell.json"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
        private const val MAX_APK_BYTES = 256L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024
        private const val MAX_FILES = 512
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val APK_FILENAME_PATTERN = Regex("^haminn-v[0-9]+\\.[0-9]+\\.[0-9]+-release\\.apk$")
        private val VERSION_NAME_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        private val CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        private val APK_CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
        private val STATUS_CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
