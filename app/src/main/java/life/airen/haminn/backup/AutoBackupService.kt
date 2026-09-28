package life.airen.haminn.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import life.airen.haminn.HaminnApplication
import life.airen.haminn.MainActivity
import life.airen.haminn.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs one automatic backup outside the Activity lifetime so a background
 * trigger survives a long export. Success stays silent; only a failure that
 * the user must resolve raises a notification.
 */
class AutoBackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var working = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动备份", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示自动备份进度与失败提醒"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A background start may still be refused; report through the stored
        // status instead of crashing the service.
        val foreground = runCatching {
            startForeground(NOTIFICATION_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }.isSuccess
        if (!foreground) {
            val app = applicationContext as HaminnApplication
            app.autoBackup.store.recordFailure("系统不允许在后台执行自动备份，将在下次打开 Haminn 时重试", false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!working) {
            working = true
            val catchUp = intent?.getBooleanExtra(EXTRA_CATCH_UP, false) ?: false
            scope.launch {
                try {
                    val app = applicationContext as HaminnApplication
                    val outcome = app.autoBackup.runForTrigger(catchUp)
                    if (outcome is AutoBackupCoordinator.Outcome.Failed) notifyFailure(outcome.message)
                } finally {
                    working = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun progressNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("正在自动备份")
        .setContentText("正在导出 Haminn 设置与全部 happ")
        .setContentIntent(openApp())
        .setOngoing(true)
        .build()

    private fun notifyFailure(message: String) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("自动备份失败")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(FAILURE_NOTIFICATION_ID, notification) }
    }

    private fun openApp() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "haminn-auto-backup"
        private const val NOTIFICATION_ID = 1903
        private const val FAILURE_NOTIFICATION_ID = 1904
        private const val EXTRA_CATCH_UP = "catchUp"

        /**
         * Starts the background export, falling back to the process scope when
         * the platform refuses a background foreground-service start.
         */
        fun start(context: Context, catchUp: Boolean) {
            val app = context.applicationContext as HaminnApplication
            if (!app.autoBackup.hasPendingWork(catchUp)) return
            val intent = Intent(context, AutoBackupService::class.java).putExtra(EXTRA_CATCH_UP, catchUp)
            val started = runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
            if (!started) app.applicationScope.launch { app.autoBackup.runForTrigger(catchUp) }
        }
    }
}
