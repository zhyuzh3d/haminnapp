package life.airen.haminn.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import life.airen.haminn.HaminnApplication
import java.util.concurrent.Executors

class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        EXECUTOR.execute {
            try {
                val app = context.applicationContext as HaminnApplication
                val now = System.currentTimeMillis()
                app.notifications.repository.due(now).forEach { item ->
                    val instance = app.registry.getInstance(item.instanceId)
                    if (instance?.notificationEnabled == true) {
                        if (app.notifications.dispatcher.post(instance, item.spec)) {
                            app.notifications.repository.complete(item, now)
                        }
                    }
                }
                app.notifications.scheduler.rebuild()
            } finally { pending.finish() }
        }
    }

    companion object { private val EXECUTOR = Executors.newSingleThreadExecutor() }
}
