package life.airen.haminn.bridge

import life.airen.haminn.runtime.RuntimeSession
import org.json.JSONObject

fun interface BridgeHost {
    suspend fun dispatch(session: RuntimeSession, method: String, params: JSONObject): Any?
}
