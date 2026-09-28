package life.airen.haminn.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import life.airen.haminn.registry.AppRegistry

class NotificationScheduler(
    private val context: Context,
    private val registry: AppRegistry,
    private val repository: NotificationRepository,
) {
    fun exactAlarmAvailable(): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAvailable(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun rebuild(): Boolean {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent()
        alarm.cancel(pending)
        if (!notificationsAvailable()) return true
        val enabledInstances = registry.listInstances().filter { it.notificationEnabled }.map { it.appId }.toSet()
        val next = repository.earliest(enabledInstances) ?: return true
        if (!exactAlarmAvailable()) return false
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(next, System.currentTimeMillis() + 250), pending)
        return true
    }

    private fun pendingIntent() = PendingIntent.getBroadcast(context, 7101,
        Intent(context, NotificationAlarmReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object { const val ACTION = "life.airen.haminn.NOTIFICATION_ALARM" }
}
