package life.airen.haminn.capability

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.registry.AppRegistry
import life.airen.haminn.runtime.RuntimeSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PermissionBroker(
    private val activity: Activity,
    private val registry: AppRegistry,
    private val systemRequest: suspend (Array<String>) -> Map<String, Boolean>,
) {
    private val mutex = Mutex()
    suspend fun require(session: RuntimeSession, capability: String, rationale: String, permissions: List<String> = emptyList(), scope: String = "") {
        if (!session.alive) throw HaminnException(ErrorCodes.SESSION_EXPIRED, "页面会话已经结束")
        val app = session.instance ?: throw HaminnException(ErrorCodes.ORIGIN_DENIED, "应用库无需页面能力授权")
        mutex.withLock {
            when (registry.getGrant(app.appId, app.trustRevision, capability, scope)) {
                "deny" -> throw HaminnException(ErrorCodes.CAPABILITY_DENIED, "此页面应用的能力已被拒绝")
                "allow" -> Unit
                null -> Unit
            }
            val missing = permissions.filter {
                val granted = ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                registry.observeSystemPermission(it, granted)
                !granted
            }
            if (missing.isNotEmpty()) {
                val result = systemRequest(missing.toTypedArray())
                missing.forEach { registry.observeSystemPermission(it, result[it] == true) }
                if (!session.alive) throw HaminnException(ErrorCodes.SESSION_EXPIRED, "系统授权期间页面会话已经结束")
                if (missing.any { result[it] != true }) {
                    throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, "Android 系统权限未授予")
                }
                registry.putGrant(app.appId, app.trustRevision, capability, "allow", scope)
            } else if (registry.getGrant(app.appId, app.trustRevision, capability, scope) == null) {
                val allowed = ask(app.name, capability, rationale)
                if (!session.alive) throw HaminnException(ErrorCodes.SESSION_EXPIRED, "授权期间页面会话已经结束")
                registry.putGrant(app.appId, app.trustRevision, capability, if (allowed) "allow" else "deny", scope)
                if (!allowed) throw HaminnException(ErrorCodes.CAPABILITY_DENIED, "用户拒绝了此能力")
            }
        }
    }

    fun clearSession(sessionId: String) = Unit

    fun status(session: RuntimeSession, capability: String, permissions: List<String>, scope: String = ""): Map<String, Any?> {
        val app = session.instance ?: return mapOf("implemented" to true, "grant" to "host", "system" to "not-required", "usable" to true)
        val grant = registry.getGrant(app.appId, app.trustRevision, capability, scope) ?: "ask"
        val missing = permissions.filter {
            val granted = ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            registry.observeSystemPermission(it, granted)
            !granted
        }
        return mapOf(
            "implemented" to true,
            "grant" to grant,
            "system" to if (missing.isEmpty()) "granted" else "missing",
            "missingPermissions" to missing,
            "usable" to (grant == "allow" && missing.isEmpty()),
        )
    }

    private suspend fun ask(appName: String, capability: String, rationale: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(activity)
                .setTitle("允许“$appName”使用此能力？")
                .setMessage("$rationale\n\n能力：$capability")
                .setNegativeButton("拒绝") { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setPositiveButton("允许") { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }

    companion object {
        val SPEECH_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO)
        val MICROPHONE_PERMISSIONS = listOf(Manifest.permission.RECORD_AUDIO)
        val COARSE_LOCATION = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        val FINE_LOCATION = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
