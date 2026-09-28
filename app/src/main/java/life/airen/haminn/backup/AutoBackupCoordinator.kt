package life.airen.haminn.backup

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import life.airen.haminn.capability.VoicePreferences
import life.airen.haminn.data.FileStore
import life.airen.haminn.data.RecordsStore
import life.airen.haminn.model.HaminnException
import life.airen.haminn.notification.NotificationRepository
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Automatic full backup: decides whether today still owes a backup, whether the
 * content actually changed, and then writes one archive into the user directory.
 * Only whole-Haminn backups exist here; no single-happ automatic backup.
 */
class AutoBackupCoordinator(
    private val context: Context,
    private val registry: AppRegistry,
    private val backup: BackupCoordinator,
    private val records: RecordsStore,
    private val files: FileStore,
    private val notifications: NotificationRepository,
) {
    val store = AutoBackupStore(context)
    val scheduler = AutoBackupScheduler(context, store)
    private val mutex = Mutex()

    @Volatile private var running = false

    sealed class Outcome {
        data class Completed(val fileName: String, val bytes: Long, val removed: Int) : Outcome()
        data class Skipped(val reason: String) : Outcome()
        data class Failed(val message: String, val needsPermission: Boolean) : Outcome()
    }

    fun status(): JSONObject {
        val config = store.snapshot()
        return JSONObject()
            .put("enabled", config.enabled)
            .put("directoryName", config.directoryName ?: JSONObject.NULL)
            .put("hasDirectory", persistedTree() != null)
            .put("keepCount", config.keepCount)
            .put("hour", config.hour)
            .put("minute", config.minute)
            .put("needsPermission", config.needsPermission)
            .put("running", running)
            .put("lastStatus", config.lastStatus ?: JSONObject.NULL)
            .put("lastMessage", config.lastMessage ?: JSONObject.NULL)
            .put("lastFileName", config.lastFileName ?: JSONObject.NULL)
            .put("lastBytes", config.lastBytes)
            .put("lastRunAt", config.lastAttemptAt)
            .put("exactAlarmAvailable", scheduler.exactAlarmAvailable())
            .put("nextRunAt", scheduler.nextRunAt())
            .put("batteryPercent", batteryPercent() ?: JSONObject.NULL)
            .put("minBatteryPercent", MIN_BATTERY_PERCENT)
    }

    /** The directory is usable only while the persisted SAF grant is still held. */
    fun persistedTree(): Uri? {
        val raw = store.snapshot().treeUri ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (granted) uri else null
    }

    /**
     * The single execution path for every automatic trigger.
     *
     * [catchUp] marks a trigger that only exists to recover a run the device
     * missed; it still needs today's scheduled time to have passed and the
     * configuration to predate it.
     */
    suspend fun runForTrigger(catchUp: Boolean): Outcome? {
        if (catchUp && !catchUpDue()) return null
        return runScheduled()
    }

    /** Force a backup now: it rewrites today's archive and is never skipped. */
    suspend fun runNow(): Outcome = execute(scheduled = false)

    /** Cheap pre-check so a background trigger never starts a service for nothing. */
    fun hasPendingWork(catchUp: Boolean): Boolean {
        if (catchUp) return catchUpDue()
        val config = store.snapshot()
        // A day may run more than once, so a finished run today is not by itself
        // a reason to refuse the next trigger — only a disabled configuration or
        // a lost directory grant is.
        return config.enabled && !config.needsPermission && persistedTree() != null
    }

    private suspend fun runScheduled(): Outcome = execute(scheduled = true)

    private fun catchUpDue(): Boolean {
        val config = store.snapshot()
        if (!config.enabled || config.needsPermission) return false
        if (persistedTree() == null) return false
        val now = System.currentTimeMillis()
        if (config.lastRunDate == LocalDate.now().toString()) return false
        val zone = ZoneId.systemDefault()
        val localDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val scheduledToday = localDate.atTime(config.hour, config.minute).atZone(zone).toInstant().toEpochMilli()
        if (now < scheduledToday) return false
        if (config.configuredAt > scheduledToday) return false
        if (now - config.lastAttemptAt < CATCH_UP_INTERVAL_MS) return false
        return true
    }

    private suspend fun execute(scheduled: Boolean): Outcome {
        if (!mutex.tryLock()) return Outcome.Skipped("已有备份任务正在执行")
        try {
            return withContext(Dispatchers.IO) { perform(scheduled) }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun perform(scheduled: Boolean): Outcome {
        val config = store.snapshot()
        if (scheduled && !config.enabled) return Outcome.Skipped("自动备份未启用")
        // Never configured and "the grant went stale" need different answers, and
        // only the second one is a permission problem worth remembering.
        val tree = persistedTree() ?: return missingDirectory(config)
        val now = LocalDate.now()
        val today = now.toString()
        val todayFile = AutoBackupPlan.fileName(AutoBackupPlan.dayKey(now))
        if (scheduled) {
            val percent = batteryPercent()
            if (percent != null && percent <= MIN_BATTERY_PERCENT) {
                return Outcome.Skipped("电量 $percent% 不高于 $MIN_BATTERY_PERCENT%，稍后会再试")
            }
        }
        // Every trigger of a day targets that day's single archive, so running
        // again neither multiplies the file nor evicts an earlier day. An
        // unchanged day is simply not rewritten; "马上执行" always writes.
        val fingerprint = fingerprint()
        if (scheduled && config.lastFileName == todayFile &&
            config.lastStatus == AutoBackupStore.STATUS_SUCCESS && fingerprint == config.lastFingerprint
        ) {
            store.recordSkip(today, fingerprint, "数据没有变化，已跳过本次备份")
            return Outcome.Skipped("数据没有变化")
        }
        store.recordAttempt(System.currentTimeMillis())
        running = true
        return try {
            val result = backup.exportAllToDirectory(tree, config.keepCount)
            val fileName = result.optString("fileName")
            val bytes = result.optLong("bytes")
            val removed = result.optInt("removed")
            val detail = buildString {
                append("已备份到「${config.directoryName ?: "所选目录"}」")
                if (removed > 0) append("，清理 $removed 个旧备份文件")
            }
            store.recordSuccess(today, fileName, bytes, fingerprint, detail)
            Outcome.Completed(fileName, bytes, removed)
        } catch (error: Throwable) {
            val message = (error as? HaminnException)?.message ?: error.message ?: "自动备份失败"
            val invalid = message.contains("授权") || message.contains("无法在所选目录")
            if (invalid) store.recordInvalidPermission(message) else store.recordFailure(message, false)
            Outcome.Failed(message, invalid)
        } finally {
            running = false
        }
    }

    private fun missingDirectory(config: AutoBackupStore.Config): Outcome {
        if (config.treeUri == null) return Outcome.Failed("请先选择备份目录", false)
        val message = "备份目录授权已失效，请重新选择目录"
        store.recordInvalidPermission(message)
        return Outcome.Failed(message, true)
    }

    private fun batteryPercent(): Int? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale
    }

    /**
     * Digest of everything a full backup actually stores. Presentation-only
     * state (favourite, notification switch, trust revision, timestamps) is
     * deliberately excluded so an unchanged archive is never rewritten.
     */
    fun fingerprint(): String {
        val payload = JSONObject()
            .put("theme", registry.setting(AppRegistry.SETTING_THEME) ?: "system")
            .put("voice", JSONObject()
                .put("tts", VoicePreferences(context).ttsJson())
                .put("speech", VoicePreferences(context).speechJson()))
        val instances = JSONArray()
        registry.listInstances().sortedBy { it.appId }.forEach { app ->
            instances.put(JSONObject()
                .put("appId", app.appId)
                .put("name", app.name)
                .put("source", app.source.name)
                .put("runtimeMode", app.runtimeMode.name)
                .put("liveUrl", app.liveUrl ?: JSONObject.NULL)
                .put("happId", app.happId ?: JSONObject.NULL)
                .put("updateUrl", app.updateUrl ?: JSONObject.NULL)
                .put("downloadUrl", app.downloadUrl ?: JSONObject.NULL)
                .put("customIconUrl", app.customIconUrl ?: JSONObject.NULL)
                .put("allowCrossOriginNetwork", app.allowCrossOriginNetwork)
                .put("sourceAdapter", app.sourceAdapter)
                .put("sourceSpec", runCatching { JSONObject(app.sourceSpec) }.getOrElse { JSONObject() })
                .put("activeReleaseId", app.activeReleaseId ?: JSONObject.NULL)
                .put("activeDataGeneration", app.activeDataGeneration)
                .put("codeHash", app.activeReleaseId?.let { registry.getRelease(it)?.treeHash } ?: JSONObject.NULL)
                .put("recordRevision", runCatching { records.revision(app.appId, app.activeDataGeneration) }.getOrDefault(0L))
                .put("files", JSONArray(runCatching { files.listStored(app.appId, app.activeDataGeneration) }.getOrDefault(emptyList())
                    .sortedBy { it.logicalId }
                    .map { JSONObject().put("logicalFileId", it.logicalId).put("sha256", it.sha256).put("size", it.size) })))
        }
        payload.put("instances", instances)
        val schedules = JSONArray()
        notifications.allSchedules()
            .sortedWith(compareBy({ it.instanceId }, { it.spec.id }))
            .forEach { item ->
                schedules.put(JSONObject()
                    .put("instanceId", item.instanceId)
                    .put("id", item.spec.id)
                    .put("title", item.spec.title)
                    .put("body", item.spec.body)
                    .put("data", item.spec.data)
                    .put("recurrence", item.recurrence.name)
                    .put("firstTriggerAt", item.firstTriggerAt))
            }
        payload.put("notifications", schedules)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MIN_BATTERY_PERCENT = 20
        private val CATCH_UP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
