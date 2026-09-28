package life.airen.haminn.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import life.airen.haminn.HaminnApplication
import java.util.concurrent.Executors

/**
 * Receives the daily alarm plus the events that invalidate a scheduled wall
 * clock time. The alarm runs the backup; the other events only re-arm the alarm
 * and offer one catch-up for a run the device missed.
 */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                val app = context.applicationContext as HaminnApplication
                app.autoBackup.scheduler.rebuild()
                AutoBackupService.start(context, catchUp = intent?.action != AutoBackupScheduler.ACTION)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
