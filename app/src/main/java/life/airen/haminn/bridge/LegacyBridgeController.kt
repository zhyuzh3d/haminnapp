package life.airen.haminn.bridge

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.runtime.RuntimeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Compatibility transport for old OEM WebViews. JavascriptInterface is visible to every frame;
 * callers must therefore treat this mode as weakly isolated even though app/session IDs stay Native-owned.
 */
class LegacyBridgeController(
    private val webView: WebView,
    private val session: RuntimeSession,
    private val host: BridgeHost,
) : PageBridge {
    private data class PendingDocument(val documentId: String, val challenge: String, var acknowledged: Boolean = false)

    private var committed = false
    private var epoch = 0L
    private var pending: PendingDocument? = null
    private var activeDocumentId: String? = null
    private var documentScope = newDocumentScope()
    private val activeRequests = HashSet<String>()
    private val random = SecureRandom()

    private val transport = object {
        @JavascriptInterface
        fun postMessage(message: String?) {
            if (message == null) return
            val size = message.toByteArray().size
            if (size > MAX_MESSAGE_BYTES) {
                // Same reasoning as the WebMessage path: a silent drop would hide
                // the cap behind the page's own timeout.
                Log.w(TAG, "Bridge message of $size bytes exceeds the $MAX_MESSAGE_BYTES byte cap")
                webView.post {
                    if (session.alive) replyError(oversizedRequestId(message), ErrorCodes.QUOTA, oversizedMessage(size))
                }
                return
            }
            webView.post { if (session.alive) handle(message) }
        }
    }

    @SuppressLint("AddJavascriptInterface")
    override fun install() {
        webView.addJavascriptInterface(transport, NATIVE_NAME)
    }

    override fun navigationStarted() {
        documentScope.cancel()
        documentScope = newDocumentScope()
        activeRequests.clear()
        committed = false
        epoch++
        pending = null
        activeDocumentId = null
    }

    override fun navigationCommitted() {
        committed = true
        activateIfReady()
    }

    override fun recoverAfterUncommittedNavigation(): Boolean {
        committed = true
        activateIfReady()
        return activeDocumentId == null && pending == null && session.alive
    }

    override fun close() {
        documentScope.cancel()
        session.close()
        pending = null
        activeDocumentId = null
        runCatching { webView.removeJavascriptInterface(NATIVE_NAME) }
    }

    override fun emit(name: String, data: Any?) {
        if (!session.alive || activeDocumentId == null) return
        post(JSONObject().put("kind", "event").put("event", name).put("documentEpoch", epoch)
            .put("data", data ?: JSONObject.NULL).toString())
    }

    private fun handle(text: String) {
        if (exceedsNestingLimit(text)) {
            replyError(null, ErrorCodes.INVALID_ARGUMENT, "消息嵌套层级过深")
            return
        }
        val json = try { JSONObject(text) } catch (_: Throwable) {
            replyError(null, ErrorCodes.INVALID_ARGUMENT, "消息不是合法 JSON")
            return
        }
        if (json.optString("kind") in setOf("hello", "request") && json.optInt("v", -1) != 1) {
            replyError(json.optString("id").takeIf { it.isNotBlank() }, ErrorCodes.UNSUPPORTED, "不支持的协议版本")
            return
        }
        when (json.optString("kind")) {
            "hello" -> {
                val documentId = json.optString("documentId")
                if (documentId.length !in 16..128) {
                    replyError(null, ErrorCodes.INVALID_ARGUMENT, "文档身份无效")
                    return
                }
                val challenge = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                pending = PendingDocument(documentId, challenge)
                post(JSONObject().put("kind", "challenge").put("documentId", documentId).put("challenge", challenge).toString())
            }
            "ack" -> {
                val candidate = pending
                if (candidate == null || json.optString("documentId") != candidate.documentId ||
                    json.optString("challenge") != candidate.challenge) {
                    replyError(null, ErrorCodes.SESSION_EXPIRED, "文档握手已经失效")
                    return
                }
                candidate.acknowledged = true
                activateIfReady()
            }
            "request" -> handleRequest(json)
            else -> replyError(json.optString("id").takeIf { it.isNotBlank() }, ErrorCodes.INVALID_ARGUMENT, "未知消息类型")
        }
    }

    private fun activateIfReady() {
        val candidate = pending ?: return
        if (!committed || !candidate.acknowledged) return
        activeDocumentId = candidate.documentId
        post(JSONObject().put("kind", "ready").put("documentId", candidate.documentId)
            .put("sessionId", session.sessionId).put("documentEpoch", epoch).toString())
        pending = null
    }

    private fun handleRequest(json: JSONObject) {
        val id = json.optString("id")
        val documentId = json.optString("documentId")
        val requestEpoch = json.optLong("documentEpoch", -1)
        if (id.isBlank() || id.length > 128 || documentId != activeDocumentId ||
            json.optString("sessionId") != session.sessionId || requestEpoch != epoch) {
            replyError(id.takeIf { it.isNotBlank() }, ErrorCodes.SESSION_EXPIRED, "页面会话已经失效")
            return
        }
        val method = json.optString("method")
        if (!method.matches(METHOD_NAME)) {
            replyError(id, ErrorCodes.INVALID_ARGUMENT, "方法名无效")
            return
        }
        val requestKey = "$requestEpoch\u0000$id"
        if (activeRequests.size >= MAX_IN_FLIGHT) {
            replyError(id, ErrorCodes.QUOTA, "页面并发请求过多", true)
            return
        }
        if (!activeRequests.add(requestKey)) {
            replyError(id, ErrorCodes.CONFLICT, "请求 ID 重复")
            return
        }
        documentScope.launch {
            try {
                val result = host.dispatch(session, method, json.optJSONObject("params") ?: JSONObject())
                if (session.alive && documentId == activeDocumentId && requestEpoch == epoch) {
                    post(JSONObject().put("kind", "response").put("id", id).put("documentEpoch", epoch)
                        .put("ok", true).put("result", result ?: JSONObject.NULL).toString())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) return@launch
                val haminn = error as? HaminnException
                if (session.alive && documentId == activeDocumentId && requestEpoch == epoch) {
                    replyError(id, haminn?.code ?: ErrorCodes.INTERNAL, haminn?.message ?: "内部错误", haminn?.retryable ?: false)
                }
            } finally { activeRequests.remove(requestKey) }
        }
    }

    private fun post(message: String) {
        webView.post {
            if (session.alive) webView.evaluateJavascript(
                "window.__haminnLegacyReceiveV1&&window.__haminnLegacyReceiveV1(${JSONObject.quote(message)})",
                null,
            )
        }
    }

    private fun replyError(id: String?, code: String, message: String, retryable: Boolean = false) {
        post(JSONObject().put("kind", "response").put("id", id ?: JSONObject.NULL)
            .put("documentEpoch", epoch).put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message.take(160)).put("retryable", retryable))
            .toString())
    }

    /**
     * A message that is too large to accept is also too large to parse freely, so
     * the request ID is read from the only position it can occupy: the envelope
     * writes `id` before any parameter, and a JSON string value can never contain
     * a bare `"id":"` because its own quotes would be escaped.
     */
    private fun oversizedRequestId(text: String): String? {
        val marker = "\"id\":\""
        val start = text.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = text.indexOf('"', from)
        if (end < 0 || end - from > 128) return null
        return text.substring(from, end).takeIf { it.isNotBlank() }
    }

    private fun oversizedMessage(size: Int) =
        "消息有 ${size / 1024} KiB，超过宿主单次请求上限 256 KiB；请改用 files.beginWrite/appendBytes/finishWrite 分块写入"

    private fun exceedsNestingLimit(value: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        value.forEach { character ->
            if (quoted) when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    private fun newDocumentScope(): CoroutineScope = CoroutineScope(
        session.scope.coroutineContext + SupervisorJob(session.scope.coroutineContext[Job])
    )

    companion object {
        const val NATIVE_NAME = "__haminnLegacyNativeV1"
        private const val TAG = "HaminnLegacyBridge"
        private const val MAX_MESSAGE_BYTES = 256 * 1024
        private const val MAX_IN_FLIGHT = 16
        private const val MAX_JSON_DEPTH = 16
        private val METHOD_NAME = Regex("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+")
    }
}
