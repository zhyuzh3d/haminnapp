package life.airen.haminn.capability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import life.airen.haminn.HaminnApplication
import life.airen.haminn.MainActivity
import life.airen.haminn.R

/**
 * Keeps a screen recording alive while the user is in another application.
 *
 * Android 14 and later only hand out a [android.media.projection.MediaProjection] to an
 * app that already runs a foreground service of type `mediaProjection`, so the recording
 * controller starts this service right after the consent dialog and waits for
 * [ScreenCaptureController.onForegroundStarted] before asking for the projection. The
 * session token in the intent keeps a late callback from a previous session from being
 * mistaken for the current one.
 */
class ScreenCaptureService : Service() {
    private var token: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "屏幕录制", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在录制屏幕期间维持采集,并提供停止录制的入口"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as HaminnApplication).screenCapture
        val startToken = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (intent?.action == ACTION_STOP) {
            manager.requestStopFromNotification(startToken)
            return START_NOT_STICKY
        }
        token = startToken
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (error: Throwable) {
            // Without a running projection foreground service the platform refuses to hand
            // out the projection at all, so this is a hard failure of the whole request.
            manager.onForegroundFailed(startToken, error)
            stopSelf()
            return START_NOT_STICKY
        }
        manager.onForegroundStarted(startToken)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val owned = token
        token = null
        (application as HaminnApplication).screenCapture.onServiceStopped(owned)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TOKEN, token.orEmpty()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在共享屏幕")
            .setContentText("共享期间切到其他应用不会中断,点此返回 Haminn")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止录制", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "life.airen.haminn.screen.START"
        const val ACTION_STOP = "life.airen.haminn.screen.STOP"
        const val EXTRA_TOKEN = "token"
        private const val CHANNEL_ID = "haminn-screen-capture"
        private const val NOTIFICATION_ID = 1903
    }
}
