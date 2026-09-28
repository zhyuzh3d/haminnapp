package life.airen.haminn.install

import android.content.Context
import android.net.Uri
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HappSource
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.WebAppInstance
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class RemoteSourceInstaller(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
) {
    data class OnlineInstallResult(
        val appId: String,
        val releaseId: String?,
        val strategy: String,
        val kind: String,
        val operationId: String? = null,
        val treeHash: String? = null,
    )

    private data class InstallManifest(
        val manifestUrl: String,
        val packageUrl: String,
        val sha256: String?,
    )

    private data class RepositoryFile(val path: String, val size: Long)

    /**
     * What a URL resolves to before anything is installed. `kind` is "package"
     * when a local happ can be materialized, "repository" for a Git source and
     * "live" for a plain page; only "package" carries a downloaded file.
     */
    data class SourcePreview(
        val kind: String,
        val file: File? = null,
        val declaredSha256: String? = null,
        val downloadUrl: String? = null,
        val pageUrl: String? = null,
        val suggestedName: String? = null,
        val liveUrl: String? = null,
        val provenance: String = "https-package",
        val description: PackageDescription? = null,
    )

    /**
     * A URL makes this an online-source happ. If the origin publishes
     * /haminn-install.json, its package is materialized locally by default;
     * otherwise the URL is saved for live runtime.
     */
    suspend fun installOnline(url: String, name: String?,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
        directoryChoice: suspend (RepositorySource, List<RepositoryDirectory>) -> String? = { _, _ -> null },
    ): OnlineInstallResult = withContext(Dispatchers.IO) {
        val normalized = validateNetworkUrl(url)
        val parsed = normalized.toHttpUrl()
        RepositorySourceRules.parse(normalized)?.let { repository ->
            return@withContext installRepository(repository, name, identityChoice = identityChoice, directoryChoice = directoryChoice)
        }
        if (parsed.encodedPath.endsWith(".zip", ignoreCase = true)) {
            val result = installHttps(normalized, name, identityChoice = identityChoice)
            return@withContext OnlineInstallResult(result.appId, result.releaseId, "local", "package")
        }
        if (parsed.encodedPath.substringAfterLast('/').equals("haminn-install.json", ignoreCase = true)) {
            val manifest = readInstallManifest(parsed, required = true)!!
            return@withContext installManifestPackage(manifest, name, null, "online-descriptor", "descriptor", identityChoice)
        }
        val manifest = discoverInstallManifest(normalized)
        if (manifest == null) {
            val candidate = download(normalized)
            try {
                if (isZipArchive(candidate)) {
                    val result = FileInputStream(candidate).use {
                        installer.installZip(it, name, provenance = "https-package", source = HappSource.ONLINE,
                            downloadUrl = normalized, identityChoice = identityChoice)
                    }
                    registry.updateSource(result.appId, "https-package", JSONObject().put("url", normalized).toString())
                    registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, normalized, it.versionCode, it.versionName) }
                    return@withContext OnlineInstallResult(result.appId, result.releaseId, "local", "package")
                }
            } finally { candidate.delete() }
            val fallbackName = name?.trim()?.takeIf { it.isNotBlank() }
                ?: normalized.toHttpUrl().host
            val app = WebAppInstance.newOnlineLive(fallbackName, normalized)
            registry.insertInstance(app)
            registry.updateSource(app.appId, "online", JSONObject().put("url", normalized).toString())
            return@withContext OnlineInstallResult(app.appId, null, "live", "live")
        }

        installManifestPackage(manifest, name, normalized, "online-manifest", "manifest", identityChoice)
    }

    /**
     * Resolves a URL far enough to show the user what will be installed. The
     * downloaded package is kept in the cache and reused by the confirming
     * install, so a package is never downloaded twice.
     */
    suspend fun previewOnline(url: String): SourcePreview = withContext(Dispatchers.IO) {
        val normalized = validateNetworkUrl(url)
        val parsed = normalized.toHttpUrl()
        RepositorySourceRules.parse(normalized)?.let { repository ->
            return@withContext SourcePreview("repository", pageUrl = normalized, suggestedName = repository.repository)
        }
        if (parsed.encodedPath.endsWith(".zip", ignoreCase = true)) {
            return@withContext packagePreview(download(normalized), downloadUrl = normalized)
        }
        if (parsed.encodedPath.substringAfterLast('/').equals(MANIFEST_FILE_NAME, ignoreCase = true)) {
            val manifest = readInstallManifest(parsed, required = true)!!
            return@withContext packagePreview(
                downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl),
                downloadUrl = manifest.packageUrl,
                declaredSha256 = manifest.sha256,
                provenance = "online-descriptor",
            )
        }
        discoverInstallManifest(normalized)?.let { manifest ->
            return@withContext packagePreview(
                downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl),
                downloadUrl = manifest.packageUrl,
                declaredSha256 = manifest.sha256,
                liveUrl = normalized,
                provenance = "online-manifest",
            )
        }
        SourcePreview("live", pageUrl = normalized, suggestedName = parsed.host)
    }

    private fun packagePreview(
        file: File,
        downloadUrl: String?,
        declaredSha256: String? = null,
        liveUrl: String? = null,
        provenance: String = "https-package",
    ): SourcePreview = SourcePreview(
        kind = "package",
        file = file,
        declaredSha256 = declaredSha256,
        downloadUrl = downloadUrl,
        liveUrl = liveUrl,
        provenance = provenance,
        description = installer.describePackage(file),
    )

    private suspend fun installRepository(
        source: RepositorySource,
        name: String?,
        existingAppId: String? = null,
        expected: String? = null,
        rememberedPath: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
        directoryChoice: suspend (RepositorySource, List<RepositoryDirectory>) -> String? = { _, _ -> null },
    ): OnlineInstallResult = withContext(Dispatchers.IO) {
        val branch = source.ref ?: repositoryDefaultBranch(source)
        val revision = if (source.provider == RepositoryProvider.GITHUB) resolveGitHubCommit(source.namespace, source.repository, branch) else branch
        val resolved = source.copy(ref = branch)
        val manifestPath = listOf(source.path, "haminn-install.json").filter { it.isNotBlank() }.joinToString("/")
        val manifest = readInstallManifest(repositoryRawUrl(resolved, revision, manifestPath), required = false)
        if (manifest != null) {
            val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
            try {
                val result = FileInputStream(archive).use {
                    installer.installZip(it, name, existingAppId, source.provider.adapter, expectedReleaseId = expected,
                        declaredSha256 = manifest.sha256, sourceRevision = revision, fallbackName = source.repository,
                        source = HappSource.ONLINE, downloadUrl = manifest.packageUrl, identityChoice = identityChoice)
                }
                rememberRepository(result, resolved, revision, source.path, "manifest")
                return@withContext OnlineInstallResult(result.appId, result.releaseId, "local", "repository-package", result.operationId, result.treeHash)
            } finally { archive.delete() }
        }

        val filtered = File(context.cacheDir, "repository-${UUID.randomUUID()}.zip")
        var sourceArchive: File? = null
        try {
            val repositoryFiles = if (source.provider == RepositoryProvider.GITHUB) {
                githubRepositoryFiles(resolved, revision)
            } else {
                sourceArchive = download(repositoryArchiveUrl(resolved, revision), repositoryArchiveHeaders(resolved))
                repositoryEntries(sourceArchive!!).map { RepositoryFile(it, -1L) }
            }
            val choices = RepositorySourceRules.directoryChoices(repositoryFiles.map(RepositoryFile::path), source.path)
            val selected = rememberedPath?.takeIf { path -> choices.any { option -> option.path == path } }
                ?: directoryChoice(resolved, choices)
                ?: throw HaminnException(ErrorCodes.CANCELLED, "用户取消安装")
            val subtree = listOf(source.path, selected).filter { it.isNotBlank() }.joinToString("/")
            if (source.provider == RepositoryProvider.GITHUB) {
                downloadGitHubDirectory(resolved, revision, repositoryFiles, subtree, filtered)
            } else {
                filterRepositoryArchive(sourceArchive!!, filtered, subtree)
            }
            val result = FileInputStream(filtered).use {
                installer.installZip(it, name, existingAppId, source.provider.adapter, expectedReleaseId = expected,
                    sourceRevision = revision, fallbackName = source.repository, source = HappSource.ONLINE,
                    downloadUrl = source.cloneUrl, identityChoice = identityChoice)
            }
            rememberRepository(result, resolved, revision, selected, "directory")
            OnlineInstallResult(result.appId, result.releaseId, "local", "repository-directory", result.operationId, result.treeHash)
        } finally { sourceArchive?.delete(); filtered.delete() }
    }

    private fun rememberRepository(result: InstallResult, source: RepositorySource, revision: String, selectedPath: String, mode: String) {
        registry.updateSource(result.appId, source.provider.adapter, JSONObject()
            .put("provider", source.provider.adapter).put("namespace", source.namespace).put("repo", source.repository)
            .put("ref", source.ref).put("resolvedRevision", revision).put("rootPath", source.path)
            .put("path", selectedPath).put("mode", mode).toString())
        registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, source.cloneUrl, it.versionCode, it.versionName) }
        val installed = registry.getInstance(result.appId)
        if (installed?.updateUrl == null) registry.updateUrls(result.appId, installed?.liveUrl, source.cloneUrl)
    }

    private suspend fun installManifestPackage(
        manifest: InstallManifest,
        name: String?,
        liveUrl: String?,
        provenance: String,
        kind: String,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice,
    ): OnlineInstallResult {
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(
                    it,
                    name,
                    provenance = provenance,
                    declaredSha256 = manifest.sha256,
                    fallbackName = liveUrl?.toHttpUrl()?.host ?: manifest.manifestUrl.toHttpUrl().host,
                    source = HappSource.ONLINE,
                    liveUrl = liveUrl,
                    downloadUrl = manifest.packageUrl,
                    identityChoice = identityChoice,
                )
            }
            registry.updateSource(result.appId, provenance, JSONObject()
                .put("url", liveUrl ?: manifest.manifestUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            return OnlineInstallResult(result.appId, result.releaseId, "local", kind)
        } finally {
            archive.delete()
        }
    }

    suspend fun installHttps(url: String, name: String?, existingAppId: String? = null, expected: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE }): InstallResult =
        withContext(Dispatchers.IO) {
            val normalized = validateDownloadUrl(url)
            val archive = download(normalized)
            try {
                val result = FileInputStream(archive).use {
                    installer.installZip(it, name, existingAppId, "https-package", expectedReleaseId = expected,
                    source = HappSource.ONLINE, downloadUrl = normalized, identityChoice = identityChoice)
                }
            registry.updateSource(result.appId, "https-package", JSONObject().put("url", normalized).toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, normalized, it.versionCode, it.versionName) }
            result
            } finally { archive.delete() }
        }

    suspend fun installGitHub(
        owner: String,
        repo: String,
        ref: String,
        path: String,
        name: String?,
        existingAppId: String? = null,
        expected: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
    ): InstallResult = withContext(Dispatchers.IO) {
        validateSlug(owner, "owner"); validateSlug(repo, "repo")
        if (ref.isBlank() || ref.length > 200 || ref.contains("..")) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "GitHub ref 无效")
        val normalizedPath = path.trim('/').also {
            if (it.isNotEmpty()) validateRelative(it)
        }
        val installed = installRepository(
            RepositorySource(RepositoryProvider.GITHUB, owner, repo, ref, normalizedPath),
            name,
            existingAppId,
            expected,
            rememberedPath = "",
            identityChoice = identityChoice,
        )
        val releaseId = installed.releaseId ?: throw HaminnException(ErrorCodes.INTERNAL, "GitHub 安装没有生成本地版本")
        InstallResult(
            installed.operationId ?: UUID.randomUUID().toString(),
            installed.appId,
            releaseId,
            installed.treeHash ?: registry.getRelease(releaseId)?.treeHash
                ?: throw HaminnException(ErrorCodes.INTERNAL, "GitHub 安装结果不完整"),
        )
    }

    suspend fun update(appId: String): InstallResult = withContext(Dispatchers.IO) {
        val app = registry.getInstance(appId) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        app.updateUrl?.let { return@withContext installUpdateUrl(app, it) }
        val spec = JSONObject(app.sourceSpec)
        when (app.sourceAdapter) {
            "https-package" -> installHttps(spec.getString("url"), app.name, appId, app.activeReleaseId)
            "github" -> installGitHub(spec.getString("owner"), spec.getString("repo"), spec.getString("ref"),
                spec.optString("path"), app.name, appId, app.activeReleaseId)
            "online-manifest" -> installManifestUpdate(app)
            "online-descriptor" -> installDescriptorUpdate(app)
            else -> throw HaminnException(ErrorCodes.UNSUPPORTED, "该来源不支持在线检查更新")
        }
    }

    suspend fun reinstall(appId: String): InstallResult = withContext(Dispatchers.IO) {
        val app = registry.getInstance(appId) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        // A locally imported package is reinstalled from its retained copy; anything else
        // follows the address it originally came from.
        val retained = app.sourceUri?.let(::File)?.takeIf { it.isFile }
        if (retained != null) {
            return@withContext retained.inputStream().use { input ->
                installer.installZip(input, app.name, app.appId, "local-source", expectedReleaseId = app.activeReleaseId)
            }
        }
        val url = app.downloadUrl
            ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "没有可用的原始安装来源")
        installDownloadUrl(app, url)
    }

    private suspend fun installDownloadUrl(app: WebAppInstance, rawUrl: String): InstallResult {
        val repository = RepositorySourceRules.parse(rawUrl)
        if (repository != null) {
            val source = runCatching { JSONObject(app.sourceSpec) }.getOrDefault(JSONObject())
            val rootPath = source.optString("rootPath", repository.path)
            val configured = repository.copy(
                ref = source.optString("ref").takeIf { it.isNotBlank() } ?: repository.ref,
                path = rootPath,
            )
            val rememberedPath = source.optString("path").takeIf { source.optString("mode") == "directory" }
            val installed = installRepository(configured, app.name, app.appId, app.activeReleaseId, rememberedPath)
            val releaseId = installed.releaseId ?: throw HaminnException(ErrorCodes.INTERNAL, "仓库更新没有生成本地版本")
            return InstallResult(installed.operationId ?: UUID.randomUUID().toString(), installed.appId, releaseId,
                installed.treeHash ?: registry.getRelease(releaseId)?.treeHash
                ?: throw HaminnException(ErrorCodes.INTERNAL, "仓库更新结果不完整"))
        }
        if (rawUrl.trim().endsWith(".git", true)) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "当前只识别 GitHub、GitLab 和 Gitee 仓库；其他 Git 服务请提供 ZIP 下载地址")
        }
        return installHttps(rawUrl, app.name, app.appId, app.activeReleaseId)
    }

    private suspend fun installUpdateUrl(app: WebAppInstance, rawUrl: String): InstallResult {
        val updateUrl = validateNetworkUrl(rawUrl)
        if (RepositorySourceRules.parse(updateUrl) != null) return installDownloadUrl(app, updateUrl)
        if (!Uri.parse(updateUrl).path.orEmpty().endsWith(".json", true)) {
            val archive = download(updateUrl)
            val result = try {
                FileInputStream(archive).use { installer.installZip(it, app.name, app.appId, "update-url", expectedReleaseId = app.activeReleaseId) }
            } finally { archive.delete() }
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, updateUrl, it.versionCode, it.versionName) }
            return result
        }
        val endpoint = updateUrl.toHttpUrl()
        val client = clientFor(endpoint.host)
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").build()
        val text = executeWithRetries(client, request).use { response ->
            if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "更新信息请求失败：HTTP ${response.code}", response.code >= 500)
            if (response.body.contentLength() > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "更新信息过大")
            readAtMost(response.body.byteStream(), MAX_MANIFEST_BYTES + 1).also {
                if (it.size > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "更新信息过大")
            }.toString(Charsets.UTF_8)
        }
        val json = runCatching { JSONObject(text) }.getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "更新信息格式无效") }
        if (json.keys().asSequence().any { it !in setOf("schema", "version", "package", "sha256") } || json.optInt("schema", -1) != 1) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "更新信息字段无效")
        }
        val packageUrl = endpoint.resolve(json.getString("package"))?.toString()
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "更新包地址无效")
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() }
        if (sha256 != null && !sha256.matches(SHA256_PATTERN)) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "更新包摘要无效")
        val archive = downloadSameOrigin(packageUrl, updateUrl)
        val result = try {
            FileInputStream(archive).use { installer.installZip(it, app.name, app.appId, "update-url",
                expectedReleaseId = app.activeReleaseId, declaredSha256 = sha256) }
        } finally { archive.delete() }
        registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, packageUrl, it.versionCode, it.versionName) }
        return result
    }

    private suspend fun installManifestUpdate(app: WebAppInstance): InstallResult {
        val liveUrl = app.liveUrl ?: throw HaminnException(ErrorCodes.CONFLICT, "线上 happ 缺少页面地址")
        val manifest = discoverInstallManifest(liveUrl)
            ?: throw HaminnException(ErrorCodes.NETWORK, "线上 happ 不再提供 haminn-install.json", true)
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(it, app.name, app.appId, "online-manifest",
                    expectedReleaseId = app.activeReleaseId, declaredSha256 = manifest.sha256)
            }
            registry.updateSource(app.appId, "online-manifest", JSONObject()
                .put("url", liveUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, manifest.packageUrl, it.versionCode, it.versionName) }
            return result
        } finally {
            archive.delete()
        }
    }

    private suspend fun installDescriptorUpdate(app: WebAppInstance): InstallResult {
        val source = runCatching { JSONObject(app.sourceSpec) }.getOrElse {
            throw HaminnException(ErrorCodes.STORAGE, "安装来源记录已损坏")
        }
        val manifestUrl = source.optString("manifestUrl").takeIf { it.isNotBlank() }
            ?: throw HaminnException(ErrorCodes.STORAGE, "安装配置地址缺失")
        val manifest = readInstallManifest(manifestUrl.toHttpUrl(), required = true)!!
        val archive = downloadSameOrigin(manifest.packageUrl, manifest.manifestUrl)
        try {
            val result = FileInputStream(archive).use {
                installer.installZip(it, app.name, app.appId, "online-descriptor",
                    expectedReleaseId = app.activeReleaseId, declaredSha256 = manifest.sha256)
            }
            registry.updateSource(result.appId, "online-descriptor", JSONObject()
                .put("url", manifest.manifestUrl)
                .put("manifestUrl", manifest.manifestUrl)
                .put("packageUrl", manifest.packageUrl)
                .put("sha256", manifest.sha256 ?: JSONObject.NULL)
                .toString())
            registry.getRelease(result.releaseId)?.let { registry.recordDownload(result.appId, manifest.packageUrl, it.versionCode, it.versionName) }
            return result
        } finally {
            archive.delete()
        }
    }

    private fun discoverInstallManifest(pageUrl: String): InstallManifest? {
        val page = pageUrl.toHttpUrl()
        val adjacent = page.resolve("haminn-install.json")
        if (adjacent != null) readInstallManifest(adjacent, required = false)?.let { return it }
        val manifestUrl = page.newBuilder()
            .encodedPath("/haminn-install.json")
            .query(null)
            .fragment(null)
            .build()
        if (adjacent == manifestUrl) return null
        return readInstallManifest(manifestUrl, required = false)
    }

    private fun readInstallManifest(manifestUrl: okhttp3.HttpUrl, required: Boolean): InstallManifest? {
        val request = Request.Builder().url(manifestUrl).header("Accept", "application/json").build()
        val body = try {
            executeWithRetries(SAME_ORIGIN_CLIENT, request).use { response ->
                if (response.code == 404 || response.code == 410) {
                    if (required) throw HaminnException(ErrorCodes.NETWORK, "安装配置不存在：HTTP ${response.code}")
                    return null
                }
                if (!response.isSuccessful) {
                    if (required) throw HaminnException(ErrorCodes.NETWORK, "安装配置读取失败：HTTP ${response.code}", response.code >= 500)
                    return null
                }
                val declared = response.body.contentLength()
                if (declared > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "haminn-install.json 过大")
                val output = java.io.ByteArrayOutputStream()
                val input = response.body.byteStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "haminn-install.json 过大")
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } catch (error: IOException) {
            if (required) throw HaminnException(ErrorCodes.NETWORK, error.message ?: "安装配置读取失败", true)
            return null
        }
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "haminn-install.json 格式无效") }
        if (json.optInt("schema", -1) != 1) throw HaminnException(ErrorCodes.UNSUPPORTED, "不支持的 haminn-install.json 版本")
        if (json.keys().asSequence().any { it !in setOf("schema", "package", "sha256") }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "haminn-install.json 包含未知字段")
        }
        val relativePackage = json.optString("package")
        validatePackagePath(relativePackage)
        val packageUrl = manifestUrl.resolve(relativePackage)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "安装包地址无效")
        if (packageUrl.scheme != manifestUrl.scheme || packageUrl.host != manifestUrl.host || packageUrl.port != manifestUrl.port) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "自动安装包必须与 happ 页面同源")
        }
        val sha256 = json.optString("sha256").takeIf { it.isNotBlank() }?.lowercase()
        if (sha256 != null && !sha256.matches(SHA256_PATTERN)) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "安装包摘要格式无效")
        }
        return InstallManifest(manifestUrl.toString(), packageUrl.toString(), sha256)
    }

    private fun downloadSameOrigin(url: String, manifestUrl: String): File {
        val targetUrl = url.toHttpUrl()
        val manifest = manifestUrl.toHttpUrl()
        if (targetUrl.scheme != manifest.scheme || targetUrl.host != manifest.host || targetUrl.port != manifest.port) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "自动安装包必须与 happ 页面同源")
        }
        val target = File(context.cacheDir, "online-package-${UUID.randomUUID()}.zip")
        try {
            val request = Request.Builder().url(targetUrl).build()
            executeWithRetries(SAME_ORIGIN_CLIENT, request).use { response ->
                if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "安装包下载失败：HTTP ${response.code}", response.code >= 500)
                if (response.body.contentLength() > MAX_DOWNLOAD_BYTES) throw HaminnException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                FileOutputStream(target).use { output ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) throw HaminnException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "远程包下载失败", true)
        }
    }

    private fun validateNetworkUrl(value: String): String {
        val normalized = value.trim()
        if (normalized.length > MAX_URL_LENGTH || normalized.any { it <= '\u001F' }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面地址无效")
        }
        val uri = Uri.parse(normalized)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.host!!.endsWith(".haminn.invalid", true)) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面地址必须是有效 HTTP(S) URL")
        }
        return uri.toString()
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray = input.use {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (output.size() < limit) {
            val read = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun validatePackagePath(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains('?') || path.contains('#') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "安装包必须是同源相对路径")
        }
    }

    private fun download(initialUrl: String, headers: Map<String, String> = emptyMap()): File {
        var url = validateDownloadUrl(initialUrl)
        var redirects = 0
        val target = File(context.cacheDir, "download-${UUID.randomUUID()}.zip")
        try {
            while (true) {
                val uri = Uri.parse(url)
                val addresses = InetAddress.getAllByName(uri.host).toList()
                if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }) {
                    throw HaminnException(ErrorCodes.ORIGIN_DENIED, "远程包地址不能指向本机")
                }
                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                    .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                    .dns { host -> if (host.equals(uri.host, true)) addresses else throw java.net.UnknownHostException(host) }.build()
                val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
                executeWithRetries(client, request).use { response ->
                    if (response.code in 300..399) {
                        if (redirects++ >= 5) throw HaminnException(ErrorCodes.NETWORK, "下载重定向次数过多")
                        val location = response.header("Location") ?: throw HaminnException(ErrorCodes.NETWORK, "下载重定向缺少地址")
                        url = validateDownloadUrl(response.request.url.resolve(location)?.toString() ?: "")
                        continue
                    }
                    if (!response.isSuccessful) throw HaminnException(ErrorCodes.NETWORK, "下载失败：HTTP ${response.code}", response.code >= 500)
                    if (response.body.contentLength() > MAX_DOWNLOAD_BYTES) throw HaminnException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                    FileOutputStream(target).use { output ->
                        val input = response.body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_DOWNLOAD_BYTES) throw HaminnException(ErrorCodes.QUOTA, "远程包超过 64 MiB")
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    return target
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "远程包下载失败", true)
        }
    }

    private fun resolveGitHubCommit(owner: String, repo: String, ref: String): String {
        val url = "$GITHUB_GATEWAY/api/".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
            .addPathSegment("commits").addPathSegment(ref).build()
        val addresses = InetAddress.getAllByName(url.host).toList()
        if (addresses.isEmpty() || addresses.any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
            }) throw HaminnException(ErrorCodes.ORIGIN_DENIED, "GitHub API 地址解析异常")
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(true).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .dns { host -> if (host.equals(url.host, true)) addresses else throw java.net.UnknownHostException(host) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").build()
        try {
            executeWithRetries(client, request).use { response ->
                if (!response.isSuccessful) {
                    val detail = if (response.code == 403 || response.code == 429) "（可能达到匿名 API 限额）" else ""
                    throw HaminnException(ErrorCodes.NETWORK, "无法解析 GitHub ref：HTTP ${response.code}$detail", response.code >= 500)
                }
                val length = response.body.contentLength()
                if (length > MAX_GITHUB_JSON_BYTES) throw HaminnException(ErrorCodes.QUOTA, "GitHub 响应过大")
                val text = response.body.charStream().readText()
                if (text.toByteArray().size > MAX_GITHUB_JSON_BYTES) throw HaminnException(ErrorCodes.QUOTA, "GitHub 响应过大")
                return JSONObject(text).getString("sha").takeIf { it.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) }
                    ?.lowercase() ?: throw HaminnException(ErrorCodes.NETWORK, "GitHub 返回了无效 commit")
            }
        } catch (error: Throwable) {
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "GitHub ref 解析失败", true)
        }
    }

    private fun repositoryDefaultBranch(source: RepositorySource): String {
        val endpoint = when (source.provider) {
            RepositoryProvider.GITHUB -> "$GITHUB_GATEWAY/api/".toHttpUrl().newBuilder()
                .addPathSegment("repos").addPathSegments(source.namespace).addPathSegment(source.repository).build()
            RepositoryProvider.GITLAB -> "https://gitlab.com".toHttpUrl().newBuilder()
                .addPathSegments("api/v4/projects").addPathSegment("${source.namespace}/${source.repository}").build()
            RepositoryProvider.GITEE -> "https://gitee.com".toHttpUrl().newBuilder()
                .addPathSegments("api/v5/repos").addPathSegments(source.namespace).addPathSegment(source.repository).build()
        }
        val json = readRepositoryJson(endpoint.toString(), source.provider)
        return json.optString("default_branch").takeIf { it.isNotBlank() && it.length <= 200 && !it.contains("..") }
            ?: throw HaminnException(ErrorCodes.NETWORK, "${source.provider.label} 没有返回默认分支")
    }

    private fun readRepositoryJson(url: String, provider: RepositoryProvider): JSONObject {
        val endpoint = url.toHttpUrl()
        val request = Request.Builder().url(endpoint).apply {
            if (provider == RepositoryProvider.GITHUB) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
            } else header("Accept", "application/json")
        }.build()
        try {
            executeWithRetries(clientFor(endpoint.host), request).use { response ->
                if (!response.isSuccessful) {
                    val detail = if (response.code == 403 || response.code == 429) "（可能达到匿名 API 限额）" else ""
                    throw HaminnException(ErrorCodes.NETWORK, "无法读取 ${provider.label} 仓库信息：HTTP ${response.code}$detail", response.code >= 500)
                }
                val bytes = readAtMost(response.body.byteStream(), MAX_GITHUB_JSON_BYTES + 1)
                if (bytes.size > MAX_GITHUB_JSON_BYTES) throw HaminnException(ErrorCodes.QUOTA, "仓库信息响应过大")
                return JSONObject(bytes.toString(Charsets.UTF_8))
            }
        } catch (error: Throwable) {
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.NETWORK, error.message ?: "仓库信息读取失败", true)
        }
    }

    private fun repositoryRawUrl(source: RepositorySource, revision: String, path: String): okhttp3.HttpUrl {
        val builder = when (source.provider) {
            RepositoryProvider.GITHUB -> "$GITHUB_GATEWAY/raw/".toHttpUrl().newBuilder()
                .addPathSegments(source.namespace).addPathSegment(source.repository).addPathSegment(revision)
            RepositoryProvider.GITLAB -> source.webUrl.toHttpUrl().newBuilder()
                .addPathSegment("-").addPathSegment("raw").addPathSegment(revision)
            RepositoryProvider.GITEE -> source.webUrl.toHttpUrl().newBuilder()
                .addPathSegment("raw").addPathSegment(revision)
        }
        if (path.isNotBlank()) builder.addPathSegments(path)
        return builder.build()
    }

    private fun repositoryArchiveUrl(source: RepositorySource, revision: String): String = when (source.provider) {
        RepositoryProvider.GITHUB -> "$GITHUB_GATEWAY/archive/".toHttpUrl().newBuilder()
            .addPathSegments(source.namespace).addPathSegment(source.repository)
            .addPathSegment("zip").addPathSegment(revision).build().toString()
        RepositoryProvider.GITLAB -> source.webUrl.toHttpUrl().newBuilder()
            .addPathSegment("-").addPathSegment("archive").addPathSegment(revision)
            .addPathSegment("${source.repository}-${revision.replace('/', '-')}.zip").build().toString()
        RepositoryProvider.GITEE -> source.webUrl.toHttpUrl().newBuilder()
            .addPathSegments("repository/archive").addPathSegment("$revision.zip").build().toString()
    }

    private fun repositoryArchiveHeaders(source: RepositorySource): Map<String, String> =
        if (source.provider == RepositoryProvider.GITHUB) mapOf(
            "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"
        ) else emptyMap()

    private fun githubRepositoryFiles(source: RepositorySource, revision: String): List<RepositoryFile> {
        val endpoint = "$GITHUB_GATEWAY/api/".toHttpUrl().newBuilder()
            .addPathSegment("repos").addPathSegments(source.namespace).addPathSegment(source.repository)
            .addPathSegments("git/trees").addPathSegment(revision)
            .addQueryParameter("recursive", "1").build()
        val json = readRepositoryJson(endpoint.toString(), RepositoryProvider.GITHUB)
        if (json.optBoolean("truncated")) {
            throw HaminnException(ErrorCodes.QUOTA, "仓库目录过大，请提供发布 ZIP 或 haminn-install.json")
        }
        val tree = json.optJSONArray("tree")
            ?: throw HaminnException(ErrorCodes.NETWORK, "GitHub 没有返回仓库目录")
        if (tree.length() > MAX_REPOSITORY_ENTRIES) throw HaminnException(ErrorCodes.QUOTA, "仓库文件数量超过限制")
        val files = buildList {
            for (index in 0 until tree.length()) {
                val entry = tree.optJSONObject(index) ?: continue
                if (entry.optString("type") != "blob") continue
                val path = entry.optString("path")
                validateRelative(path)
                val size = entry.optLong("size", -1L)
                if (size > MAX_EXPANDED_BYTES) throw HaminnException(ErrorCodes.QUOTA, "仓库文件过大：$path")
                add(RepositoryFile(path, size))
            }
        }
        if (files.isEmpty()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "仓库中没有可安装文件")
        return files
    }

    private fun downloadGitHubDirectory(
        source: RepositorySource,
        revision: String,
        files: List<RepositoryFile>,
        subtree: String,
        output: File,
    ) {
        val selected = files.mapNotNull { file ->
            val relative = when {
                subtree.isBlank() -> file.path
                file.path.startsWith("$subtree/") -> file.path.removePrefix("$subtree/")
                else -> return@mapNotNull null
            }
            RepositoryFile(relative, file.size)
        }
        if (selected.isEmpty()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "仓库目录为空或不存在")
        if (selected.size > MAX_REPOSITORY_FILES) throw HaminnException(ErrorCodes.QUOTA, "所选目录文件数量超过限制")
        val declaredSize = selected.map(RepositoryFile::size).filter { it >= 0 }.sum()
        if (declaredSize > MAX_EXPANDED_BYTES) throw HaminnException(ErrorCodes.QUOTA, "所选目录展开后超过 256 MiB")
        var total = 0L
        val repositoryClient = clientFor(GITHUB_GATEWAY.toHttpUrl().host)
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            selected.forEach { file ->
                validateRelative(file.path)
                val remotePath = listOf(subtree, file.path).filter { it.isNotBlank() }.joinToString("/")
                val endpoint = repositoryRawUrl(source, revision, remotePath)
                val request = Request.Builder().url(endpoint).build()
                executeWithRetries(repositoryClient, request).use { response ->
                    if (!response.isSuccessful) {
                        throw HaminnException(ErrorCodes.NETWORK, "仓库文件下载失败：${file.path}（HTTP ${response.code}）", response.code >= 500)
                    }
                    zip.putNextEntry(ZipEntry(file.path))
                    val input = response.body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_EXPANDED_BYTES) throw HaminnException(ErrorCodes.QUOTA, "所选目录展开后超过 256 MiB")
                        zip.write(buffer, 0, read)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun repositoryEntries(source: File): List<String> {
        val entries = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val parts = entry.name.trimEnd('/').split('/')
                if (parts.size < 2) continue
                val relative = parts.drop(1).joinToString("/")
                if (relative.isNotBlank()) {
                    validateRelative(relative)
                    entries += relative
                    if (entries.size > MAX_REPOSITORY_ENTRIES) throw HaminnException(ErrorCodes.QUOTA, "仓库文件数量超过限制")
                }
            }
        }
        if (entries.isEmpty()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "仓库中没有可安装文件")
        return entries.toList()
    }

    private fun isZipArchive(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val signature = ByteArray(4)
            input.read(signature) == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte() &&
                signature[2] in listOf(3, 5, 7).map(Int::toByte) && signature[3] in listOf(4, 6, 8).map(Int::toByte)
        }
    }.getOrDefault(false)

    private fun filterRepositoryArchive(source: File, output: File, subtree: String) {
        var written = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val parts = entry.name.trimEnd('/').split('/')
                    if (parts.size < 2) continue
                    val relativeToRepo = parts.drop(1).joinToString("/")
                    val relative = if (subtree.isEmpty()) relativeToRepo else {
                        if (relativeToRepo == subtree || !relativeToRepo.startsWith("$subtree/")) continue
                        relativeToRepo.removePrefix("$subtree/")
                    }
                    if (relative.isBlank() || entry.isDirectory) continue
                    validateRelative(relative)
                    zip.putNextEntry(ZipEntry(relative))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_EXPANDED_BYTES) throw HaminnException(ErrorCodes.QUOTA, "GitHub 源码展开后过大")
                        zip.write(buffer, 0, read)
                    }
                    zip.closeEntry(); written++
                }
            }
        }
        if (written == 0) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "仓库目录为空或不存在")
    }

    private fun validateDownloadUrl(value: String): String {
        val normalized = value.trim()
        if (normalized.length > MAX_URL_LENGTH) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "远程包地址过长")
        if (normalized.any { it <= '\u001F' }) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "远程包地址包含控制字符")
        val uri = Uri.parse(normalized)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.host!!.endsWith(".haminn.invalid", true)) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "远程包必须使用有效 HTTP(S) 地址")
        }
        return uri.toString()
    }

    private fun clientFor(host: String): OkHttpClient {
        val addresses = InetAddress.getAllByName(host).toList()
        if (addresses.isEmpty() || addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress }) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "地址不能指向本机")
        }
        return OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .dns { requested -> if (requested.equals(host, true)) addresses else throw java.net.UnknownHostException(requested) }.build()
    }

    private fun executeWithRetries(client: OkHttpClient, request: Request, attempts: Int = 3): Response {
        var lastError: IOException? = null
        repeat(attempts) { attempt ->
            try {
                return client.newCall(request).execute()
            } catch (error: IOException) {
                lastError = error
                if (attempt + 1 < attempts) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("网络请求失败")
    }

    private fun validateSlug(value: String, field: String) {
        if (!value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) || value.startsWith('.') || value.endsWith('.')) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "GitHub $field 无效")
        }
    }

    private fun validateRelative(path: String) {
        if (path.length > 512 || path.startsWith('/') || path.contains('\\') || path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "仓库子目录无效")
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        private const val MAX_GITHUB_JSON_BYTES = 4 * 1024 * 1024
        private const val MAX_REPOSITORY_ENTRIES = 20_000
        private const val MAX_REPOSITORY_FILES = 5_000
        private const val MANIFEST_FILE_NAME = "haminn-install.json"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_URL_LENGTH = 4096
        private const val GITHUB_GATEWAY = "https://haminn.airen.life/_repo/github"
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private val SAME_ORIGIN_CLIENT = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
