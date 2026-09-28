package life.airen.haminn.bridge

import android.net.Uri
import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.runtime.RuntimeSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.security.SecureRandom

@SuppressLint("RequiresFeature")
class BridgeController(
    private val webView: WebView,
    private val session: RuntimeSession,
    private val host: BridgeHost,
    private val injectedSdk: String,
) : PageBridge {
    private data class PendingDocument(
        val documentId: String,
        val challenge: String,
        val proxy: JavaScriptReplyProxy,
        var acknowledged: Boolean = false,
    )

    private var committed = false
    private var epoch = 0L
    private var pending: PendingDocument? = null
    private var activeDocumentId: String? = null
    private var activeProxy: JavaScriptReplyProxy? = null
    private var documentScope = newDocumentScope()
    private val activeRequests = HashSet<String>()
    private val random = SecureRandom()

    override fun install() {
        WebViewCompat.addWebMessageListener(webView, TRANSPORT_NAME, setOf(session.origin)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            val text = message.data ?: return@addWebMessageListener
            val size = text.toByteArray().size
            if (size > MAX_MESSAGE_BYTES) {
                // Dropping this silently would leave the page waiting for its own
                // client timeout with nothing to act on. Answering the request id
                // makes the cap observable, so the page can switch to the chunked
                // file channel instead of retrying the same oversized message.
                Log.w(TAG, "Bridge message of $size bytes exceeds the $MAX_MESSAGE_BYTES byte cap")
                replyError(replyProxy, oversizedRequestId(text), ErrorCodes.QUOTA, oversizedMessage(size))
                return@addWebMessageListener
            }
            if (exceedsNestingLimit(text)) {
                replyError(replyProxy, null, ErrorCodes.INVALID_ARGUMENT, "消息嵌套层级过深")
                return@addWebMessageListener
            }
            if (!session.alive || !isMainFrame || canonicalOrigin(sourceOrigin) != session.origin) {
                replyError(replyProxy, null, ErrorCodes.ORIGIN_DENIED, "来源不允许")
                return@addWebMessageListener
            }
            handle(text, replyProxy)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, injectedSdk, setOf(session.origin))
        }
    }

    override fun navigationStarted() {
        documentScope.cancel()
        documentScope = newDocumentScope()
        activeRequests.clear()
        committed = false
        epoch++
        pending = null
        activeDocumentId = null
        activeProxy = null
    }

    override fun navigationCommitted() {
        committed = true
        activateIfReady()
    }

    override fun recoverAfterUncommittedNavigation(): Boolean {
        committed = true
        activateIfReady()
        return activeProxy == null && pending == null && session.alive
    }

    override fun close() {
        documentScope.cancel()
        session.close()
        pending = null
        activeProxy = null
    }

    override fun emit(name: String, data: Any?) {
        val proxy = activeProxy ?: return
        if (!session.alive) return
        safePost(proxy, JSONObject()
            .put("kind", "event").put("event", name).put("documentEpoch", epoch)
            .put("data", data ?: JSONObject.NULL).toString())
    }

    private fun handle(text: String, replyProxy: JavaScriptReplyProxy) {
        val json = try { JSONObject(text) } catch (_: Throwable) {
            replyError(replyProxy, null, ErrorCodes.INVALID_ARGUMENT, "消息不是合法 JSON")
            return
        }
        if (json.optString("kind") in setOf("hello", "request") && json.optInt("v", -1) != 1) {
            replyError(replyProxy, json.optString("id").takeIf { it.isNotBlank() }, ErrorCodes.UNSUPPORTED, "不支持的协议版本")
            return
        }
        when (json.optString("kind")) {
            "hello" -> {
                val documentId = json.optString("documentId")
                if (documentId.length !in 16..128) {
                    replyError(replyProxy, null, ErrorCodes.INVALID_ARGUMENT, "文档身份无效")
                    return
                }
                val challenge = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                pending = PendingDocument(documentId, challenge, replyProxy)
                safePost(replyProxy, JSONObject()
                    .put("kind", "challenge").put("documentId", documentId).put("challenge", challenge).toString())
            }
            "ack" -> {
                val candidate = pending
                if (candidate == null || candidate.proxy !== replyProxy ||
                    json.optString("documentId") != candidate.documentId ||
                    json.optString("challenge") != candidate.challenge
                ) {
                    replyError(replyProxy, null, ErrorCodes.SESSION_EXPIRED, "文档握手已经失效")
                    return
                }
                candidate.acknowledged = true
                activateIfReady()
            }
            "request" -> handleRequest(json, replyProxy)
            else -> replyError(replyProxy, json.optString("id").takeIf { it.isNotBlank() }, ErrorCodes.INVALID_ARGUMENT, "未知消息类型")
        }
    }

    private fun activateIfReady() {
        val candidate = pending ?: return
        if (!committed || !candidate.acknowledged) return
        activeDocumentId = candidate.documentId
        activeProxy = candidate.proxy
        safePost(candidate.proxy, JSONObject()
            .put("kind", "ready").put("documentId", candidate.documentId)
            .put("sessionId", session.sessionId).put("documentEpoch", epoch).toString())
        pending = null
    }

    private fun handleRequest(json: JSONObject, replyProxy: JavaScriptReplyProxy) {
        val id = json.optString("id")
        val documentId = json.optString("documentId")
        val sessionId = json.optString("sessionId")
        val requestEpoch = json.optLong("documentEpoch", -1)
        if (id.isBlank() || id.length > 128 || replyProxy !== activeProxy || documentId != activeDocumentId ||
            sessionId != session.sessionId || requestEpoch != epoch
        ) {
            replyError(replyProxy, id.takeIf { it.isNotBlank() }, ErrorCodes.SESSION_EXPIRED, "页面会话已经失效")
            return
        }
        val method = json.optString("method")
        if (!method.matches(METHOD_NAME)) {
            replyError(replyProxy, id, ErrorCodes.INVALID_ARGUMENT, "方法名无效")
            return
        }
        val params = json.optJSONObject("params") ?: JSONObject()
        val requestKey = "$requestEpoch\u0000$id"
        if (activeRequests.size >= MAX_IN_FLIGHT) {
            replyError(replyProxy, id, ErrorCodes.QUOTA, "页面并发请求过多", true)
            return
        }
        if (!activeRequests.add(requestKey)) {
            replyError(replyProxy, id, ErrorCodes.CONFLICT, "请求 ID 重复")
            return
        }
        documentScope.launch {
            try {
                val result = host.dispatch(session, method, params)
                if (session.alive && replyProxy === activeProxy && documentId == activeDocumentId && requestEpoch == epoch) {
                    safePost(replyProxy, JSONObject()
                        .put("kind", "response").put("id", id).put("documentEpoch", epoch)
                        .put("ok", true).put("result", result ?: JSONObject.NULL).toString())
                }
            } catch (e: Throwable) {
                if (e is CancellationException) return@launch
                Log.e(TAG, "Bridge request failed: $method", e)
                val error = e as? HaminnException
                if (session.alive && replyProxy === activeProxy && documentId == activeDocumentId && requestEpoch == epoch) {
                    replyError(replyProxy, id, error?.code ?: ErrorCodes.INTERNAL, error?.message ?: "内部错误", error?.retryable ?: false)
                }
            } finally {
                activeRequests.remove(requestKey)
            }
        }
    }

    private fun newDocumentScope(): CoroutineScope = CoroutineScope(
        session.scope.coroutineContext + SupervisorJob(session.scope.coroutineContext[Job])
    )

    private fun replyError(proxy: JavaScriptReplyProxy, id: String?, code: String, message: String, retryable: Boolean = false) {
        safePost(proxy, JSONObject().put("kind", "response")
            .put("id", id ?: JSONObject.NULL).put("documentEpoch", epoch).put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message.take(160)).put("retryable", retryable))
            .toString())
    }

    private fun safePost(proxy: JavaScriptReplyProxy, message: String) {
        runCatching { proxy.postMessage(message) }
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
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return true
                '}', ']' -> depth--
            }
        }
        return false
    }

    private fun canonicalOrigin(uri: Uri): String {
        val port = if (uri.port != -1) ":${uri.port}" else ""
        return "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}$port"
    }

    companion object {
        private const val TRANSPORT_NAME = "__haminnTransportV1"
        private const val TAG = "HaminnBridge"
        private const val MAX_MESSAGE_BYTES = 256 * 1024
        private const val MAX_IN_FLIGHT = 16
        private const val MAX_JSON_DEPTH = 16
        private val METHOD_NAME = Regex("[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+")
    }
}
