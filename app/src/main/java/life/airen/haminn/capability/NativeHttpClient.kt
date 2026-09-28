package life.airen.haminn.capability

import android.net.Uri
import android.util.Base64
import life.airen.haminn.data.FileStore
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString
import org.json.JSONObject
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class NativeHttpClient(private val files: FileStore) {
    data class AuthorizationTarget(val origin: String, val addressClass: String) {
        val grantScope: String get() = "$addressClass:$origin"
    }

    private data class StreamHandle(
        val owner: String,
        val appId: String,
        val generation: String,
        val call: Call,
        val response: Response,
        val input: InputStream,
        var receivedBytes: Long = 0,
        @Volatile var lastAccessAt: Long = System.currentTimeMillis(),
    )

    private data class SocketEvent(val kind: String, val text: String? = null, val bytes: ByteArray? = null, val code: Int? = null)

    private class SocketHandle(
        val owner: String,
        val appId: String,
        val generation: String,
        val opened: CompletableDeferred<JSONObject> = CompletableDeferred(),
        val events: ArrayBlockingQueue<SocketEvent> = ArrayBlockingQueue(MAX_SOCKET_EVENTS),
        @Volatile var socket: WebSocket? = null,
        @Volatile var receivedBytes: Long = 0,
        @Volatile var sentBytes: Long = 0,
        @Volatile var lastAccessAt: Long = System.currentTimeMillis(),
        @Volatile var closed: Boolean = false,
    )

    private inner class LogicalFileBody(
        private val appId: String,
        private val generation: String,
        private val logicalFileId: String,
        private val length: Long,
        private val mediaType: MediaType?,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            files.open(appId, generation, logicalFileId).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    private val streams = ConcurrentHashMap<String, StreamHandle>()
    private val sockets = ConcurrentHashMap<String, SocketHandle>()

    suspend fun authorizationTarget(rawOrigin: String): AuthorizationTarget {
        val uri = Uri.parse(normalize(rawOrigin))
        if (uri.query != null || uri.fragment != null || (!uri.path.isNullOrEmpty() && uri.path != "/")) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "网络授权范围必须是完整 Origin")
        }
        val addresses = withContext(Dispatchers.IO) { resolveAndValidate(uri) }
        return AuthorizationTarget(origin(uri), if (addresses.any(::isPrivate)) "private" else "public")
    }

    suspend fun request(
        appId: String,
        generation: String,
        params: JSONObject,
        authorize: suspend (origin: String, addressClass: String) -> Unit,
    ): JSONObject {
        val prepared = prepare(appId, generation, params)
        var url = prepared.url
        var redirects = 0
        var stripCredentials = false
        while (true) {
            val target = target(url)
            authorize(target.authorization.origin, target.authorization.addressClass)
            val call = client(target, prepared.timeoutMs).newCall(requestBuilder(url, prepared, stripCredentials).build())
            val response = execute(call)
            val redirect = redirect(response, prepared.method, url, redirects)
            if (redirect != null) {
                response.close()
                redirects += 1
                stripCredentials = stripCredentials || origin(Uri.parse(url)) != origin(Uri.parse(redirect))
                url = redirect
                continue
            }
            response.use { result ->
                val headers = publicHeaders(result)
                if (prepared.method == "HEAD") return responseMeta(result, headers).put("body", JSONObject.NULL)
                val body = result.body
                val declaredLength = body.contentLength()
                if (declaredLength > MAX_RESPONSE_BODY) throw HaminnException(ErrorCodes.QUOTA, "响应超过 64 MiB")
                val contentType = body.contentType()?.toString() ?: "application/octet-stream"
                if (declaredLength < 0 || declaredLength > INLINE_RESPONSE_BYTES) {
                    val stored = withContext(Dispatchers.IO) {
                        body.byteStream().use { files.import(appId, generation, it, suggestedName(Uri.parse(url)), contentType) }
                    }
                    return responseMeta(result, headers).put("file", stored)
                }
                val bytes = withContext(Dispatchers.IO) { body.bytes() }
                if (bytes.size > INLINE_RESPONSE_BYTES) {
                    val stored = withContext(Dispatchers.IO) {
                        files.import(appId, generation, bytes.inputStream(), suggestedName(Uri.parse(url)), contentType)
                    }
                    return responseMeta(result, headers).put("file", stored)
                }
                val textual = contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")
                return responseMeta(result, headers).put(
                    if (textual) "bodyText" else "bodyBase64",
                    if (textual) bytes.toString(Charsets.UTF_8) else Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }
        }
    }

    suspend fun openStream(
        owner: String,
        appId: String,
        generation: String,
        params: JSONObject,
        authorize: suspend (origin: String, addressClass: String) -> Unit,
    ): JSONObject {
        pruneStreams()
        if (streams.values.count { it.owner == owner } >= MAX_STREAMS_PER_SESSION || streams.size >= MAX_STREAMS_TOTAL) {
            throw HaminnException(ErrorCodes.QUOTA, "流式网络连接数量已达上限")
        }
        val prepared = prepare(appId, generation, params)
        var url = prepared.url
        var redirects = 0
        var stripCredentials = false
        while (true) {
            val target = target(url)
            authorize(target.authorization.origin, target.authorization.addressClass)
            val call = client(target, prepared.timeoutMs).newCall(requestBuilder(url, prepared, stripCredentials).build())
            val response = execute(call)
            val redirect = redirect(response, prepared.method, url, redirects)
            if (redirect != null) {
                response.close()
                redirects += 1
                stripCredentials = stripCredentials || origin(Uri.parse(url)) != origin(Uri.parse(redirect))
                url = redirect
                continue
            }
            val streamId = UUID.randomUUID().toString()
            streams[streamId] = StreamHandle(owner, appId, generation, call, response, response.body.byteStream())
            return responseMeta(response, publicHeaders(response))
                .put("streamId", streamId)
                .put("contentType", response.body.contentType()?.toString() ?: "application/octet-stream")
                .put("contentLength", response.body.contentLength().takeIf { it >= 0 } ?: JSONObject.NULL)
        }
    }

    suspend fun readStream(owner: String, appId: String, generation: String, params: JSONObject): JSONObject {
        val streamId = params.getString("streamId")
        val handle = ownedStream(streamId, owner, appId, generation)
        val maxBytes = params.optInt("maxBytes", DEFAULT_STREAM_CHUNK).coerceIn(1_024, MAX_STREAM_CHUNK)
        val buffer = ByteArray(maxBytes)
        val read = try {
            withContext(Dispatchers.IO) { synchronized(handle) { handle.input.read(buffer) } }
        } catch (error: Throwable) {
            closeHandle(streamId, handle)
            throw HaminnException(ErrorCodes.NETWORK, error.message ?: "读取流式响应失败", true)
        }
        if (read < 0) {
            closeHandle(streamId, handle)
            return JSONObject().put("streamId", streamId).put("chunkBase64", "")
                .put("bytes", 0).put("receivedBytes", handle.receivedBytes).put("done", true)
        }
        handle.receivedBytes += read
        handle.lastAccessAt = System.currentTimeMillis()
        if (handle.receivedBytes > MAX_RESPONSE_BODY) {
            closeHandle(streamId, handle)
            throw HaminnException(ErrorCodes.QUOTA, "流式响应超过 64 MiB")
        }
        return JSONObject().put("streamId", streamId)
            .put("chunkBase64", Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP))
            .put("bytes", read).put("receivedBytes", handle.receivedBytes).put("done", false)
    }

    fun closeStream(owner: String, appId: String, generation: String, params: JSONObject): JSONObject {
        val streamId = params.getString("streamId")
        val handle = ownedStream(streamId, owner, appId, generation)
        closeHandle(streamId, handle)
        return JSONObject().put("streamId", streamId).put("closed", true)
    }

    suspend fun openSocket(
        owner: String,
        appId: String,
        generation: String,
        params: JSONObject,
        authorize: suspend (origin: String, addressClass: String) -> Unit,
    ): JSONObject {
        pruneStreams()
        if (sockets.values.count { it.owner == owner } >= MAX_SOCKETS_PER_SESSION || sockets.size >= MAX_SOCKETS_TOTAL) {
            throw HaminnException(ErrorCodes.QUOTA, "WebSocket 连接数量已达上限")
        }
        val socketUrl = normalizeSocket(params.getString("url"))
        val networkUrl = socketNetworkUrl(socketUrl)
        val target = target(networkUrl)
        authorize(target.authorization.origin, target.authorization.addressClass)
        val timeoutMs = params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val socketId = UUID.randomUUID().toString()
        val handle = SocketHandle(owner, appId, generation)
        sockets[socketId] = handle
        val request = Request.Builder().url(socketUrl)
        val headers = params.optJSONObject("headers") ?: JSONObject()
        val names = headers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val normalized = name.lowercase()
            if (normalized in FORBIDDEN_HEADERS) continue
            val value = headers.optString(name)
            if (value.length <= 8_192 && !value.contains('\n') && !value.contains('\r')) request.header(name, value)
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handle.socket = webSocket
                handle.lastAccessAt = System.currentTimeMillis()
                handle.opened.complete(responseMeta(response, publicHeaders(response)).put("socketId", socketId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                enqueueSocketEvent(socketId, handle, SocketEvent("text", text = text), text.toByteArray(Charsets.UTF_8).size)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                enqueueSocketEvent(socketId, handle, SocketEvent("binary", bytes = bytes.toByteArray()), bytes.size)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                enqueueSocketEvent(socketId, handle, SocketEvent("closing", text = reason, code = code), 0)
                webSocket.close(code, reason.take(120))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handle.closed = true
                enqueueSocketEvent(socketId, handle, SocketEvent("closed", text = reason, code = code), 0)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handle.closed = true
                val message = t.message?.take(500) ?: "WebSocket 连接失败"
                if (!handle.opened.isCompleted) {
                    handle.opened.completeExceptionally(HaminnException(ErrorCodes.NETWORK, message, true))
                }
                enqueueSocketEvent(socketId, handle, SocketEvent("error", text = message, code = response?.code), 0)
            }
        }
        handle.socket = socketClient(target, timeoutMs).newWebSocket(request.build(), listener)
        return try {
            withTimeout(timeoutMs) { handle.opened.await() }
        } catch (error: Throwable) {
            closeSocketHandle(socketId, handle, 1001, "Open failed")
            if (error is HaminnException) throw error
            throw HaminnException(ErrorCodes.NETWORK, error.message ?: "WebSocket 连接超时", true)
        }
    }

    suspend fun readSocket(owner: String, appId: String, generation: String, params: JSONObject): JSONObject {
        val socketId = params.getString("socketId")
        val handle = ownedSocket(socketId, owner, appId, generation)
        val timeoutMs = params.optLong("timeoutMs", 30_000L).coerceIn(0L, MAX_SOCKET_READ_TIMEOUT_MS)
        val event = withContext(Dispatchers.IO) { handle.events.poll(timeoutMs, TimeUnit.MILLISECONDS) }
            ?: return JSONObject().put("socketId", socketId).put("type", "timeout")
        handle.lastAccessAt = System.currentTimeMillis()
        val output = JSONObject().put("socketId", socketId).put("type", event.kind)
        if (event.text != null) output.put("text", event.text)
        if (event.bytes != null) output.put("dataBase64", Base64.encodeToString(event.bytes, Base64.NO_WRAP))
        if (event.code != null) output.put("code", event.code)
        if (event.kind in setOf("closed", "error")) sockets.remove(socketId, handle)
        return output
    }

    fun sendSocket(owner: String, appId: String, generation: String, params: JSONObject): JSONObject {
        val socketId = params.getString("socketId")
        val handle = ownedSocket(socketId, owner, appId, generation)
        val hasText = params.has("text")
        val hasBytes = params.has("dataBase64")
        if (hasText == hasBytes) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "WebSocket 帧必须且只能包含 text 或 dataBase64")
        val bytes = if (hasText) params.getString("text").toByteArray(Charsets.UTF_8) else runCatching {
            Base64.decode(params.getString("dataBase64"), Base64.DEFAULT)
        }.getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "dataBase64 无效") }
        if (bytes.size > MAX_SOCKET_FRAME_BYTES) throw HaminnException(ErrorCodes.QUOTA, "WebSocket 单帧超过 1 MiB")
        handle.sentBytes += bytes.size
        if (handle.sentBytes > MAX_SOCKET_TOTAL_BYTES) {
            closeSocketHandle(socketId, handle, 1009, "Send limit")
            throw HaminnException(ErrorCodes.QUOTA, "WebSocket 发送总量超过 64 MiB")
        }
        val accepted = if (hasText) handle.socket?.send(params.getString("text")) else handle.socket?.send(ByteString.of(*bytes))
        if (accepted != true) throw HaminnException(ErrorCodes.NETWORK, "WebSocket 已关闭或发送队列已满", true)
        handle.lastAccessAt = System.currentTimeMillis()
        return JSONObject().put("socketId", socketId).put("accepted", true).put("bytes", bytes.size)
    }

    fun closeSocket(owner: String, appId: String, generation: String, params: JSONObject): JSONObject {
        val socketId = params.getString("socketId")
        val handle = ownedSocket(socketId, owner, appId, generation)
        closeSocketHandle(socketId, handle, 1000, params.optString("reason", "Client closed").take(120))
        return JSONObject().put("socketId", socketId).put("closed", true)
    }

    fun closeAll(owner: String? = null) {
        streams.entries.toList().forEach { (id, handle) ->
            if (owner == null || handle.owner == owner) closeHandle(id, handle)
        }
        sockets.entries.toList().forEach { (id, handle) ->
            if (owner == null || handle.owner == owner) closeSocketHandle(id, handle, 1001, "Session closed")
        }
    }

    private data class PreparedRequest(
        val url: String,
        val method: String,
        val headers: JSONObject,
        val body: RequestBody?,
        val timeoutMs: Long,
    )

    private data class NetworkTarget(
        val uri: Uri,
        val addresses: List<InetAddress>,
        val authorization: AuthorizationTarget,
    )

    private suspend fun prepare(appId: String, generation: String, params: JSONObject): PreparedRequest {
        val url = normalize(params.getString("url"))
        val method = params.optString("method", "GET").uppercase()
        if (method !in METHODS) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不支持的 HTTP 方法")
        val body = withContext(Dispatchers.IO) { requestBody(appId, generation, params) }
        if (method in setOf("GET", "HEAD") && body != null) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$method 不能携带请求体")
        val timeoutMs = params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return PreparedRequest(url, method, params.optJSONObject("headers") ?: JSONObject(), body, timeoutMs)
    }

    private fun requestBody(appId: String, generation: String, params: JSONObject): RequestBody? {
        val choices = listOf("bodyBase64", "bodyText", "bodyLogicalFileId", "multipart").count(params::has)
        if (choices > 1) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "请求体只能使用一种输入方式")
        if (params.has("bodyBase64")) {
            val bytes = runCatching { Base64.decode(params.getString("bodyBase64"), Base64.DEFAULT) }
                .getOrElse { throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "bodyBase64 无效") }
            if (bytes.size > MAX_INLINE_REQUEST_BODY) throw HaminnException(ErrorCodes.QUOTA, "内联请求体超过 1 MiB")
            return bytes.toRequestBody(params.optString("contentType", "application/octet-stream").toMediaTypeOrNull())
        }
        if (params.has("bodyText")) {
            val bytes = params.getString("bodyText").toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_INLINE_REQUEST_BODY) throw HaminnException(ErrorCodes.QUOTA, "内联请求体超过 1 MiB")
            return bytes.toRequestBody(params.optString("contentType", "text/plain; charset=utf-8").toMediaTypeOrNull())
        }
        if (params.has("bodyLogicalFileId")) {
            val id = params.getString("bodyLogicalFileId")
            val metadata = files.metadata(appId, generation, id)
                ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "上传文件不存在")
            return LogicalFileBody(appId, generation, id, metadata.size, params.optString("contentType", metadata.mime).toMediaTypeOrNull())
        }
        if (params.has("multipart")) {
            val parts = params.getJSONArray("multipart")
            if (parts.length() !in 1..MAX_MULTIPART_PARTS) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "multipart 部分数量无效")
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            var totalFileBytes = 0L
            for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                val name = safeDisposition(part.getString("name"), "multipart 字段名")
                if (part.has("text")) {
                    val text = part.getString("text")
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_MULTIPART_TEXT) throw HaminnException(ErrorCodes.QUOTA, "multipart 文本部分过大")
                    builder.addFormDataPart(name, text)
                } else if (part.has("logicalFileId")) {
                    val id = part.getString("logicalFileId")
                    val metadata = files.metadata(appId, generation, id)
                        ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "上传文件不存在")
                    totalFileBytes += metadata.size
                    if (totalFileBytes > MAX_UPLOAD_BYTES) throw HaminnException(ErrorCodes.QUOTA, "上传文件合计超过 64 MiB")
                    val filename = safeDisposition(part.optString("filename", metadata.name), "上传文件名")
                    val contentType = part.optString("contentType", metadata.mime).toMediaTypeOrNull()
                    builder.addFormDataPart(name, filename, LogicalFileBody(appId, generation, id, metadata.size, contentType))
                } else {
                    throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "multipart 部分缺少 text 或 logicalFileId")
                }
            }
            return builder.build()
        }
        return null
    }

    private fun safeDisposition(value: String, label: String): String {
        val safe = value.trim()
        if (safe.isEmpty() || safe.length > 160 || safe.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$label 无效")
        }
        return safe
    }

    private suspend fun target(url: String): NetworkTarget {
        val uri = Uri.parse(url)
        val addresses = withContext(Dispatchers.IO) { resolveAndValidate(uri) }
        val authorization = AuthorizationTarget(origin(uri), if (addresses.any(::isPrivate)) "private" else "public")
        return NetworkTarget(uri, addresses, authorization)
    }

    private fun client(target: NetworkTarget, timeoutMs: Long): OkHttpClient {
        val fixedDns = Dns { hostname ->
            if (!hostname.equals(target.uri.host, true)) throw java.net.UnknownHostException("Unexpected host")
            target.addresses
        }
        return OkHttpClient.Builder()
            .dns(fixedDns).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(minOf(timeoutMs, 15_000), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).writeTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
    }

    private fun socketClient(target: NetworkTarget, timeoutMs: Long): OkHttpClient = client(target, timeoutMs)
        .newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    private fun requestBuilder(url: String, prepared: PreparedRequest, stripCredentials: Boolean): Request.Builder {
        val request = Request.Builder().url(url).method(prepared.method, prepared.body)
        val names = prepared.headers.keys()
        while (names.hasNext()) {
            val name = names.next()
            val normalized = name.lowercase()
            if (normalized in FORBIDDEN_HEADERS) continue
            if (stripCredentials && isCredentialHeader(normalized)) continue
            val value = prepared.headers.optString(name)
            if (value.length <= 8_192 && !value.contains('\n') && !value.contains('\r')) request.header(name, value)
        }
        return request
    }

    private suspend fun execute(call: Call): Response = try {
        withContext(Dispatchers.IO) { call.execute() }
    } catch (error: Throwable) {
        throw HaminnException(ErrorCodes.NETWORK, error.message ?: "网络请求失败", true)
    }

    private fun redirect(response: Response, method: String, currentUrl: String, redirects: Int): String? {
        val location = response.header("Location") ?: return null
        if (response.code !in 300..399 || method !in setOf("GET", "HEAD")) return null
        if (redirects >= MAX_REDIRECTS) throw HaminnException(ErrorCodes.NETWORK, "重定向次数过多")
        val next = response.request.url.resolve(location)
            ?: throw HaminnException(ErrorCodes.NETWORK, "无效的重定向地址")
        val currentUri = Uri.parse(currentUrl)
        val nextUri = Uri.parse(next.toString())
        if (currentUri.scheme.equals("https", true) && !nextUri.scheme.equals("https", true)) {
            throw HaminnException(ErrorCodes.NETWORK, "拒绝 HTTPS 降级重定向")
        }
        return normalize(next.toString())
    }

    private fun publicHeaders(response: Response): JSONObject = JSONObject().also { output ->
        response.headers.names()
            .filter { it.lowercase() !in PRIVATE_RESPONSE_HEADERS }
            .forEach { output.put(it, response.headers.values(it).joinToString(", ").take(16_384)) }
    }

    private fun responseMeta(response: Response, headers: JSONObject): JSONObject = JSONObject()
        .put("status", response.code).put("headers", headers).put("url", response.request.url.toString())

    private fun ownedStream(streamId: String, owner: String, appId: String, generation: String): StreamHandle {
        val handle = streams[streamId] ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "流式连接不存在或已经结束")
        if (handle.owner != owner || handle.appId != appId || handle.generation != generation) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "流式连接不属于当前页面会话")
        }
        return handle
    }

    private fun closeHandle(streamId: String, handle: StreamHandle) {
        if (!streams.remove(streamId, handle)) return
        runCatching { handle.input.close() }
        runCatching { handle.response.close() }
        runCatching { handle.call.cancel() }
    }

    private fun enqueueSocketEvent(socketId: String, handle: SocketHandle, event: SocketEvent, bytes: Int) {
        if (handle.closed && event.kind !in setOf("closed", "error")) return
        handle.receivedBytes += bytes
        handle.lastAccessAt = System.currentTimeMillis()
        if (bytes > MAX_SOCKET_FRAME_BYTES || handle.receivedBytes > MAX_SOCKET_TOTAL_BYTES || !handle.events.offer(event)) {
            closeSocketHandle(socketId, handle, 1009, "Receive limit")
        }
    }

    private fun ownedSocket(socketId: String, owner: String, appId: String, generation: String): SocketHandle {
        val handle = sockets[socketId] ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "WebSocket 连接不存在或已经结束")
        if (handle.owner != owner || handle.appId != appId || handle.generation != generation) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "WebSocket 连接不属于当前页面会话")
        }
        return handle
    }

    private fun closeSocketHandle(socketId: String, handle: SocketHandle, code: Int, reason: String) {
        sockets.remove(socketId, handle)
        handle.closed = true
        runCatching { handle.socket?.close(code, reason) }
        runCatching { handle.socket?.cancel() }
        if (!handle.opened.isCompleted) handle.opened.completeExceptionally(HaminnException(ErrorCodes.CANCELLED, "WebSocket 已关闭"))
    }

    private fun pruneStreams() {
        val cutoff = System.currentTimeMillis() - STREAM_IDLE_TTL_MS
        streams.entries.toList().forEach { (id, handle) -> if (handle.lastAccessAt < cutoff) closeHandle(id, handle) }
        sockets.entries.toList().forEach { (id, handle) -> if (handle.lastAccessAt < cutoff) closeSocketHandle(id, handle, 1001, "Idle timeout") }
    }

    private fun normalizeSocket(input: String): String {
        val value = input.trim()
        if (value.length > MAX_URL_LENGTH || value.any { it <= '\u001F' }) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "WebSocket 地址无效")
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Native WebSocket 仅支持无用户信息的 WS(S) 地址")
        }
        return uri.toString()
    }

    private fun socketNetworkUrl(url: String): String {
        val uri = Uri.parse(url)
        return uri.buildUpon().scheme(if (uri.scheme == "wss") "https" else "http").build().toString()
    }

    private fun normalize(input: String): String {
        val value = input.trim()
        if (value.length > MAX_URL_LENGTH) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 地址过长")
        if (value.any { it <= '\u001F' }) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 地址包含控制字符")
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Native HTTP 仅支持无用户信息的 HTTP(S) 地址")
        }
        if (uri.port == 8765 || uri.host!!.endsWith(".haminn.invalid", true)) {
            throw HaminnException(ErrorCodes.ORIGIN_DENIED, "该目标是 Haminn 保留端点")
        }
        return uri.toString()
    }

    private fun resolveAndValidate(uri: Uri): List<InetAddress> {
        val addresses = InetAddress.getAllByName(uri.host).toList()
        if (addresses.isEmpty()) throw HaminnException(ErrorCodes.NETWORK, "域名没有可用地址")
        addresses.forEach { rawAddress ->
            val address = effectiveAddress(rawAddress)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress || address.isLinkLocalAddress || isMetadata(address)) {
                throw HaminnException(ErrorCodes.ORIGIN_DENIED, "目标地址类别不允许")
            }
        }
        val classes = addresses.map(::isPrivate).distinct()
        if (classes.size != 1) throw HaminnException(ErrorCodes.ORIGIN_DENIED, "目标同时解析到公网和私网地址")
        return addresses
    }

    private fun isPrivate(rawAddress: InetAddress): Boolean {
        val address = effectiveAddress(rawAddress)
        return address.isSiteLocalAddress || when (address) {
            is Inet4Address -> address.address.let { (it[0].toInt() and 0xff) == 100 && (it[1].toInt() and 0xc0) == 64 }
            is Inet6Address -> (address.address[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    private fun isMetadata(address: InetAddress): Boolean = effectiveAddress(address).hostAddress?.substringBefore('%') in setOf(
        "169.254.169.254", "100.100.100.200", "fd00:ec2::254",
    )

    private fun effectiveAddress(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val bytes = address.address
        val mapped = bytes.size == 16 && bytes.sliceArray(0..9).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        return if (mapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
    }

    private fun origin(uri: Uri): String {
        val default = (uri.scheme == "https" && uri.port in setOf(-1, 443)) || (uri.scheme == "http" && uri.port in setOf(-1, 80))
        return "${uri.scheme!!.lowercase()}://${uri.host!!.lowercase()}${if (default) "" else ":${uri.port}"}"
    }

    private fun suggestedName(uri: Uri) = uri.lastPathSegment?.takeIf { it.isNotBlank() }?.take(120) ?: "response.bin"

    private fun isCredentialHeader(name: String): Boolean = name in CREDENTIAL_HEADERS ||
        name.contains("api-key") || name.contains("apikey") || name.endsWith("-token") || name.startsWith("x-amz-")

    companion object {
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-connection", "upgrade", "te", "trailer")
        private val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization", "x-api-key", "api-key", "xi-api-key", "x-goog-api-key")
        private val PRIVATE_RESPONSE_HEADERS = setOf("set-cookie", "www-authenticate", "proxy-authenticate")
        private const val MAX_INLINE_REQUEST_BODY = 1024 * 1024
        private const val INLINE_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 64L * 1024 * 1024
        private const val MAX_RESPONSE_BODY = 64L * 1024 * 1024
        private const val MAX_MULTIPART_PARTS = 24
        private const val MAX_MULTIPART_TEXT = 256 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_URL_LENGTH = 4096
        private const val MIN_TIMEOUT_MS = 5_000L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 180_000L
        private const val DEFAULT_STREAM_CHUNK = 32 * 1024
        private const val MAX_STREAM_CHUNK = 64 * 1024
        private const val MAX_STREAMS_PER_SESSION = 6
        private const val MAX_STREAMS_TOTAL = 12
        private const val MAX_SOCKETS_PER_SESSION = 3
        private const val MAX_SOCKETS_TOTAL = 8
        private const val MAX_SOCKET_EVENTS = 32
        private const val MAX_SOCKET_FRAME_BYTES = 1024 * 1024
        private const val MAX_SOCKET_TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_SOCKET_READ_TIMEOUT_MS = 60_000L
        private const val STREAM_IDLE_TTL_MS = 2 * 60 * 1000L
    }
}
