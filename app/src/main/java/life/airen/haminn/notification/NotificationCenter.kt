package life.airen.haminn.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import life.airen.haminn.registry.AppRegistry
import java.util.concurrent.TimeUnit

class NotificationCenter(private val context: Context, registry: AppRegistry) {
    val repository = NotificationRepository(context)
    val dispatcher = NotificationDispatcher(context)
    val scheduler = NotificationScheduler(context, registry, repository)

    fun start() {
        val request = PeriodicWorkRequestBuilder<OnlineNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        scheduler.rebuild()
    }

    fun syncNow() = OnlineNotificationWorker.enqueueNow(context)
    fun close() = repository.close()

    companion object { const val WORK_NAME = "haminn-online-notifications" }
}
