package life.airen.haminn.deploy

import android.content.Context
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.install.PackageManifestReader
import life.airen.haminn.model.DevWorkspace
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.LaunchChannel
import life.airen.haminn.registry.AppRegistry
import org.json.JSONArray
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One mutable development tree per ordinary happ, backed by immutable content blobs. */
class DevWorkspaceManager(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
) {
    data class Entry(val path: String, val sha256: String, val bytes: Long)
    data class Snapshot(val workspace: DevWorkspace, val entries: Map<String, Entry>)
    data class RuntimeConfig(val entryPath: String, val routing: String)
    data class BuildArtifact(val id: String, val appId: String, val revision: Long, val file: File, val sha256: String, val bytes: Long, val createdAt: Long)

    private val root = File(context.filesDir, "dev-workspaces")
    private val locks = ConcurrentHashMap<String, Any>()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val builds = ConcurrentHashMap<String, BuildArtifact>()

    fun requireDevelopableApp(appId: String): life.airen.haminn.model.WebAppInstance {
        if (!UUID_PATTERN.matches(appId) || appId in PROTECTED_IDS) {
            throw HaminnException(ErrorCodes.PROTECTED_TARGET, "HaminnUI 是受保护的管理界面，不能创建开发副本")
        }
        val app = registry.getInstance(appId)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        if (app.happId in PROTECTED_HAPP_IDS) {
            throw HaminnException(ErrorCodes.PROTECTED_TARGET, "HaminnUI 是受保护的管理界面，不能创建开发副本")
        }
        return app
    }

    fun status(appId: String): JSONObject = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        val current = registry.getDevWorkspace(appId)
        if (current == null) return@synchronized JSONObject()
            .put("appId", appId).put("state", "missing").put("launchChannel", app.launchChannel.name.lowercase())
            .put("devVersion", JSONObject.NULL)
        val workspace = if (!current.dirty && current.baseReleaseId != app.activeReleaseId && app.activeReleaseId != null) {
            rebuild(appId, current, app.activeReleaseId, keepRevision = true)
        } else current
        workspace.toJson(app.activeReleaseId)
            .put("devVersion", versionOf(appId, snapshot(appId).entries))
            .put("state", when {
                workspace.baseReleaseId != app.activeReleaseId -> "base-outdated"
                workspace.dirty -> "dirty"
                else -> "clean"
            })
            .put("launchChannel", registry.getInstance(appId)!!.launchChannel.name.lowercase())
    }

    fun enter(appId: String): JSONObject = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        val releaseId = app.activeReleaseId
            ?: throw HaminnException(ErrorCodes.CONFLICT, "此 happ 没有可复制的本地正式版本")
        val existing = registry.getDevWorkspace(appId)
        val workspace = when {
            existing == null -> createFromRelease(appId, releaseId)
            !existing.dirty && existing.baseReleaseId != releaseId -> rebuild(appId, existing, releaseId, keepRevision = true)
            else -> existing
        }
        registry.setLaunchChannel(appId, LaunchChannel.DEV)
        workspace.toJson(releaseId).put("devVersion", versionOf(appId, snapshot(appId).entries))
            .put("state", if (workspace.dirty) "dirty" else "clean").put("launchChannel", "dev")
    }

    /** Prepare or recover a workspace without changing the app launch channel. */
    fun prepare(appId: String): JSONObject = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        val releaseId = app.activeReleaseId
            ?: throw HaminnException(ErrorCodes.CONFLICT, "此 happ 没有可复制的本地正式版本")
        val existing = registry.getDevWorkspace(appId)
        val workspace = when {
            existing == null -> createFromRelease(appId, releaseId)
            !existing.dirty && existing.baseReleaseId != releaseId -> rebuild(appId, existing, releaseId, keepRevision = true)
            else -> existing
        }
        workspace.toJson(releaseId).put("devVersion", versionOf(appId, snapshot(appId).entries)).put("state", when {
            workspace.baseReleaseId != releaseId -> "base-outdated"
            workspace.dirty -> "dirty"
            else -> "clean"
        }).put("launchChannel", app.launchChannel.name.lowercase())
    }

    fun leave(appId: String): JSONObject = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        registry.setLaunchChannel(appId, LaunchChannel.STABLE)
        (registry.getDevWorkspace(appId)?.toJson(app.activeReleaseId) ?: JSONObject().put("appId", appId).put("state", "missing"))
            .put("launchChannel", "stable")
    }

    fun reset(appId: String): JSONObject = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        val releaseId = app.activeReleaseId ?: throw HaminnException(ErrorCodes.CONFLICT, "此 happ 没有本地正式版本")
        val current = registry.getDevWorkspace(appId)
        val workspace = if (current == null) createFromRelease(appId, releaseId) else rebuild(appId, current, releaseId, keepRevision = true)
        registry.setLaunchChannel(appId, LaunchChannel.DEV)
        workspace.toJson(releaseId).put("devVersion", versionOf(appId, snapshot(appId).entries))
            .put("state", "clean").put("launchChannel", "dev")
    }

    fun list(appId: String): JSONObject {
        val snapshot = writableSnapshot(appId)
        return snapshot.workspace.toJson(registry.getInstance(appId)?.activeReleaseId)
            .put("devVersion", versionOf(appId, snapshot.entries))
            .put("files", JSONArray(snapshot.entries.values.sortedBy { it.path }.map {
                JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.sha256)
            }))
    }

    fun read(appId: String, path: String): JSONObject {
        safePath(path)
        val snapshot = writableSnapshot(appId)
        val entry = snapshot.entries[path] ?: fail(ErrorCodes.INVALID_ARGUMENT, "开发文件不存在")
        if (entry.bytes > MAX_TEXT_BYTES) fail(ErrorCodes.QUOTA, "文本读取上限为 512 KiB，请使用本地镜像或二进制接口")
        val bytes = blob(appId, entry.sha256).readBytes()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val content = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { fail(ErrorCodes.INVALID_ARGUMENT, "文件不是 UTF-8 文本") }
        return snapshot.workspace.toJson(registry.getInstance(appId)?.activeReleaseId)
            .put("path", path).put("content", content).put("sha256", entry.sha256).put("bytes", entry.bytes)
    }

    fun apply(appId: String, expectedRevision: Long, changes: JSONArray): JSONObject = synchronized(lock(appId)) {
        val before = writableSnapshot(appId, expectedRevision)
        val entries = before.entries.toMutableMap()
        val changedPaths = linkedSetOf<String>()
        val seenTargets = hashSetOf<String>()
        for (index in 0 until changes.length()) {
            val change = changes.getJSONObject(index)
            if (change.optBoolean("move")) {
                val from = change.getString("from"); val to = change.getString("to")
                safePath(from); safePath(to)
                if (!seenTargets.add(to)) fail(ErrorCodes.INVALID_ARGUMENT, "同一批次包含重复目标路径")
                val moved = entries.remove(from) ?: fail(ErrorCodes.INVALID_ARGUMENT, "移动源文件不存在：$from")
                if (entries.containsKey(to)) fail(ErrorCodes.CONFLICT, "移动目标已存在：$to")
                entries[to] = moved.copy(path = to)
                changedPaths += from; changedPaths += to
                continue
            }
            val path = change.getString("path"); safePath(path)
            if (!seenTargets.add(path)) fail(ErrorCodes.INVALID_ARGUMENT, "同一批次包含重复目标路径")
            if (change.optBoolean("delete")) {
                if (change.has("content")) fail(ErrorCodes.INVALID_ARGUMENT, "删除项不能同时包含内容")
                if (entries.remove(path) == null) fail(ErrorCodes.INVALID_ARGUMENT, "不能删除不存在的文件：$path")
            } else {
                if (!change.has("content")) fail(ErrorCodes.INVALID_ARGUMENT, "文件修改缺少 content")
                val bytes = change.getString("content").toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_TEXT_BYTES) fail(ErrorCodes.QUOTA, "单个文本修改超过 512 KiB")
                entries[path] = store(appId, path, bytes)
            }
            changedPaths += path
        }
        commit(appId, before, entries, changedPaths)
    }

    fun put(appId: String, expectedRevision: Long, path: String, input: InputStream, length: Long, declaredSha256: String): JSONObject = synchronized(lock(appId)) {
        safePath(path)
        if (length !in 1..MAX_SINGLE_FILE) fail(ErrorCodes.QUOTA, "单文件大小超过 64 MiB")
        val before = writableSnapshot(appId, expectedRevision)
        val temporary = File(workspaceRoot(appId), "incoming-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (copied < length) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
                    if (read <= 0) fail(ErrorCodes.INVALID_ARGUMENT, "文件上传提前结束")
                    output.write(buffer, 0, read); digest.update(buffer, 0, read); copied += read
                }
                output.fd.sync()
            }
            val actual = digest.digest().hex()
            if (!actual.equals(declaredSha256, true)) fail(ErrorCodes.INVALID_ARGUMENT, "文件摘要不匹配")
            commitBlob(appId, temporary, actual)
            val entries = before.entries.toMutableMap()
            entries[path] = Entry(path, actual, length)
            commit(appId, before, entries, linkedSetOf(path))
        } finally { temporary.delete() }
    }

    fun replace(appId: String, expectedRevision: Long, input: InputStream): JSONObject = synchronized(lock(appId)) {
        val before = writableSnapshot(appId, expectedRevision)
        val entries = linkedMapOf<String, Entry>()
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                val path = item.name.trimEnd('/')
                safePath(path)
                if (item.isDirectory) { zip.closeEntry(); continue }
                if (entries.size >= MAX_FILES) fail(ErrorCodes.QUOTA, "开发树文件数量超过限制")
                if (entries.containsKey(path)) fail(ErrorCodes.INVALID_ARGUMENT, "ZIP 包含重复路径")
                val temporary = File(workspaceRoot(appId), "incoming-${UUID.randomUUID()}")
                val digest = MessageDigest.getInstance("SHA-256")
                var single = 0L
                try {
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            single += read; total += read
                            if (single > MAX_SINGLE_FILE || total > MAX_TREE_BYTES) fail(ErrorCodes.QUOTA, "开发树内容超过限制")
                            output.write(buffer, 0, read); digest.update(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                    val sha = digest.digest().hex(); commitBlob(appId, temporary, sha)
                    entries[path] = Entry(path, sha, single)
                } finally { temporary.delete() }
                zip.closeEntry()
            }
        }
        if (entries.isEmpty()) fail(ErrorCodes.INVALID_ARGUMENT, "开发树 ZIP 为空")
        val changed = (before.entries.keys + entries.keys).filter { before.entries[it] != entries[it] }.toCollection(linkedSetOf())
        commit(appId, before, entries, changed)
    }

    fun resolve(appId: String, path: String): File? {
        safePath(path)
        val snapshot = snapshot(appId)
        val entry = snapshot.entries[path] ?: return null
        return blob(appId, entry.sha256).takeIf { it.isFile && it.length() == entry.bytes }
    }

    fun runtimeConfig(appId: String): RuntimeConfig {
        val snapshot = snapshot(appId)
        val manifest = snapshot.entries["haminn.json"]?.let { entry ->
            runCatching { JSONObject(blob(appId, entry.sha256).readText(Charsets.UTF_8)) }.getOrNull()
        }
        val entry = manifest?.optString("entry", "index.html")?.takeIf { it.isNotBlank() } ?: "index.html"
        safePath(entry)
        if (entry !in snapshot.entries) fail(ErrorCodes.INVALID_ARGUMENT, "开发工作副本入口文件不存在：$entry")
        val routing = manifest?.optString("routing", "hash")?.takeIf { it in setOf("hash", "history") } ?: "hash"
        return RuntimeConfig(entry, routing)
    }

    fun build(appId: String, expectedRevision: Long, versionCode: Long, versionName: String): JSONObject = synchronized(lock(appId)) {
        if (versionCode <= 0 || versionName.isBlank() || versionName.length > 80) fail(ErrorCodes.INVALID_ARGUMENT, "发布版本无效")
        val app = requireDevelopableApp(appId)
        val before = writableSnapshot(appId, expectedRevision)
        val stable = app.activeReleaseId?.let(registry::getRelease) ?: fail(ErrorCodes.CONFLICT, "正式版本不存在")
        if (stable.versionCode != null && versionCode <= stable.versionCode) fail(ErrorCodes.CONFLICT, "新版本号必须大于当前正式版本")
        if (app.publisherKeyId != null) fail(ErrorCodes.UNSUPPORTED, "此 happ 使用发布者签名，需导出源码后由发布者签名再安装")
        cleanupBuilds()
        val buildId = UUID.randomUUID().toString()
        val output = File(buildRoot().apply { mkdirs() }, "$buildId.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            before.entries.values.sortedBy { it.path }.forEach { entry ->
                if (entry.path == "haminn.sig") return@forEach
                val bytes = if (entry.path == "haminn.json") {
                    val json = runCatching { JSONObject(blob(appId, entry.sha256).readText(Charsets.UTF_8)) }
                        .getOrElse { fail(ErrorCodes.INVALID_ARGUMENT, "haminn.json 不是有效 JSON") }
                    json.put("version", JSONObject().put("code", versionCode).put("name", versionName)).toString(2).toByteArray(Charsets.UTF_8)
                } else blob(appId, entry.sha256).readBytes()
                zip.putNextEntry(ZipEntry(entry.path).apply { time = 0 })
                zip.write(bytes); zip.closeEntry()
            }
        }
        val validationRoot = File(context.cacheDir, "dev-build-check-$buildId")
        try {
            materialize(before, validationRoot, versionCode to versionName)
            val metadata = PackageManifestReader.read(validationRoot)
            if (app.happId != null && metadata?.happId != app.happId) fail(ErrorCodes.CONFLICT, "开发包的 happId 与当前实例不一致")
            if (metadata?.entry?.let { File(validationRoot, it).isFile } == false) fail(ErrorCodes.INVALID_ARGUMENT, "开发包入口文件不存在")
        } finally { validationRoot.deleteRecursively() }
        val artifact = BuildArtifact(buildId, appId, before.workspace.revision, output, AgentWorkspace.hashFile(output), output.length(), System.currentTimeMillis())
        builds[buildId] = artifact
        JSONObject().put("buildId", buildId).put("appId", appId).put("devRevision", before.workspace.revision)
            .put("sha256", artifact.sha256).put("bytes", artifact.bytes).put("fileName", "${safeName(app.name)}-$versionName.zip")
    }

    /** Builds the exact development revision currently selected for runtime sharing. */
    fun buildShare(appId: String, expectedRevision: Long): BuildArtifact = synchronized(lock(appId)) {
        val app = requireDevelopableApp(appId)
        val before = writableSnapshot(appId, expectedRevision)
        cleanupBuilds()
        val buildId = UUID.randomUUID().toString()
        val output = File(buildRoot().apply { mkdirs() }, "$buildId.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            before.entries.values.sortedBy { it.path }.forEach { entry ->
                if (entry.path == "haminn.sig" && before.workspace.dirty) return@forEach
                zip.putNextEntry(ZipEntry(entry.path).apply { time = 0 })
                FileInputStream(blob(appId, entry.sha256)).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (output.length() > SHARE_ZIP_BYTES) {
            output.delete()
            fail(ErrorCodes.QUOTA, "分享 ZIP 超过 64 MiB")
        }
        val validationRoot = File(context.cacheDir, "dev-share-check-$buildId")
        try {
            materialize(before, validationRoot, null, includeSignature = !before.workspace.dirty)
            val metadata = PackageManifestReader.read(validationRoot)
            if (app.happId != null && metadata?.happId != app.happId) fail(ErrorCodes.CONFLICT, "开发包的 happId 与当前实例不一致")
            if (metadata?.entry?.let { File(validationRoot, it).isFile } == false) fail(ErrorCodes.INVALID_ARGUMENT, "开发包入口文件不存在")
            if (!before.workspace.dirty) PackageManifestReader.verifyPublisher(validationRoot, before.workspace.treeHash)
        } finally { validationRoot.deleteRecursively() }
        BuildArtifact(buildId, appId, before.workspace.revision, output, AgentWorkspace.hashFile(output), output.length(), System.currentTimeMillis())
            .also { builds[buildId] = it }
    }

    fun artifact(buildId: String): BuildArtifact? {
        cleanupBuilds()
        return builds[buildId]?.takeIf { it.file.isFile }
    }

    fun installed(appId: String, expectedRevision: Long, newReleaseId: String, keepDev: Boolean = false): JSONObject = synchronized(lock(appId)) {
        val before = writableSnapshot(appId, expectedRevision)
        val active = registry.getInstance(appId)?.activeReleaseId
        if (active != newReleaseId) fail(ErrorCodes.CONFLICT, "正式版本在安装后发生变化")
        val workspace = rebuild(appId, before.workspace, newReleaseId, keepRevision = true)
        registry.setLaunchChannel(appId, if (keepDev) LaunchChannel.DEV else LaunchChannel.STABLE)
        workspace.toJson(newReleaseId).put("devVersion", versionOf(appId, snapshot(appId).entries)).put("state", "clean")
            .put("launchChannel", if (keepDev) "dev" else "stable")
    }

    fun delete(appId: String) = synchronized(lock(appId)) {
        registry.deleteDevWorkspace(appId)
        snapshots.remove(appId)
        workspaceRoot(appId).deleteRecursively()
        Unit
    }

    fun recoverStorage() {
        root.mkdirs()
        val known = registry.listAllInstances().map { it.appId }.toSet()
        root.listFiles()?.filter { it.isDirectory && it.name != "builds" && it.name !in known }?.forEach { it.deleteRecursively() }
        root.walkTopDown().filter { it.isFile && it.name.startsWith("incoming-") }.forEach { it.delete() }
        cleanupBuilds()
        registry.listInstances().filter { it.launchChannel == LaunchChannel.DEV }.forEach { app ->
            if (registry.getDevWorkspace(app.appId) == null) runCatching { registry.setLaunchChannel(app.appId, LaunchChannel.STABLE) }
        }
    }

    private fun writableSnapshot(appId: String, expectedRevision: Long? = null): Snapshot {
        val app = requireDevelopableApp(appId)
        if (app.launchChannel != LaunchChannel.DEV) fail(ErrorCodes.DEV_MODE_REQUIRED, "请先将此 happ 设置为运行开发副本")
        val snapshot = snapshot(appId)
        if (expectedRevision != null && snapshot.workspace.revision != expectedRevision) {
            throw HaminnException(ErrorCodes.CONFLICT, "开发副本已变化；当前 revision=${snapshot.workspace.revision}, treeHash=${snapshot.workspace.treeHash}")
        }
        return snapshot
    }

    private fun snapshot(appId: String): Snapshot {
        val workspace = registry.getDevWorkspace(appId) ?: fail(ErrorCodes.DEV_MODE_REQUIRED, "开发工作副本不存在")
        snapshots[appId]?.takeIf { it.workspace.generation == workspace.generation }?.let { return it }
        val manifest = manifestFile(appId, workspace.generation)
        if (!manifest.isFile) fail(ErrorCodes.STORAGE, "开发工作副本清单缺失")
        val json = runCatching { JSONObject(manifest.readText(Charsets.UTF_8)) }
            .getOrElse { fail(ErrorCodes.STORAGE, "开发工作副本清单损坏") }
        val files = json.getJSONArray("files")
        val entries = linkedMapOf<String, Entry>()
        for (index in 0 until files.length()) {
            val item = files.getJSONObject(index); val path = item.getString("path"); safePath(path)
            val entry = Entry(path, item.getString("sha256"), item.getLong("bytes"))
            if (!entry.sha256.matches(SHA_PATTERN) || entry.bytes !in 0..MAX_SINGLE_FILE || entries.put(path, entry) != null) {
                fail(ErrorCodes.STORAGE, "开发工作副本清单包含无效文件")
            }
        }
        return Snapshot(workspace, entries).also { snapshots[appId] = it }
    }

    private fun versionOf(appId: String, entries: Map<String, Entry>): Any {
        val manifest = entries["haminn.json"] ?: return JSONObject.NULL
        val json = runCatching { JSONObject(blob(appId, manifest.sha256).readText(Charsets.UTF_8)) }.getOrNull()
        val version = json?.optJSONObject("version") ?: return JSONObject.NULL
        return JSONObject().put("code", version.optLong("code", 0L)).put("name", version.optString("name"))
    }

    private fun createFromRelease(appId: String, releaseId: String): DevWorkspace {
        val release = registry.getRelease(releaseId) ?: fail(ErrorCodes.STORAGE, "正式版本不存在")
        val webRoot = installer.releaseWebRoot(release)
        val entries = linkedMapOf<String, Entry>()
        var total = 0L
        webRoot.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(webRoot).invariantSeparatorsPath }.forEach { file ->
            val path = file.relativeTo(webRoot).invariantSeparatorsPath; safePath(path)
            total += file.length()
            if (entries.size >= MAX_FILES || total > MAX_TREE_BYTES) fail(ErrorCodes.QUOTA, "正式版本超过开发工作副本限制")
            entries[path] = store(appId, path, file.readBytes())
        }
        val generation = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val workspace = DevWorkspace(appId, releaseId, generation, 1, treeHash(appId, entries), false, now, now)
        writeManifest(workspace, entries)
        return registry.insertDevWorkspace(workspace).also { snapshots[appId] = Snapshot(it, entries) }
    }

    private fun rebuild(appId: String, current: DevWorkspace, releaseId: String, keepRevision: Boolean): DevWorkspace {
        val release = registry.getRelease(releaseId) ?: fail(ErrorCodes.STORAGE, "正式版本不存在")
        val webRoot = installer.releaseWebRoot(release)
        val entries = linkedMapOf<String, Entry>()
        var total = 0L
        webRoot.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(webRoot).invariantSeparatorsPath }.forEach { file ->
            val path = file.relativeTo(webRoot).invariantSeparatorsPath; safePath(path)
            total += file.length()
            if (entries.size >= MAX_FILES || total > MAX_TREE_BYTES) fail(ErrorCodes.QUOTA, "正式版本超过开发工作副本限制")
            entries[path] = store(appId, path, file.readBytes())
        }
        val generation = UUID.randomUUID().toString()
        val pending = current.copy(generation = generation, revision = if (keepRevision) current.revision + 1 else 1,
            treeHash = treeHash(appId, entries), dirty = false, baseReleaseId = releaseId, updatedAt = System.currentTimeMillis())
        writeManifest(pending, entries)
        val committed = registry.commitDevWorkspace(appId, current.revision, generation, pending.treeHash, false, releaseId)
        snapshots[appId] = Snapshot(committed, entries)
        pruneManifests(appId, generation)
        return committed
    }

    private fun commit(appId: String, before: Snapshot, entries: Map<String, Entry>, changedPaths: Set<String>): JSONObject {
        if (entries.isEmpty() || entries.size > MAX_FILES || entries.values.sumOf { it.bytes } > MAX_TREE_BYTES) {
            fail(ErrorCodes.QUOTA, "开发树为空或超过限制")
        }
        val hash = treeHash(appId, entries)
        val baseHash = registry.getRelease(before.workspace.baseReleaseId)?.treeHash
        val generation = UUID.randomUUID().toString()
        val pending = before.workspace.copy(generation = generation, revision = before.workspace.revision + 1,
            treeHash = hash, dirty = hash != baseHash, updatedAt = System.currentTimeMillis())
        writeManifest(pending, entries)
        val committed = try {
            registry.commitDevWorkspace(appId, before.workspace.revision, generation, hash, pending.dirty)
        } catch (_: Throwable) {
            manifestFile(appId, generation).delete()
            throw HaminnException(ErrorCodes.CONFLICT, "开发副本在提交时发生变化")
        }
        snapshots[appId] = Snapshot(committed, entries.toMap())
        pruneManifests(appId, generation)
        return committed.toJson(registry.getInstance(appId)?.activeReleaseId)
            .put("state", if (committed.dirty) "dirty" else "clean")
            .put("changedPaths", JSONArray(changedPaths.toList()))
            .put("commitState", "committed")
    }

    private fun store(appId: String, path: String, bytes: ByteArray): Entry {
        if (bytes.size > MAX_SINGLE_FILE) fail(ErrorCodes.QUOTA, "单文件超过 64 MiB：$path")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val target = blob(appId, sha)
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.tmp")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            commitBlob(appId, temporary, sha)
        }
        return Entry(path, sha, bytes.size.toLong())
    }

    private fun commitBlob(appId: String, temporary: File, sha: String) {
        val target = blob(appId, sha)
        target.parentFile?.mkdirs()
        if (target.isFile) { temporary.delete(); return }
        if (!temporary.renameTo(target)) {
            FileInputStream(temporary).use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
            temporary.delete()
        }
        target.setReadable(true, true); target.setWritable(false, false)
    }

    private fun writeManifest(workspace: DevWorkspace, entries: Map<String, Entry>) {
        val destination = manifestFile(workspace.appId, workspace.generation)
        destination.parentFile?.mkdirs()
        val value = JSONObject().put("schema", 1).put("appId", workspace.appId)
            .put("baseReleaseId", workspace.baseReleaseId).put("generation", workspace.generation)
            .put("revision", workspace.revision).put("treeHash", workspace.treeHash)
            .put("files", JSONArray(entries.values.sortedBy { it.path }.map {
                JSONObject().put("path", it.path).put("sha256", it.sha256).put("bytes", it.bytes)
            }))
        val temporary = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output -> output.write(value.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        if (!temporary.renameTo(destination)) { temporary.delete(); fail(ErrorCodes.STORAGE, "无法提交开发树清单") }
    }

    private fun materialize(
        snapshot: Snapshot,
        destination: File,
        version: Pair<Long, String>?,
        includeSignature: Boolean = false,
    ) {
        destination.deleteRecursively(); destination.mkdirs()
        snapshot.entries.values.forEach { entry ->
            if (entry.path == "haminn.sig" && !includeSignature) return@forEach
            val target = File(destination, entry.path); target.parentFile?.mkdirs()
            if (entry.path == "haminn.json" && version != null) {
                val json = JSONObject(blob(snapshot.workspace.appId, entry.sha256).readText(Charsets.UTF_8))
                json.put("version", JSONObject().put("code", version.first).put("name", version.second))
                target.writeText(json.toString(2), Charsets.UTF_8)
            } else blob(snapshot.workspace.appId, entry.sha256).copyTo(target)
        }
    }

    private fun treeHash(appId: String, entries: Map<String, Entry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("haminn-tree-v1\u0000".toByteArray(Charsets.UTF_8))
        entries.values.filter { it.path != "haminn.sig" }.sortedBy { it.path }.forEach { entry ->
            val path = entry.path.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array()); digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(entry.bytes).array())
            FileInputStream(blob(appId, entry.sha256)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
        }
        return digest.digest().hex()
    }

    private fun pruneManifests(appId: String, current: String) {
        val files = File(workspaceRoot(appId), "trees").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        files.drop(2).filter { it.nameWithoutExtension != current }.forEach { it.delete() }
    }

    private fun cleanupBuilds() {
        val cutoff = System.currentTimeMillis() - BUILD_TTL_MS
        builds.entries.removeIf { (_, value) ->
            val expired = value.createdAt < cutoff || !value.file.isFile
            if (expired) value.file.delete()
            expired
        }
        buildRoot().listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun safeName(value: String) = value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').take(48).ifBlank { "happ" }
    private fun lock(appId: String) = locks.computeIfAbsent(appId) { Any() }
    private fun workspaceRoot(appId: String) = File(root, appId).canonicalFile.also {
        if (!it.path.startsWith(root.canonicalPath + File.separator)) fail(ErrorCodes.PROTECTED_TARGET, "开发路径越界")
    }
    private fun blob(appId: String, sha: String) = File(workspaceRoot(appId), "blobs/${sha.take(2)}/$sha")
    private fun manifestFile(appId: String, generation: String) = File(workspaceRoot(appId), "trees/$generation.json")
    private fun buildRoot() = File(root, "builds")

    companion object {
        const val PROTECTED_STORE_ID = "__haminn_store__"
        private val PROTECTED_IDS = setOf(PROTECTED_STORE_ID, "haminnui", "haminnweb", "official-shell")
        private val PROTECTED_HAPP_IDS = setOf("life.airen.haminn", "life.airen.haminnui")
        private val UUID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private const val MAX_TEXT_BYTES = 512L * 1024
        private const val MAX_SINGLE_FILE = 64L * 1024 * 1024
        private const val MAX_TREE_BYTES = 256L * 1024 * 1024
        private const val MAX_FILES = 10_000
        private const val BUILD_TTL_MS = 10L * 60 * 1000
        private const val SHARE_ZIP_BYTES = 64L * 1024 * 1024

        fun safePath(path: String) {
            if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') || path.contains(':') ||
                path.split('/').any { it.isBlank() || it == "." || it == ".." || it == "__haminn" }) {
                fail(ErrorCodes.INVALID_ARGUMENT, "开发文件路径无效")
            }
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun fail(code: String, message: String): Nothing = throw HaminnException(code, message)
    }
}
