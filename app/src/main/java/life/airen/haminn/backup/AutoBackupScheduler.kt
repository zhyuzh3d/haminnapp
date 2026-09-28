package life.airen.haminn.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZoneId

/**
 * Daily wall-clock trigger for the automatic backup. The time of day is kept in
 * local time, so the next occurrence is recomputed after a reboot, a manual
 * clock change or a time zone change.
 */
class AutoBackupScheduler(private val context: Context, private val store: AutoBackupStore) {
    fun exactAlarmAvailable(): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Next occurrence of the configured local time of day. */
    fun nextRunAt(now: Long = System.currentTimeMillis()): Long {
        val config = store.snapshot()
        return AutoBackupPlan.nextRunAt(now, config.hour, config.minute, ZoneId.systemDefault())
    }

    /** Reprograms the single daily alarm; returns false when exact alarms are unavailable. */
    fun rebuild(): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent()
        alarm.cancel(pending)
        if (!store.snapshot().enabled) return true
        if (!exactAlarmAvailable()) return false
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(nextRunAt(), System.currentTimeMillis() + 1_000), pending)
        return true
    }

    fun cancel() = context.getSystemService(AlarmManager::class.java).cancel(pendingIntent())

    private fun pendingIntent() = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AutoBackupReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION = "life.airen.haminn.AUTO_BACKUP_ALARM"
        private const val REQUEST_CODE = 7102
    }
}
