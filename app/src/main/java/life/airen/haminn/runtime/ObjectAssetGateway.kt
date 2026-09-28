package life.airen.haminn.runtime

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import life.airen.haminn.data.FileStore
import life.airen.haminn.data.HostImageStore
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * Resolves private object URLs inside the active WebView session. HaminnUI may
 * read host presentation images; a happ may read only files from its own data
 * generation. No Android filesystem path is exposed to the page.
 */
class ObjectAssetGateway(
    context: Context,
    private val origin: String,
    private val appId: String?,
    private val dataGeneration: String?,
    private val allowHostImages: Boolean,
) {
    private val images = HostImageStore(context)
    private val files = FileStore(context)

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!sameOrigin(request.url, Uri.parse(origin))) return null
        val path = request.url.encodedPath ?: return null
        if (!path.startsWith("/__haminn/objects/") && !path.startsWith("/__haminn/files/")) return null
        if (request.method !in setOf("GET", "HEAD")) return empty(405, "Method Not Allowed")
        return when {
            path.startsWith("/__haminn/objects/images/") &&
                (allowHostImages || appId != null && path.startsWith("/__haminn/objects/images/$appId/")) -> {
                val image = images.open(path) ?: return empty(404, "Not Found")
                fileResponse(request, image.file.length(), image.mime) { FileInputStream(image.file) }
            }
            appId != null && dataGeneration != null -> {
                val targetAppId = appId
                val targetGeneration = dataGeneration
                val logicalId = FILE_URL.matchEntire(path)?.groupValues?.get(1)
                    ?: return empty(404, "Not Found")
                val metadata = runCatching { files.metadata(targetAppId, targetGeneration, logicalId) }.getOrNull()
                    ?: return empty(404, "Not Found")
                fileResponse(request, metadata.size, metadata.mime) {
                    files.open(targetAppId, targetGeneration, logicalId)
                }
            }
            else -> empty(404, "Not Found")
        }
    }

    private fun fileResponse(
        request: WebResourceRequest,
        length: Long,
        mime: String,
        open: () -> FileInputStream,
    ): WebResourceResponse {
        val range = request.requestHeaders["Range"]?.let { parseRange(it, length) }
        if (request.requestHeaders.containsKey("Range") && range == null) {
            return response(416, "Range Not Satisfiable", mime, mapOf("Content-Range" to "bytes */$length"), empty())
        }
        if (range != null) {
            val (start, end) = range
            val count = end - start + 1
            val input = if (request.method == "HEAD") empty() else BoundedInput(open().also { it.channel.position(start) }, count)
            return response(206, "Partial Content", mime, mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes $start-$end/$length",
                "Content-Length" to count.toString(),
            ), input)
        }
        val input = if (request.method == "HEAD") empty() else open()
        return response(200, "OK", mime, mapOf("Accept-Ranges" to "bytes", "Content-Length" to length.toString()), input)
    }

    private fun parseRange(value: String, length: Long): Pair<Long, Long>? {
        if (!value.startsWith("bytes=") || value.contains(',') || length <= 0) return null
        val parts = value.removePrefix("bytes=").split('-', limit = 2)
        if (parts.size != 2) return null
        val start: Long
        val end: Long
        if (parts[0].isBlank()) {
            val suffix = parts[1].toLongOrNull()?.coerceAtMost(length) ?: return null
            if (suffix <= 0) return null
            start = length - suffix
            end = length - 1
        } else {
            start = parts[0].toLongOrNull() ?: return null
            end = if (parts[1].isBlank()) length - 1 else parts[1].toLongOrNull() ?: return null
        }
        return if (start < 0 || end < start || start >= length || end >= length) null else start to end
    }

    private fun response(code: Int, reason: String, mime: String, extra: Map<String, String>, stream: InputStream) =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null,
            code, reason, BASE_HEADERS + extra, stream)

    private fun empty(code: Int, reason: String) = response(code, reason, "text/plain", emptyMap(), empty())
    private fun empty() = ByteArrayInputStream(ByteArray(0))
    private fun sameOrigin(a: Uri, b: Uri) = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    private fun port(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private class BoundedInput(private val input: FileInputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return input.read().also { if (it >= 0) remaining-- }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            return input.read(buffer, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
        }
        override fun close() = input.close()
    }

    companion object {
        private val FILE_URL = Regex("/__haminn/files/([0-9a-fA-F-]{36})")
        private val BASE_HEADERS = mapOf(
            "Cache-Control" to "private, no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'",
        )
    }
}
