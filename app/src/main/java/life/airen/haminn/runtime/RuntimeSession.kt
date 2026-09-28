package life.airen.haminn.runtime

import life.airen.haminn.model.CodeRelease
import life.airen.haminn.model.WebAppInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

enum class RuntimeRole { STORE, WEB_APP, SUPPORT }

class RuntimeSession(
    val role: RuntimeRole,
    val instance: WebAppInstance?,
    val release: CodeRelease?,
    val origin: String,
    val profileName: String,
    @Volatile var devRevision: Long? = null,
) {
    val sessionId: String = UUID.randomUUID().toString()
    val appId: String = instance?.appId ?: STORE_APP_ID
    val dataGeneration: String? = instance?.activeDataGeneration
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile var alive: Boolean = true
        private set

    fun close() {
        alive = false
        scope.cancel()
    }

    companion object {
        const val STORE_APP_ID = "__haminn_store__"
    }
}
