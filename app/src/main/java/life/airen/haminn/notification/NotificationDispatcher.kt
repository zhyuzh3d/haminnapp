package life.airen.haminn.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import life.airen.haminn.MainActivity
import life.airen.haminn.R
import life.airen.haminn.launcher.HappTaskHost
import life.airen.haminn.model.WebAppInstance

class NotificationDispatcher(private val context: Context) {
    fun available(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    fun post(instance: WebAppInstance, spec: NotificationSpec): Boolean {
        if (!instance.notificationEnabled) return false
        if (!available()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "happ-${instance.appId}"
        manager.createNotificationChannel(NotificationChannel(channelId, instance.name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "${instance.name} 发送的通知"
        })
        val intent = HappTaskHost.intent(context, instance).apply {
            putExtra(MainActivity.EXTRA_NOTIFICATION_ID, spec.id)
            putExtra(MainActivity.EXTRA_NOTIFICATION_DATA, spec.data.toString())
        }
        val requestCode = (instance.appId + "\u0000" + spec.id).hashCode()
        val pending = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(spec.title).setContentText(spec.body).setStyle(android.app.Notification.BigTextStyle().bigText(spec.body))
            .setAutoCancel(true).setContentIntent(pending).setCategory(android.app.Notification.CATEGORY_REMINDER).build()
        manager.notify(instance.appId, spec.id.hashCode(), notification)
        return true
    }

    fun cancel(instanceId: String, notificationId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(instanceId, notificationId.hashCode())
    }

    fun cancelAll(instanceId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == instanceId }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun deleteChannel(instanceId: String) {
        cancelAll(instanceId)
        context.getSystemService(NotificationManager::class.java).deleteNotificationChannel("happ-$instanceId")
    }
}
