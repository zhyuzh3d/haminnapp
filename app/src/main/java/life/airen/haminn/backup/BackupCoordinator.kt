package life.airen.haminn.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import life.airen.haminn.BuildConfig
import life.airen.haminn.capability.VoicePreferences
import life.airen.haminn.data.FileStore
import life.airen.haminn.data.HostImageStore
import life.airen.haminn.data.RecordsStore
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HappRuntimeMode
import life.airen.haminn.model.HappSource
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.WebAppInstance
import life.airen.haminn.registry.AppRegistry
import life.airen.haminn.notification.NotificationRepository
import life.airen.haminn.notification.NotificationSpec
import life.airen.haminn.notification.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupCoordinator(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val records: RecordsStore,
    private val files: FileStore,
    private val notifications: NotificationRepository,
) {
    private val images = HostImageStore(context)

    suspend fun export(appId: String, destination: Uri): JSONObject = withContext(Dispatchers.IO) {
        val app = registry.getAnyInstance(appId) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val releaseId = app.activeReleaseId
        releaseId?.let(installer::acquireRelease)
        val entries = JSONObject()
        var total = 0L
        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw HaminnException(ErrorCodes.STORAGE, "无法创建备份文件")
            output.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    fun begin(name: String): Pair<MessageDigest, CountingDigestOutput> {
                        validatePath(name)
                        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                        val digest = MessageDigest.getInstance("SHA-256")
                        return digest to CountingDigestOutput(zip, digest)
                    }
                    fun finish(name: String, state: Pair<MessageDigest, CountingDigestOutput>) {
                        zip.closeEntry()
                        total += state.second.count
                        if (total > MAX_BACKUP_BYTES) throw HaminnException(ErrorCodes.QUOTA, "备份超过 1 GiB")
                        entries.put(name, JSONObject().put("sha256", state.first.digest().hex()).put("size", state.second.count))
                    }
                    val recordsName = "records.jsonl"
                    val recordState = begin(recordsName)
                    val recordCount = records.exportJsonLines(app.appId, app.activeDataGeneration, recordState.second)
                    finish(recordsName, recordState)

                    val stored = files.listStored(app.appId, app.activeDataGeneration)
                    val metadata = JSONArray(stored.map { JSONObject().put("logicalFileId", it.logicalId)
                        .put("name", it.name).put("mime", it.mime).put("size", it.size).put("sha256", it.sha256) })
                    writeBytes(zip, "files.json", JSONObject().put("files", metadata).toString().toByteArray(), entries).also { total += it }
                    stored.forEach { item ->
                        val name = "files/${item.logicalId}"
                        val state = begin(name)
                        files.open(app.appId, app.activeDataGeneration, item.logicalId).use { it.copyBounded(state.second, MAX_BACKUP_BYTES - total) }
                        finish(name, state)
                    }

                    val customIconEntry = app.customIconUrl?.let { url ->
                        val image = images.open(url)
                            ?: throw HaminnException(ErrorCodes.STORAGE, "自定义图标对象不存在")
                        val name = "presentation/custom-icon.${image.file.extension}"
                        val state = begin(name)
                        FileInputStream(image.file).use { it.copyBounded(state.second, MAX_BACKUP_BYTES - total) }
                        finish(name, state)
                        name
                    }

                    var codeFiles = 0
                    if (app.activeReleaseId != null) {
                        val release = app.activeReleaseId?.let(registry::getRelease)
                            ?: throw HaminnException(ErrorCodes.STORAGE, "活动代码版本不存在")
                        val root = installer.releaseWebRoot(release).canonicalFile
                        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                            val relative = file.relativeTo(root).invariantSeparatorsPath
                            val name = "code/$relative"
                            val state = begin(name)
                            FileInputStream(file).use { it.copyBounded(state.second, MAX_BACKUP_BYTES - total) }
                            finish(name, state)
                            codeFiles++
                        }
                    }
                    val manifest = JSONObject().put("schema", 3).put("createdAt", System.currentTimeMillis())
                        .put("app", JSONObject().put("appId", app.appId).put("name", app.name).put("source", app.source.name.lowercase())
                            .put("runtimeMode", app.runtimeMode.name.lowercase())
                            .put("liveUrl", app.liveUrl ?: JSONObject.NULL)
                            .put("happId", app.happId ?: JSONObject.NULL)
                            .put("updateUrl", app.updateUrl ?: JSONObject.NULL)
                            .put("downloadUrl", app.downloadUrl ?: JSONObject.NULL)
                            .put("customIconEntry", customIconEntry ?: JSONObject.NULL)
                            .put("allowCrossOriginNetwork", app.allowCrossOriginNetwork)
                            .put("sourceAdapter", app.sourceAdapter)
                            .put("sourceSpec", JSONObject(app.sourceSpec)))
                        .put("recordCount", recordCount).put("fileCount", stored.size).put("codeFileCount", codeFiles)
                        .put("entries", entries)
                        .put("excludes", JSONArray(listOf("webProfile", "cookies", "webStorage", "permissions", "developerTokens")))
                    zip.putNextEntry(ZipEntry(MANIFEST).apply { time = 0L })
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.STORAGE, error.message ?: "备份导出失败")
        } finally { releaseId?.let(installer::releaseRelease) }
        JSONObject().put("exported", true).put("appId", appId).put("bytes", total)
    }

    suspend fun exportAll(destination: Uri): JSONObject = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "shared/backup-${UUID.randomUUID()}").apply { mkdirs() }
        val nested = mutableListOf<Pair<String, File>>()
        try {
            // A full backup covers exactly the instances the user can see and
            // use. Archived instances are retired tombstones: archiving deletes
            // their releases and code, so they have nothing restorable to store
            // and including them would only abort the restore of the whole file.
            registry.listInstances().forEachIndexed { index, app ->
                val file = File(tempDir, "happ-$index.zip")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                export(app.appId, uri)
                nested += "happs/$index/backup.zip" to file
            }
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw HaminnException(ErrorCodes.STORAGE, "无法创建备份文件")
            output.use { raw -> ZipOutputStream(raw.buffered()).use { zip ->
                val entries = JSONObject()
                nested.forEach { (name, file) ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) { val read = input.read(buffer); if (read < 0) break; zip.write(buffer, 0, read); digest.update(buffer, 0, read); size += read }
                    }
                    zip.closeEntry()
                    entries.put(name, JSONObject().put("size", size).put("sha256", digest.digest().hex()))
                }
                val settingsBytes = haminnSettingsJson().toString().toByteArray()
                val settingsDigest = MessageDigest.getInstance("SHA-256").digest(settingsBytes).hex()
                zip.putNextEntry(ZipEntry("settings.json").apply { time = 0L })
                zip.write(settingsBytes)
                zip.closeEntry()
                entries.put("settings.json", JSONObject().put("size", settingsBytes.size).put("sha256", settingsDigest))
                val notificationBytes = JSONArray(notifications.allSchedules().map { item ->
                    item.toJson().put("instanceId", item.instanceId).put("firstTriggerAt", item.firstTriggerAt).put("anchorLocal", item.anchorLocal.toString())
                }).toString().toByteArray()
                val notificationDigest = MessageDigest.getInstance("SHA-256").digest(notificationBytes).hex()
                zip.putNextEntry(ZipEntry("notifications.json").apply { time = 0L }); zip.write(notificationBytes); zip.closeEntry()
                entries.put("notifications.json", JSONObject().put("size", notificationBytes.size).put("sha256", notificationDigest))
                val manifest = JSONObject().put("schema", 1).put("backupType", "full")
                    .put("createdAt", System.currentTimeMillis()).put("haminnVersionCode", BuildConfig.VERSION_CODE)
                    .put("haminnVersionName", BuildConfig.VERSION_NAME).put("happCount", nested.size).put("entries", entries)
                    .put("excludes", JSONArray(listOf("cookies", "webStorage", "permissions", "developerTokens")))
                zip.putNextEntry(ZipEntry("haminn-backup.json").apply { time = 0L }); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            }}
            JSONObject().put("exported", true).put("backupType", "full").put("happCount", nested.size)
        } finally { tempDir.deleteRecursively() }
    }

    suspend fun exportSettings(destination: Uri): JSONObject = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: throw HaminnException(ErrorCodes.STORAGE, "无法创建备份文件")
        output.use { raw -> ZipOutputStream(raw.buffered()).use { zip ->
                val settings = haminnSettingsJson()
            val bytes = settings.toString().toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            zip.putNextEntry(ZipEntry("settings.json").apply { time = 0L }); zip.write(bytes); zip.closeEntry()
            val manifest = JSONObject().put("schema", 1).put("backupType", "settings").put("createdAt", System.currentTimeMillis())
                .put("entries", JSONObject().put("settings.json", JSONObject().put("size", bytes.size).put("sha256", digest)))
            zip.putNextEntry(ZipEntry("haminn-backup.json").apply { time = 0L }); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
        }}
        JSONObject().put("exported", true).put("backupType", "settings")
    }

    private fun haminnSettingsJson(): JSONObject = JSONObject()
        .put("theme", storedTheme())
        .put("voice", JSONObject().put("tts", VoicePreferences(context).ttsJson()).put("speech", VoicePreferences(context).speechJson()))

    private fun storedTheme(): String = registry.setting(AppRegistry.SETTING_THEME)
        ?.takeIf { it in setOf("system", "light", "dark") } ?: "system"

    suspend fun restoreAny(source: Uri): JSONObject = withContext(Dispatchers.IO) {
        val temporary = File(context.cacheDir, "shared/restore-${UUID.randomUUID()}.zip").apply { parentFile?.mkdirs() }
        try {
            context.contentResolver.openInputStream(source)?.use { input -> FileOutputStream(temporary).use { input.copyBounded(it, MAX_BACKUP_BYTES) } }
                ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取备份文件")
            ZipFile(temporary).use { zip ->
                val outer = zip.getEntry("haminn-backup.json")
                if (outer == null) return@withContext restore(source)
                val manifest = zip.getInputStream(outer).use { JSONObject(it.reader().readText()) }
                if (manifest.optInt("schema") != 1) throw HaminnException(ErrorCodes.UNSUPPORTED, "备份版本不受支持")
                when (manifest.getString("backupType")) {
                    "settings" -> {
                        validateArchive(zip, manifest.getJSONObject("entries"))
                        val settings = zip.getInputStream(zip.getEntry("settings.json")).use { JSONObject(it.reader().readText()) }
                        val voice = settings.optJSONObject("voice") ?: JSONObject()
                        VoicePreferences(context).applyJson(voice.optJSONObject("tts"), voice.optJSONObject("speech"))
                        JSONObject().put("restored", true).put("backupType", "settings").put("theme", settings.optString("theme", "system"))
                    }
                    "full" -> {
                        validateArchive(zip, manifest.getJSONObject("entries"))
                        val restored = JSONArray()
                        val idMap = mutableMapOf<String, String>()
                        val entries = manifest.getJSONObject("entries").keys().asSequence().filter { it.startsWith("happs/") }.sorted().toList()
                        entries.forEach { path ->
                            val file = File(context.cacheDir, "shared/nested-${UUID.randomUUID()}.zip")
                            try { zip.getInputStream(zip.getEntry(path)).use { input -> FileOutputStream(file).use { input.copyTo(it) } }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                val oldId = ZipFile(file).use { nested ->
                                    val nestedManifest = nested.getInputStream(nested.getEntry(MANIFEST)).use { JSONObject(it.reader().readText()) }
                                    nestedManifest.getJSONObject("app").optString("appId")
                                }
                                val result = restore(uri)
                                restored.put(result)
                                if (oldId.isNotBlank()) idMap[oldId] = result.getString("appId")
                            } finally { file.delete() }
                        }
                        zip.getEntry("notifications.json")?.let { entry ->
                            val schedules = zip.getInputStream(entry).use { JSONArray(it.reader().readText()) }
                            for (index in 0 until schedules.length()) {
                                val item = schedules.getJSONObject(index)
                                val newId = idMap[item.optString("instanceId")] ?: continue
                                val recurrence = Recurrence.valueOf(item.getString("recurrence").uppercase())
                                val spec = NotificationSpec.fromJson(JSONObject().put("id", item.getString("id"))
                                    .put("title", item.getString("title")).put("body", item.getString("body"))
                                    .put("data", item.optJSONObject("data") ?: JSONObject()))
                                notifications.upsert(newId, spec, item.getLong("firstTriggerAt"), recurrence)
                            }
                        }
                        var settingsRestored = false
                        var restoredTheme = "system"
                        zip.getEntry("settings.json")?.let { entry ->
                            val settings = zip.getInputStream(entry).use { JSONObject(it.reader().readText()) }
                            val voice = settings.optJSONObject("voice") ?: JSONObject()
                            VoicePreferences(context).applyJson(voice.optJSONObject("tts"), voice.optJSONObject("speech"))
                            restoredTheme = settings.optString("theme", "system")
                            settingsRestored = true
                        }
                        JSONObject().put("restored", true).put("backupType", "full").put("happCount", restored.length())
                            .put("apps", restored).put("settingsRestored", settingsRestored).put("theme", restoredTheme)
                    }
                    "happ" -> restore(source)
                    else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份类型无效")
                }
            }
        } finally { temporary.delete() }
    }

    suspend fun restore(source: Uri): JSONObject = withContext(Dispatchers.IO) {
        val temporary = File(context.cacheDir, "restore-${UUID.randomUUID()}.haminn-backup.zip")
        var appId: String? = null
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取备份文件")
            input.use { src -> FileOutputStream(temporary).use { src.copyBounded(it, MAX_BACKUP_BYTES) } }
            ZipFile(temporary).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST) ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不是 Haminn 备份")
                if (manifestEntry.size !in 1..MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "备份清单大小异常")
                val manifest = zip.getInputStream(manifestEntry).use { JSONObject(it.reader().readText()) }
                val schema = manifest.optInt("schema")
                if (schema != 3) throw HaminnException(ErrorCodes.UNSUPPORTED, "旧版备份含内联文件数据，请重新导出")
                validateArchive(zip, manifest.getJSONObject("entries"))
                val appJson = manifest.getJSONObject("app")
                val name = appJson.getString("name").trim().take(80).ifBlank { "恢复的应用" }
                val adapter = appJson.getString("sourceAdapter")
                val source = when (appJson.getString("source")) {
                    "local" -> HappSource.LOCAL
                    "online" -> HappSource.ONLINE
                    else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份 happ 来源无效")
                }
                val runtimeMode = when (appJson.getString("runtimeMode")) {
                    "local" -> HappRuntimeMode.LOCAL
                    "live" -> HappRuntimeMode.LIVE
                    else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份 happ 运行方式无效")
                }
                val liveUrl = appJson.optString("liveUrl").takeIf { it.isNotBlank() && it != "null" }
                val codeEntries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("code/") }.toList()
                val restored = if (codeEntries.isNotEmpty()) {
                    val codeZip = File(context.cacheDir, "restore-code-${UUID.randomUUID()}.zip")
                    try {
                        ZipOutputStream(FileOutputStream(codeZip)).use { out ->
                            codeEntries.forEach { entry ->
                                val relative = entry.name.removePrefix("code/")
                                validatePath(relative)
                                out.putNextEntry(ZipEntry(relative))
                                zip.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                        FileInputStream(codeZip).use {
                            installer.installZip(it, name, provenance = "backup", source = source, liveUrl = liveUrl)
                        }.appId
                    } finally { codeZip.delete() }
                } else if (source == HappSource.ONLINE && liveUrl != null) {
                    val url = validateOnlineUrl(liveUrl)
                    WebAppInstance.newOnlineLive(name, url).also(registry::insertInstance).appId
                } else throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份缺少可运行的 happ 代码或线上地址")
                appId = restored
                val app = registry.getInstance(restored) ?: throw HaminnException(ErrorCodes.STORAGE, "恢复实例创建失败")
                zip.getEntry("records.jsonl")?.let { entry -> zip.getInputStream(entry).use { records.importJsonLines(restored, app.activeDataGeneration, it) } }
                val fileMetadata = zip.getEntry("files.json")?.let { entry ->
                    zip.getInputStream(entry).use { JSONObject(it.reader().readText()).getJSONArray("files") }
                } ?: JSONArray()
                for (index in 0 until fileMetadata.length()) {
                    val item = fileMetadata.getJSONObject(index)
                    val id = item.getString("logicalFileId")
                    val entry = zip.getEntry("files/$id") ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份附件缺失：$id")
                    val restoredFile = zip.getInputStream(entry).use {
                        files.importWithId(restored, app.activeDataGeneration, id, it, item.getString("name"), item.getString("mime"))
                    }
                    if (!restoredFile.getString("sha256").equals(item.getString("sha256"), true)) {
                        throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份附件摘要不一致：$id")
                    }
                }
                registry.updateInstance(restored, name, app.liveUrl, null)
                if (appJson.has("customIconEntry")) {
                    appJson.optString("customIconEntry").takeIf { it.isNotBlank() && it != "null" }?.let { iconPath ->
                        val entry = zip.getEntry(iconPath)
                            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份自定义图标缺失")
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        registry.updatePresentation(restored, name, bytes, replaceIcon = true)
                    }
                }
                registry.updateUrls(restored, liveUrl, appJson.optString("updateUrl").takeIf { it.isNotBlank() && it != "null" })
                if (appJson.optBoolean("allowCrossOriginNetwork")) registry.setCrossOriginNetworkEnabled(restored, true)
                registry.updateSource(restored, adapter, appJson.getJSONObject("sourceSpec").toString())
                if (runtimeMode == HappRuntimeMode.LIVE && app.runtimeMode != HappRuntimeMode.LIVE) {
                    registry.setRuntimeMode(restored, HappRuntimeMode.LIVE)
                }
                JSONObject().put("restored", true).put("appId", restored).put("name", name)
            }
        } catch (error: Throwable) {
            appId?.let { id ->
                registry.deleteInstance(id)
                installer.deleteAppFiles(id)
                registry.finishDelete(id)
            }
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.INVALID_ARGUMENT, error.message ?: "备份恢复失败")
        } finally { temporary.delete() }
    }

    suspend fun restoreData(source: Uri, targetAppId: String): JSONObject = withContext(Dispatchers.IO) {
        val targetApp = registry.getInstance(targetAppId)
            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "页面应用不存在")
        val temporary = File(context.cacheDir, "restore-data-${UUID.randomUUID()}.haminn-backup.zip")
        val nextGeneration = UUID.randomUUID().toString()
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取备份文件")
            input.use { src -> FileOutputStream(temporary).use { src.copyBounded(it, MAX_BACKUP_BYTES) } }
            ZipFile(temporary).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST)
                    ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不是 Haminn 备份")
                if (manifestEntry.size !in 1..MAX_MANIFEST_BYTES) throw HaminnException(ErrorCodes.QUOTA, "备份清单大小异常")
                val manifest = zip.getInputStream(manifestEntry).use { JSONObject(it.reader().readText()) }
                if (manifest.optInt("schema") != 3) throw HaminnException(ErrorCodes.UNSUPPORTED, "旧版备份含内联文件数据，请重新导出")
                validateArchive(zip, manifest.getJSONObject("entries"))
                zip.getEntry("records.jsonl")?.let { entry ->
                    zip.getInputStream(entry).use { records.importJsonLines(targetAppId, nextGeneration, it) }
                }
                val fileMetadata = zip.getEntry("files.json")?.let { entry ->
                    zip.getInputStream(entry).use { JSONObject(it.reader().readText()).getJSONArray("files") }
                } ?: JSONArray()
                for (index in 0 until fileMetadata.length()) {
                    val item = fileMetadata.getJSONObject(index)
                    val id = item.getString("logicalFileId")
                    val entry = zip.getEntry("files/$id")
                        ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份附件缺失：$id")
                    val restoredFile = zip.getInputStream(entry).use {
                        files.importWithId(targetAppId, nextGeneration, id, it, item.getString("name"), item.getString("mime"))
                    }
                    if (!restoredFile.getString("sha256").equals(item.getString("sha256"), true)) {
                        throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份附件摘要不一致：$id")
                    }
                }
            }
            registry.swapDataGeneration(targetAppId, targetApp.activeDataGeneration, nextGeneration)
            records.deleteGeneration(targetAppId, targetApp.activeDataGeneration)
            JSONObject().put("restored", true).put("appId", targetAppId)
                .put("dataGeneration", nextGeneration).put("trustRevisionReset", true)
        } catch (error: Throwable) {
            records.deleteGeneration(targetAppId, nextGeneration)
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.INVALID_ARGUMENT, error.message ?: "备份数据恢复失败")
        } finally { temporary.delete() }
    }

    /**
     * Writes one full backup into a user-chosen SAF directory under that day's
     * single archive name. Running the same day again replaces that file rather
     * than adding another one, and whole days beyond [keepCount] are dropped,
     * so a stored day is never evicted by a repeat run of today. The new
     * archive is written under a temporary name and only takes over the daily
     * name once it is complete, so a failed run never costs a stored backup.
     * Only files this feature created in that directory are ever deleted.
     */
    suspend fun exportAllToDirectory(treeUri: Uri, keepCount: Int): JSONObject = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: throw HaminnException(ErrorCodes.STORAGE, "备份目录授权已失效，请重新选择目录")
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        deleteAbandonedPartials(resolver, treeUri, treeDocumentId)
        val finalName = AutoBackupPlan.fileName(AutoBackupPlan.dayKey(LocalDate.now()))
        val temporary = DocumentsContract.createDocument(resolver, parent, "application/zip", "$finalName${AutoBackupPlan.PART}")
            ?: throw HaminnException(ErrorCodes.STORAGE, "无法在所选目录创建备份文件")
        var committed = try {
            exportAll(temporary)
            verifyArchive(resolver, temporary)
            runCatching { DocumentsContract.renameDocument(resolver, temporary, finalName) }.getOrNull()
                ?: commitWithoutRename(resolver, parent, temporary, finalName)
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            throw if (error is HaminnException) error else HaminnException(ErrorCodes.STORAGE, error.message ?: "自动备份失败")
        }
        var committedName = displayName(resolver, committed) ?: finalName
        val removed = sweepDirectory(resolver, treeUri, treeDocumentId, keepCount, committedName)
        // A provider that cannot replace an existing document may have suffixed
        // the new file. Its older same-day sibling is gone now, so claim the
        // clean daily name instead of leaving "… (1)" behind.
        if (committedName != finalName) {
            runCatching { DocumentsContract.renameDocument(resolver, committed, finalName) }.getOrNull()?.let {
                committed = it
                committedName = displayName(resolver, it) ?: finalName
            }
        }
        JSONObject().put("exported", true).put("backupType", "full").put("fileName", committedName)
            .put("bytes", documentSize(resolver, committed)).put("removed", removed)
    }

    private fun documentSize(resolver: ContentResolver, uri: Uri): Long =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    /** Fallback for document providers that cannot rename: write the final name directly. */
    private suspend fun commitWithoutRename(resolver: ContentResolver, parent: Uri, temporary: Uri, finalName: String): Uri {
        runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
        val direct = DocumentsContract.createDocument(resolver, parent, "application/zip", finalName)
            ?: throw HaminnException(ErrorCodes.STORAGE, "无法在所选目录创建备份文件")
        try {
            exportAll(direct)
            verifyArchive(resolver, direct)
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, direct) }
            throw error
        }
        return direct
    }

    private fun deleteAbandonedPartials(resolver: ContentResolver, treeUri: Uri, treeDocumentId: String) {
        listDirectory(resolver, treeUri, treeDocumentId)
            .filter { AutoBackupPlan.isTemporary(it.second) }
            .forEach { runCatching { DocumentsContract.deleteDocument(resolver, it.first) } }
    }

    /**
     * Drops what a finished day no longer needs: leftover archives of the same
     * day that the committed file has replaced, and whole days older than the
     * retained window. Entries this feature did not write are left alone.
     */
    private fun sweepDirectory(
        resolver: ContentResolver,
        treeUri: Uri,
        treeDocumentId: String,
        keepCount: Int,
        committedName: String,
    ): Int {
        val entries = listDirectory(resolver, treeUri, treeDocumentId)
        val removal = AutoBackupPlan.selectForRemoval(entries.map { it.second }, keepCount, committedName).toSet()
        if (removal.isEmpty()) return 0
        var removed = 0
        entries.filter { it.second in removal }.forEach { (uri, _) ->
            if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) removed++
        }
        return removed
    }

    private fun listDirectory(resolver: ContentResolver, treeUri: Uri, treeDocumentId: String): List<Pair<Uri, String>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val result = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                result += DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name
            }
        }
        return result
    }

    /**
     * Confirms the committed archive is a structurally complete ZIP by checking
     * its central directory record. Content hashes are verified again on restore.
     */
    private fun verifyArchive(resolver: ContentResolver, uri: Uri) {
        val tail = readTail(resolver, uri, TAIL_BYTES) ?: throw HaminnException(ErrorCodes.STORAGE, "无法读取已写入的备份文件")
        if (tail.size < EOCD_MIN) throw HaminnException(ErrorCodes.STORAGE, "备份文件为空或不完整")
        var index = tail.size - EOCD_MIN
        while (index >= 0) {
            if (tail[index] == 0x50.toByte() && tail[index + 1] == 0x4b.toByte() &&
                tail[index + 2] == 0x05.toByte() && tail[index + 3] == 0x06.toByte()
            ) {
                val comment = (tail[index + 20].toInt() and 0xff) or ((tail[index + 21].toInt() and 0xff) shl 8)
                if (index + EOCD_MIN + comment == tail.size) return
                break
            }
            index--
        }
        throw HaminnException(ErrorCodes.STORAGE, "备份文件结构不完整")
    }

    private fun readTail(resolver: ContentResolver, uri: Uri, limit: Int): ByteArray? {
        val ring = ByteArray(limit)
        var cursor = 0
        var filled = 0
        resolver.openInputStream(uri)?.use { input ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    val take = minOf(limit - cursor, read - offset)
                    System.arraycopy(chunk, offset, ring, cursor, take)
                    cursor = (cursor + take) % limit
                    offset += take
                    filled = minOf(limit, filled + take)
                }
            }
        } ?: return null
        val start = if (filled == limit) cursor else 0
        return ByteArray(filled) { ring[(start + it) % limit] }
    }

    private fun validateArchive(zip: ZipFile, declared: JSONObject) {
        val seen = HashSet<String>()
        var count = 0
        var total = 0L
        val actual = HashSet<String>()
        zip.entries().asSequence().forEach { entry ->
            count++
            if (count > MAX_BACKUP_FILES) throw HaminnException(ErrorCodes.QUOTA, "备份文件数量过多")
            validatePath(entry.name)
            if (!seen.add(entry.name.lowercase())) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份包含重复路径")
            if (!entry.isDirectory && entry.name != MANIFEST) {
                val spec = declared.optJSONObject(entry.name)
                    ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份含未登记内容：${entry.name}")
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read; total += read
                        if (total > MAX_BACKUP_BYTES) throw HaminnException(ErrorCodes.QUOTA, "备份展开后超过 1 GiB")
                        digest.update(buffer, 0, read)
                    }
                }
                if (size != spec.getLong("size") || !digest.digest().hex().equals(spec.getString("sha256"), true)) {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份内容校验失败：${entry.name}")
                }
                actual += entry.name
            }
        }
        val expected = declared.keys().asSequence().toSet()
        if (actual != expected) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份清单与内容不一致")
    }

    private fun validateOnlineUrl(value: String): String {
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.host!!.endsWith(".haminn.invalid", true)) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份中的在线地址无效")
        }
        return uri.toString()
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.length > 768 || path.startsWith('/') || path.startsWith('\\') || path.contains('\\') ||
            path.contains('\u0000') || path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "备份路径无效")
        }
    }

    private fun writeBytes(zip: ZipOutputStream, name: String, bytes: ByteArray, entries: JSONObject): Long {
        validatePath(name)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        zip.putNextEntry(ZipEntry(name).apply { time = 0L }); zip.write(bytes); zip.closeEntry()
        entries.put(name, JSONObject().put("sha256", digest).put("size", bytes.size))
        return bytes.size.toLong()
    }

    private class CountingDigestOutput(private val delegate: OutputStream, private val digest: MessageDigest) : OutputStream() {
        var count = 0L; private set
        override fun write(value: Int) { delegate.write(value); digest.update(value.toByte()); count++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length); digest.update(buffer, offset, length); count += length
        }
        override fun flush() = delegate.flush()
    }

    private fun InputStream.copyBounded(output: OutputStream, remainingBudget: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > remainingBudget) throw HaminnException(ErrorCodes.QUOTA, "备份超过容量限制")
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MANIFEST = "backup.json"
        private const val MAX_MANIFEST_BYTES = 256L * 1024
        private const val MAX_BACKUP_BYTES = 1024L * 1024 * 1024
        private const val MAX_BACKUP_FILES = 50_000
        private const val EOCD_MIN = 22
        private const val TAIL_BYTES = 66_000
    }
}
