package life.airen.haminn.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import life.airen.haminn.HaminnApplication

class NotificationRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as HaminnApplication
        if (intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            app.notifications.repository.recalculateRecurring(System.currentTimeMillis())
        }
        app.notifications.scheduler.rebuild()
    }
}
