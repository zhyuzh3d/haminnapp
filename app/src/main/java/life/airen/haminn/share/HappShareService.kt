package life.airen.haminn.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import life.airen.haminn.HaminnApplication
import life.airen.haminn.MainActivity
import life.airen.haminn.R

class HappShareService : Service() {
    private var ownedSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "happ 分享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示当前设备间 happ 分享会话"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as HaminnApplication).happShare
        if (intent?.action == ACTION_STOP) {
            manager.stop(ownedSessionId, stopForegroundService = false)
            ownedSessionId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val info = manager.foregroundInfo(sessionId)
        if (info == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ownedSessionId = sessionId
        startForeground(NOTIFICATION_ID, notification(info.first, info.second))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val sessionId = ownedSessionId
        ownedSessionId = null
        if (sessionId != null) {
            (application as HaminnApplication).happShare.stop(sessionId, stopForegroundService = false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(name: String, expiresAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HappShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val minutes = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在分享 $name")
            .setContentText("局域网分享约 $minutes 分钟后自动停止")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止分享", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "life.airen.haminn.share.START"
        const val ACTION_STOP = "life.airen.haminn.share.STOP"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val CHANNEL_ID = "haminn-happ-share"
        private const val NOTIFICATION_ID = 1902
    }
}
