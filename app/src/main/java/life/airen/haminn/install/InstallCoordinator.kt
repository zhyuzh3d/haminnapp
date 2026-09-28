package life.airen.haminn.install

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import life.airen.haminn.model.CodeRelease
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HappRuntimeMode
import life.airen.haminn.model.HappSource
import life.airen.haminn.model.LaunchChannel
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.WebAppInstance
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.launcher.ShortcutHost
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class InstallResult(
    val operationId: String,
    val appId: String,
    val releaseId: String,
    val treeHash: String,
)

/** What a package declares about itself, read without installing it. */
data class PackageDescription(
    val manifestFound: Boolean,
    val name: String?,
    val versionName: String?,
    val versionCode: Long?,
    val happId: String?,
    val entry: String?,
    val icon: String?,
    val bytes: Long,
)

enum class IdentityInstallChoice { UPDATE, NEW_INSTANCE, CANCEL }

class InstallCoordinator(
    private val context: Context,
    private val registry: AppRegistry,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val releaseLeases = ConcurrentHashMap<String, AtomicInteger>()
    private val shortcuts = ShortcutHost(context)
    private val images = HostImageStore(context)
    private val appsRoot get() = File(context.filesDir, "instances")

    suspend fun installZip(
        input: InputStream,
        suggestedName: String?,
        existingAppId: String? = null,
        provenance: String = "import",
        idempotencyKey: String? = null,
        expectedReleaseId: String? = null,
        declaredSha256: String? = null,
        sourceRevision: String? = null,
        fallbackName: String? = null,
        source: HappSource = HappSource.LOCAL,
        liveUrl: String? = null,
        downloadUrl: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
        commitGuard: (() -> Unit) -> Unit = { it() },
    ): InstallResult = withContext(Dispatchers.IO) {
        val provisionalAppId = existingAppId ?: UUID.randomUUID().toString()
        var appId = provisionalAppId
        locks.computeIfAbsent(appId) { Mutex() }.withLock {
            var instance = existingAppId?.let {
                registry.getInstance(it) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
            }
            val incomingDir = File(appsRoot, "$appId/incoming").apply { mkdirs() }
            val zipFile = File(incomingDir, "${UUID.randomUUID()}.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            var transferred = 0L
            try {
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        transferred += read
                        if (transferred > MAX_ZIP_BYTES) throw HaminnException(ErrorCodes.QUOTA, "ZIP 超过 64 MiB")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw if (error is HaminnException) error else HaminnException(ErrorCodes.STORAGE, error.message ?: "无法接收 ZIP", true)
            }
            val zipHash = digest.digest().hex()
            if (declaredSha256 != null && !zipHash.equals(declaredSha256, ignoreCase = true)) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 摘要不匹配")
            }

            val operationId = try {
                registry.createOperation(appId, "install-release", idempotencyKey, zipHash, expectedReleaseId)
            } catch (error: Throwable) {
                zipFile.delete()
                cleanupProvisionalRoot(appId, existingAppId)
                throw HaminnException(ErrorCodes.CONFLICT, error.message ?: "幂等键已用于其他内容")
            }
            val existing = registry.operationJson(operationId)
            if (existing?.optString("state") == "succeeded") {
                zipFile.delete()
                val release = registry.getRelease(existing.getString("resultReleaseId"))
                    ?: throw HaminnException(ErrorCodes.STORAGE, "幂等操作对应的版本不存在")
                return@withLock InstallResult(
                    operationId,
                    appId,
                    release.releaseId,
                    release.treeHash,
                )
            }

            val staging = File(appsRoot, "$appId/staging/$operationId")
            var finalDir: File? = null
            var registryCommitted = false
            var archivedInstance: WebAppInstance? = null
            var replacePackageIdentity = false
            try {
                var currentInstance = existingAppId?.let {
                    registry.getInstance(it) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
                }
                if (expectedReleaseId != null && currentInstance?.activeReleaseId != expectedReleaseId) {
                    throw HaminnException(ErrorCodes.CONFLICT, "活动版本已经变化")
                }
                registry.updateOperation(operationId, "transferring")
                if (staging.exists()) staging.deleteRecursively()
                val webRoot = File(staging, "web").apply { mkdirs() }
                registry.updateOperation(operationId, "validating")
                extractValidated(zipFile, webRoot)
                val metadata = PackageManifestReader.read(webRoot)
                val entry = metadata?.entry ?: "index.html"
                validateRelativePath(entry)
                check(File(webRoot, entry).isFile) { "入口文件不存在：$entry" }
                val treeHash = treeHash(webRoot)
                val publisher = PackageManifestReader.verifyPublisher(webRoot, treeHash)
                val packageIconBytes = metadata?.icon?.let { icon ->
                    val iconFile = File(webRoot, icon)
                    if (!iconFile.isFile || iconFile.length() > MAX_ICON_BYTES || icon.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) {
                        throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "haminn.json 的 icon 文件无效")
                    }
                    IconProcessor.centeredPngBytes { iconFile.inputStream() }
                }
                if (currentInstance != null) {
                    if (currentInstance.happId != null && metadata?.happId != currentInstance.happId) {
                        throw HaminnException(ErrorCodes.CONFLICT, "更新包的 happId 与当前实例不一致")
                    }
                    if (currentInstance.publisherKeyId != null && publisher?.keyId != currentInstance.publisherKeyId) {
                        throw HaminnException(ErrorCodes.CONFLICT, "更新包的发布者签名与当前实例不一致")
                    }
                }
                if (existingAppId == null && metadata?.happId != null) {
                    val ready = if (publisher != null) registry.findReady(metadata.happId, publisher.keyId)
                        ?: registry.findAnyReady(metadata.happId) else registry.findAnyReady(metadata.happId)
                    if (ready != null) {
                        when (identityChoice(ready, publisher?.keyId)) {
                            IdentityInstallChoice.UPDATE -> {
                                appId = ready.appId
                                instance = ready
                                currentInstance = ready
                                replacePackageIdentity = ready.publisherKeyId != publisher?.keyId
                            }
                            IdentityInstallChoice.NEW_INSTANCE -> Unit
                            IdentityInstallChoice.CANCEL -> throw HaminnException(ErrorCodes.CANCELLED, "用户取消安装")
                        }
                    } else {
                        val verifiedArchive = publisher?.let { registry.findArchived(metadata.happId, it.keyId) }
                        if (verifiedArchive != null) {
                            archivedInstance = verifiedArchive
                            appId = verifiedArchive.appId
                        } else {
                            val archived = registry.findAnyArchived(metadata.happId)
                            if (archived != null) when (identityChoice(archived, publisher?.keyId)) {
                                IdentityInstallChoice.UPDATE -> {
                                    archivedInstance = archived
                                    appId = archived.appId
                                    replacePackageIdentity = archived.publisherKeyId != publisher?.keyId
                                }
                                IdentityInstallChoice.NEW_INSTANCE -> Unit
                                IdentityInstallChoice.CANCEL -> throw HaminnException(ErrorCodes.CANCELLED, "用户取消安装")
                            }
                        }
                    }
                }
                if (instance != null) {
                    val duplicate = registry.findReleaseByTreeHash(appId, treeHash)
                    if (duplicate != null) {
                        staging.deleteRecursively()
                        if (duplicate.releaseId != currentInstance?.activeReleaseId) {
                            commitGuard { registry.activateRelease(appId, duplicate.releaseId, currentInstance?.activeReleaseId) }
                        }
                        if (replacePackageIdentity) registry.updatePackageIdentity(appId, metadata?.happId, publisher?.keyId)
                        registry.updateDefaultIcon(appId, packageIconBytes)
                        refreshShortcut(appId)
                        registry.updateOperation(operationId, "succeeded", duplicate.releaseId)
                        pruneReleases(appId, duplicate.releaseId)
                        return@withLock InstallResult(operationId, appId, duplicate.releaseId, duplicate.treeHash)
                    }
                }
                val releaseId = UUID.randomUUID().toString()
                val destination = File(appsRoot, "$appId/releases/$releaseId")
                finalDir = destination
                destination.parentFile?.mkdirs()
                check(staging.renameTo(destination)) { "无法提交代码目录" }

                val now = System.currentTimeMillis()
                val release = CodeRelease(
                    releaseId = releaseId,
                    appId = appId,
                    treeHash = treeHash,
                    provenance = provenance,
                    versionCode = metadata?.versionCode,
                    versionName = metadata?.versionName,
                    sourceRevision = sourceRevision,
                    entryPath = entry,
                    relativeRoot = "instances/$appId/releases/$releaseId/web",
                    createdAt = now,
                    routing = metadata?.routing ?: "hash",
                    happId = metadata?.happId,
                    publisherKeyId = publisher?.keyId,
                )
                registry.updateOperation(operationId, "committing")
                if (archivedInstance != null) {
                    commitGuard { registry.restoreArchivedWithRelease(appId, release) }
                    registryCommitted = true
                    if (replacePackageIdentity) registry.updatePackageIdentity(appId, metadata?.happId, publisher?.keyId)
                } else if (instance == null) {
                    val name = suggestedName?.trim()?.takeIf { it.isNotBlank() }
                        ?: metadata?.name?.takeIf { it.isNotBlank() }
                        ?: fallbackName?.takeIf { it.isNotBlank() } ?: "本地应用"
                    val initialLiveUrl = liveUrl ?: metadata?.liveUrl
                    val startUrl = initialLiveUrl ?: "https://$appId.apps.haminn.invalid/"
                    val origin = originOf(startUrl)
                    val app = WebAppInstance(
                        appId = appId, name = name.take(80), source = source, runtimeMode = HappRuntimeMode.LOCAL,
                        launchChannel = LaunchChannel.STABLE,
                        startUrl = startUrl, liveUrl = initialLiveUrl, primaryOrigin = origin,
                        webProfileName = "app-${appId.replace("-", "")}", trustRevision = 1,
                        activeReleaseId = null, activeDataGeneration = UUID.randomUUID().toString(),
                        sourceAdapter = provenance, sourceSpec = JSONObject().put("kind", provenance).toString(),
                        developerEnabled = false, favorite = false, iconUrl = null, createdAt = now, updatedAt = now,
                        happId = metadata?.happId, publisherKeyId = publisher?.keyId,
                        downloadUrl = downloadUrl, downloadVersionCode = metadata?.versionCode?.takeIf { downloadUrl != null },
                        downloadVersionName = metadata?.versionName?.takeIf { downloadUrl != null }, updateUrl = metadata?.updateUrl,
                        defaultIconUrl = packageIconBytes?.let { images.put(appId, it, "image/png") },
                    )
                    commitGuard { registry.insertLocalWithRelease(app, release) }
                    registryCommitted = true
                } else {
                    try {
                        commitGuard { registry.commitRelease(release, currentInstance?.activeReleaseId) }
                        registryCommitted = true
                        if (replacePackageIdentity) registry.updatePackageIdentity(appId, metadata?.happId, publisher?.keyId)
                    } catch (e: Throwable) {
                        destination.deleteRecursively()
                        throw HaminnException(ErrorCodes.CONFLICT, "活动版本在提交时发生变化")
                    }
                }
                val installedInstance = archivedInstance ?: currentInstance
                if (installedInstance != null) registry.updateDefaultIcon(appId, packageIconBytes)
                makeReleaseReadOnly(File(destination, "web"))
                refreshShortcut(appId)
                registry.recordPackageSource(appId, downloadUrl, metadata?.versionCode, metadata?.versionName, metadata?.updateUrl)
                registry.updateOperation(operationId, "succeeded", releaseId)
                pruneReleases(appId, releaseId)
                if (provisionalAppId != appId) File(appsRoot, provisionalAppId).deleteRecursively()
                InstallResult(operationId, appId, releaseId, treeHash)
            } catch (e: Throwable) {
                staging.deleteRecursively()
                if (!registryCommitted) finalDir?.deleteRecursively()
                val mapped = e as? HaminnException
                    ?: HaminnException(ErrorCodes.INVALID_ARGUMENT, e.message ?: "本地包校验失败")
                registry.updateOperation(operationId, "failed", errorCode = mapped.code, errorMessage = mapped.message)
                cleanupProvisionalRoot(provisionalAppId, existingAppId)
                throw mapped
            } finally {
                zipFile.delete()
            }
        }
    }

    /**
     * Reads the happ identity out of a package without installing it, so the
     * library can confirm what is about to be added. The manifest is validated
     * with the same rules as a real install, so a broken package fails here
     * instead of after the user confirms.
     */
    fun describePackage(file: File): PackageDescription {
        val entries = LinkedHashSet<String>()
        var manifestText: String? = null
        ZipFile(file).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                entries.add(entry.name)
                if (entry.name == MANIFEST_FILE && entry.size in 1..MANIFEST_BYTES) {
                    manifestText = runCatching { archive.getInputStream(entry).readBytes().toString(Charsets.UTF_8) }.getOrNull()
                }
            }
        }
        val manifest = manifestText?.let { PackageManifestReader.readText(it) }
        return PackageDescription(
            manifestFound = manifest != null,
            name = manifest?.name?.takeIf { it.isNotBlank() },
            versionName = manifest?.versionName,
            versionCode = manifest?.versionCode,
            happId = manifest?.happId?.takeIf { it.isNotBlank() },
            entry = manifest?.entry,
            icon = manifest?.icon?.takeIf { it.isNotBlank() && it in entries },
            bytes = file.length(),
        )
    }

    /** Extracts one file out of a package into the cache so it can be shown as an icon. */
    fun extractPackageFile(file: File, relative: String): File? {
        val parts = relative.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        val target = File(context.cacheDir, "preview-${UUID.randomUUID()}")
        return try {
            ZipFile(file).use { archive ->
                val entry = archive.getEntry(parts.joinToString("/")) ?: return null
                if (entry.size > MAX_ICON_BYTES) return null
                archive.getInputStream(entry).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            }
            target
        } catch (_: Throwable) {
            target.delete()
            null
        }
    }

    /** Installs a package that was already materialized, e.g. previewed before confirming. */
    suspend fun installPackageFile(
        file: File,
        suggestedName: String?,
        provenance: String,
        declaredSha256: String? = null,
        liveUrl: String? = null,
        downloadUrl: String? = null,
        fallbackName: String? = null,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE },
    ): InstallResult = FileInputStream(file).use { stream ->
        installZip(
            stream, suggestedName, provenance = provenance, declaredSha256 = declaredSha256, fallbackName = fallbackName,
            source = HappSource.ONLINE, liveUrl = liveUrl, downloadUrl = downloadUrl, identityChoice = identityChoice,
        )
    }

    suspend fun installUri(uri: Uri, suggestedName: String?,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE }): InstallResult {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取所选文件")
        stream.use { return installZip(it, suggestedName, identityChoice = identityChoice) }
    }

    suspend fun installTree(uri: Uri, suggestedName: String?,
        identityChoice: suspend (WebAppInstance, String?) -> IdentityInstallChoice = { _, _ -> IdentityInstallChoice.NEW_INSTANCE }): InstallResult = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取所选目录")
        val temporary = File(context.cacheDir, "tree-${UUID.randomUUID()}.zip")
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                var files = 0
                var expanded = 0L
                fun append(directory: DocumentFile, prefix: String, depth: Int) {
                    if (depth > MAX_TREE_DEPTH) throw HaminnException(ErrorCodes.QUOTA, "目录层级超过限制")
                    directory.listFiles().sortedBy { it.name?.lowercase() ?: "" }.forEach { child ->
                        val name = child.name?.takeIf { it.isNotBlank() }
                            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "目录包含无名称项目")
                        val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                        validateRelativePath(relative)
                        if (child.isDirectory) {
                            append(child, relative, depth + 1)
                        } else if (child.isFile) {
                            files++
                            if (files > MAX_FILES) throw HaminnException(ErrorCodes.QUOTA, "文件数量超过限制")
                            zip.putNextEntry(ZipEntry(relative))
                            val input = context.contentResolver.openInputStream(child.uri)
                                ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取：$relative")
                            input.use {
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var single = 0L
                                while (true) {
                                    val read = it.read(buffer)
                                    if (read < 0) break
                                    single += read
                                    expanded += read
                                    if (single > MAX_SINGLE_FILE || expanded > MAX_EXPANDED_BYTES) {
                                        throw HaminnException(ErrorCodes.QUOTA, "目录内容超过限制")
                                    }
                                    zip.write(buffer, 0, read)
                                }
                            }
                            zip.closeEntry()
                            if (temporary.length() > MAX_ZIP_BYTES) throw HaminnException(ErrorCodes.QUOTA, "目录快照压缩后超过 64 MiB")
                        }
                    }
                }
                append(tree, "", 0)
                if (files == 0) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "目录为空")
            }
            FileInputStream(temporary).use { installZip(it, suggestedName, provenance = "directory", identityChoice = identityChoice) }
        } finally {
            temporary.delete()
        }
    }

    fun acquireRelease(releaseId: String) {
        releaseLeases.computeIfAbsent(releaseId) { AtomicInteger() }.incrementAndGet()
    }

    fun releaseRelease(releaseId: String) {
        releaseLeases[releaseId]?.let { count ->
            if (count.decrementAndGet() <= 0) releaseLeases.remove(releaseId, count)
        }
    }

    fun releaseWebRoot(release: CodeRelease): File {
        val root = File(context.filesDir, release.relativeRoot)
        check(root.canonicalPath.startsWith(appsRoot.canonicalPath + File.separator))
        return root
    }

    fun deleteAppFiles(appId: String): Boolean {
        require(appId.matches(Regex("[0-9a-fA-F-]{36}")))
        val dir = File(appsRoot, appId)
        check(dir.canonicalPath.startsWith(appsRoot.canonicalPath + File.separator))
        val deleted = !dir.exists() || dir.deleteRecursively()
        if (deleted) images.deleteApp(appId)
        return deleted
    }

    /** Keep the newest releases, plus the active release if it is outside the window. */
    fun pruneReleasesToLimit(appId: String, limit: Int = 5) {
        require(limit > 0)
        val instance = registry.getInstance(appId) ?: throw IllegalArgumentException("App not found")
        val releases = registry.listReleases(appId)
        val keep = buildSet {
            releases.take(limit).forEach { add(it.releaseId) }
            instance.activeReleaseId?.let(::add)
            releases.filter { (releaseLeases[it.releaseId]?.get() ?: 0) > 0 }.forEach { add(it.releaseId) }
        }
        val removable = releases.filter { it.releaseId !in keep }
        val removed = removable.filter { release ->
            val dir = File(appsRoot, "$appId/releases/${release.releaseId}")
            !dir.exists() || dir.deleteRecursively()
        }.map { it.releaseId }
        registry.deleteReleaseRows(appId, removed)
    }

    fun deleteAppCode(appId: String): Boolean {
        require(appId.matches(Regex("[0-9a-fA-F-]{36}")))
        val appRoot = File(appsRoot, appId).canonicalFile
        check(appRoot.path.startsWith(appsRoot.canonicalPath + File.separator))
        listOf("incoming", "staging", "releases").forEach { File(appRoot, it).deleteRecursively() }
        return listOf("incoming", "staging", "releases").none { File(appRoot, it).exists() }
    }

    fun recoverStorage() {
        val instances = registry.listAllInstances().associateBy { it.appId }
        appsRoot.mkdirs()
        appsRoot.listFiles()?.filter { it.isDirectory }?.forEach { appRoot ->
            val instance = instances[appRoot.name]
            if (instance == null) {
                appRoot.deleteRecursively()
                return@forEach
            }
            File(appRoot, "incoming").deleteRecursively()
            File(appRoot, "staging").deleteRecursively()
            val knownReleases = registry.listReleases(instance.appId).map { it.releaseId }.toSet()
            File(appRoot, "releases").listFiles()?.filter { it.isDirectory && it.name !in knownReleases }
                ?.forEach { it.deleteRecursively() }
            File(appRoot, "data").listFiles()?.filter { it.isDirectory && it.name != instance.activeDataGeneration }
                ?.forEach { it.deleteRecursively() }
        }
        File(context.filesDir, "objects/images").listFiles()?.filter { it.isDirectory && it.name !in instances }
            ?.forEach { it.deleteRecursively() }
        registry.pendingProfileCleanup().map { it.second }.distinct().forEach(registry::finishDelete)
    }

    private fun cleanupProvisionalRoot(appId: String, existingAppId: String?) {
        if (existingAppId == null && registry.getInstance(appId) == null) {
            File(appsRoot, appId).deleteRecursively()
            images.deleteApp(appId)
        }
    }

    private fun extractValidated(zipFile: File, outputRoot: File) {
        val canonicalRoot = outputRoot.canonicalFile
        val names = HashSet<String>()
        var fileCount = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                fileCount++
                if (fileCount > MAX_FILES) throw HaminnException(ErrorCodes.QUOTA, "文件数量超过限制")
                if (entry.name.startsWith('/') || entry.name.startsWith('\\') || entry.name.contains('\\')) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含绝对路径或反斜杠路径")
                }
                val normalized = entry.name.trimEnd('/')
                if (normalized.isBlank()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含空路径")
                validateRelativePath(normalized)
                if (!names.add(normalized.lowercase())) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含重复路径")
                val target = File(canonicalRoot, normalized).canonicalFile
                if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 路径越界")
                }
                if (entry.size > 1L * 1024 * 1024 && entry.compressedSize > 0 && entry.size / entry.compressedSize > 200) {
                    throw HaminnException(ErrorCodes.QUOTA, "ZIP 包含异常压缩比文件")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    var single = 0L
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            single += read
                            total += read
                            if (single > MAX_SINGLE_FILE || total > MAX_EXPANDED_BYTES) {
                                throw HaminnException(ErrorCodes.QUOTA, "解压后内容超过限制")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        if (fileCount == 0) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "ZIP 为空")
    }

    private fun treeHash(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("haminn-tree-v1\u0000".toByteArray(Charsets.UTF_8))
        root.walkTopDown().filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath != "haminn.sig" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }.forEach { (path, file) ->
                val pathBytes = path.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
                digest.update(pathBytes)
                digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.length()).array())
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().hex()
    }

    private fun validateRelativePath(path: String) {
        if (path.isBlank() || path.length > 512 || path.startsWith("/") || path.contains('\\') || path.contains("\u0000")) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "非法路径")
        }
        if (path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "非法路径：$path")
        }
        if (path.startsWith("__haminn/")) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "路径使用了保留命名空间")
    }

    private fun makeReleaseReadOnly(root: File) {
        root.walkBottomUp().forEach { file ->
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setExecutable(file.isDirectory, true)
        }
    }

    private fun originOf(url: String): String {
        val uri = Uri.parse(url)
        val port = if (uri.port != -1 && !((uri.scheme.equals("https", true) && uri.port == 443) ||
                    (uri.scheme.equals("http", true) && uri.port == 80))) ":${uri.port}" else ""
        return "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}$port"
    }

    private fun pruneReleases(appId: String, activeReleaseId: String) {
        val releases = registry.listReleases(appId)
        val keep = buildSet {
            add(activeReleaseId)
            releases.firstOrNull { it.releaseId != activeReleaseId }?.let { add(it.releaseId) }
            releases.filter { (releaseLeases[it.releaseId]?.get() ?: 0) > 0 }.forEach { add(it.releaseId) }
        }
        val removable = releases.filter { it.releaseId !in keep }
        val removed = removable.filter { release ->
            val dir = File(appsRoot, "$appId/releases/${release.releaseId}")
            !dir.exists() || dir.deleteRecursively()
        }.map { it.releaseId }
        registry.deleteReleaseRows(appId, removed)
    }

    private fun refreshShortcut(appId: String) {
        registry.getInstance(appId)?.let { instance -> runCatching { shortcuts.update(instance) } }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MANIFEST_FILE = "haminn.json"
        private const val MANIFEST_BYTES = 64L * 1024
        private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        private const val MAX_SINGLE_FILE = 64L * 1024 * 1024
        private const val MAX_FILES = 10_000
        private const val MAX_ICON_BYTES = 2L * 1024 * 1024
        private const val MAX_TREE_DEPTH = 32
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
    }
}
