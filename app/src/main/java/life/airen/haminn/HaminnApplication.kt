package life.airen.haminn

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import life.airen.haminn.backup.AutoBackupCoordinator
import life.airen.haminn.backup.BackupCoordinator
import life.airen.haminn.capability.ScreenCaptureController
import life.airen.haminn.data.FileStore
import life.airen.haminn.data.RecordsStore
import life.airen.haminn.deploy.DevelopmentServer
import life.airen.haminn.deploy.AgentDevelopmentServer
import life.airen.haminn.deploy.DevWorkspaceManager
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.install.RemoteSourceInstaller
import life.airen.haminn.registry.AppRegistry
import life.airen.haminn.runtime.OfficialShellManager
import life.airen.haminn.notification.NotificationCenter
import life.airen.haminn.share.HappShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HaminnApplication : Application(), DefaultLifecycleObserver {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var registry: AppRegistry
        private set
    lateinit var installer: InstallCoordinator
        private set
    lateinit var remoteInstaller: RemoteSourceInstaller
        private set
    lateinit var developmentServer: DevelopmentServer
        private set
    lateinit var agentServer: AgentDevelopmentServer
        private set
    lateinit var devWorkspaces: DevWorkspaceManager
        private set
    lateinit var officialShell: OfficialShellManager
        private set
    lateinit var notifications: NotificationCenter
        private set
    lateinit var happShare: HappShareManager
        private set
    val records by lazy { RecordsStore(this) }
    val files by lazy { FileStore(this) }
    /**
     * Screen recording has to outlive the page that started it, so it is owned here rather
     * than by the activity: the activity only supplies the consent dialog and the target
     * of the events. The delegate is kept so termination can ask whether it was ever used,
     * instead of building a controller just to shut it down.
     */
    private val screenCaptureDelegate = lazy { ScreenCaptureController(this, registry, files, applicationScope) }
    val screenCapture by screenCaptureDelegate
    lateinit var backup: BackupCoordinator
        private set
    lateinit var autoBackup: AutoBackupCoordinator
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        registry = AppRegistry(this)
        installer = InstallCoordinator(this, registry)
        devWorkspaces = DevWorkspaceManager(this, registry, installer)
        remoteInstaller = RemoteSourceInstaller(this, registry, installer)
        developmentServer = DevelopmentServer(this, registry, installer, applicationScope)
        agentServer = AgentDevelopmentServer(this, registry, installer, devWorkspaces, applicationScope)
        happShare = HappShareManager(this, registry, installer, devWorkspaces, applicationScope)
        officialShell = OfficialShellManager(this)
        notifications = NotificationCenter(this, registry)
        backup = BackupCoordinator(this, registry, installer, records, files, notifications.repository)
        autoBackup = AutoBackupCoordinator(this, registry, backup, records, files, notifications.repository)
        val installState = getSharedPreferences("haminn-install-state", MODE_PRIVATE)
        val installedAt = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val replaced = installState.getLong("lastUpdateTime", -1L).let { it != -1L && it != installedAt }
        if (replaced) {
            officialShell.resetToEmbedded()
            agentServer.disable("APK 已重新安装或更新")
        }
        installState.edit().putLong("lastUpdateTime", installedAt).apply()
        registry.recoverInterruptedOperations()
        installer.recoverStorage()
        devWorkspaces.recoverStorage()
        agentServer.restoreIfEnabled()
        notifications.start()
        autoBackup.scheduler.rebuild()
        // The device may have been off when the daily time passed; recover that
        // run once per day, at most one attempt per catch-up window.
        applicationScope.launch { autoBackup.runForTrigger(catchUp = true) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        developmentServer.stop("Haminn entered background")
    }

    override fun onTerminate() {
        developmentServer.stop("Application terminated")
        agentServer.stop("Application terminated")
        happShare.close()
        // Before the scope goes away: a recording that is still running is imported here.
        if (screenCaptureDelegate.isInitialized()) screenCapture.shutdown()
        applicationScope.cancel()
        notifications.close()
        registry.close()
        super<Application>.onTerminate()
    }
}
