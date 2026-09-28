package life.airen.haminn.runtime

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap

/** Mounts an immutable release into its real page URL space. */
class LocalContentGateway private constructor(
    private val context: Context,
    private val baseUrl: Uri,
    private val root: File?,
    private val devResolver: ((String) -> File?)?,
    private val assetPrefix: String?,
    private val entryPath: String,
    private val historyFallback: Boolean,
    private val injectRuntime: Boolean,
    private val allowNetworkFallback: Boolean,
    private val devRevisionProvider: (() -> Long?)? = null,
) {
    private val localMainFrames = ConcurrentHashMap.newKeySet<String>()

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (!sameOrigin(uri, baseUrl)) return if (allowNetworkFallback) null else blocked()
        if (request.method !in setOf("GET", "HEAD")) {
            return if (allowNetworkFallback) null else response(405, "Method Not Allowed", "text/plain", emptyMap(), empty())
        }
        val relative = localPath(uri) ?: return if (allowNetworkFallback) null else blocked()
        if (relative.startsWith("__haminn/")) return response(404, "Not Found", "text/plain", emptyMap(), empty())
        val path = if (relative.isBlank()) entryPath else relative
        if (!safePath(path)) return response(400, "Bad Request", "text/plain", emptyMap(), empty())
        val result = if (root != null || devResolver != null) fileResponse(request, path) else assetResponse(request, path)
        if (result == null) return if (allowNetworkFallback) null else blocked()
        if (request.isForMainFrame && result.statusCode in 200..299) localMainFrames.add(canonicalUrl(uri))
        return result
    }

    fun wasLocalMainFrame(url: String?): Boolean = url != null && canonicalUrl(Uri.parse(url)) in localMainFrames

    private fun localPath(uri: Uri): String? {
        if (assetPrefix != null) return Uri.decode(uri.encodedPath ?: "/").removePrefix("/")
        val requestPath = uri.encodedPath ?: "/"
        val entryUrlPath = normalizedEntryUrlPath()
        val mountDirectory = if (entryUrlPath.endsWith('/')) entryUrlPath else entryUrlPath.substringBeforeLast('/', "") + "/"
        if (requestPath == entryUrlPath || entryUrlPath.endsWith('/') && requestPath == entryUrlPath.dropLast(1)) return ""
        if (!requestPath.startsWith(mountDirectory)) return null
        return Uri.decode(requestPath.removePrefix(mountDirectory))
    }

    private fun normalizedEntryUrlPath(): String {
        val path = baseUrl.encodedPath?.takeIf { it.isNotBlank() } ?: "/"
        return if (path.startsWith('/')) path else "/$path"
    }

    private fun fileResponse(request: WebResourceRequest, path: String): WebResourceResponse? {
        val canonicalRoot = root?.canonicalFile
        var file = if (devResolver != null) devResolver.invoke(path) else File(canonicalRoot!!, path).canonicalFile
        if (file == null || !file.isFile || canonicalRoot != null && !inside(canonicalRoot, file)) {
            if (historyFallback && request.isForMainFrame && request.requestHeaders["Accept"]?.contains("text/html") == true) {
                file = if (devResolver != null) devResolver.invoke(entryPath) else File(canonicalRoot!!, entryPath).canonicalFile
            }
        }
        if (file == null || !file.isFile || canonicalRoot != null && !inside(canonicalRoot, file)) return null
        val range = request.requestHeaders["Range"]?.let { parseRange(it, file.length()) }
        if (request.requestHeaders.containsKey("Range") && range == null) {
            return response(416, "Range Not Satisfiable", mime(path), headers() + ("Content-Range" to "bytes */${file.length()}"), empty())
        }
        if (range != null) {
            val (start, end) = range
            val length = end - start + 1
            return response(206, "Partial Content", mime(path), headers() + mapOf(
                "Accept-Ranges" to "bytes", "Content-Range" to "bytes $start-$end/${file.length()}", "Content-Length" to length.toString(),
            ), if (request.method == "HEAD") empty() else BoundedFileInputStream(file, start, length))
        }
        if ((injectRuntime || devRevisionProvider != null) && request.isForMainFrame && mime(path) == "text/html" && file.length() <= MAX_INJECTABLE_HTML_BYTES) {
            val bytes = inject(file.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            return response(200, "OK", "text/html", headers() + mapOf("Content-Length" to bytes.size.toString(), "Accept-Ranges" to "none"),
                if (request.method == "HEAD") empty() else ByteArrayInputStream(bytes))
        }
        return response(200, "OK", mime(path), headers() + mapOf("Content-Length" to file.length().toString(), "Accept-Ranges" to "bytes"),
            if (request.method == "HEAD") empty() else FileInputStream(file))
    }

    private fun assetResponse(request: WebResourceRequest, path: String): WebResourceResponse? {
        val fullPath = "$assetPrefix/$path"
        return try {
            if ((injectRuntime || devRevisionProvider != null) && request.isForMainFrame && mime(path) == "text/html") {
                val bytes = context.assets.open(fullPath).bufferedReader(Charsets.UTF_8).use { inject(it.readText()) }.toByteArray(Charsets.UTF_8)
                response(200, "OK", "text/html", headers() + ("Content-Length" to bytes.size.toString()),
                    if (request.method == "HEAD") empty() else ByteArrayInputStream(bytes))
            } else response(200, "OK", mime(path), headers(), if (request.method == "HEAD") empty() else context.assets.open(fullPath))
        } catch (_: Throwable) { null }
    }

    private fun headers(): Map<String, String> {
        val policy = if (assetPrefix != null || !allowNetworkFallback) {
            "default-src 'self' data: blob:; connect-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"
        } else "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
        return mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
            "Service-Worker-Allowed" to "https://sw-disabled.haminn.invalid/", "Content-Security-Policy" to policy)
    }

    private fun inject(html: String): String {
        val revision = devRevisionProvider?.invoke()
        val marker = if (revision == null) "" else "<meta name=\"haminn-dev-revision\" content=\"$revision\">"
        val runtime = if (!injectRuntime || html.contains(RUNTIME_PATH)) "" else "<script src=\"$RUNTIME_PATH\"></script>"
        val tag = marker + runtime
        if (tag.isEmpty()) return html
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        if (head != null) return html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1)
        val doctype = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
        val offset = doctype?.range?.last?.plus(1) ?: 0
        return html.substring(0, offset) + tag + html.substring(offset)
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
            start = length - suffix; end = length - 1
        } else {
            start = parts[0].toLongOrNull() ?: return null
            end = if (parts[1].isBlank()) length - 1 else parts[1].toLongOrNull() ?: return null
        }
        return if (start < 0 || end < start || start >= length || end >= length) null else start to end
    }

    private fun sameOrigin(a: Uri, b: Uri) = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)
    private fun effectivePort(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun canonicalUrl(uri: Uri) = uri.buildUpon().fragment(null).build().toString()
    private fun safePath(path: String) = path.isNotBlank() && !path.contains('\u0000') && !path.contains('\\') && path.split('/').none { it.isBlank() || it == "." || it == ".." }
    private fun inside(root: File, file: File) = file.path.startsWith(root.path + File.separator)
    private fun blocked() = response(403, "Forbidden", "text/plain", emptyMap(), empty())
    private fun response(code: Int, reason: String, mime: String, headers: Map<String, String>, stream: InputStream) =
        WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null, code, reason, headers, stream)
    private fun empty() = ByteArrayInputStream(ByteArray(0))
    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json", "map" -> "application/json"
        "wasm" -> "application/wasm"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"; "gif" -> "image/gif"; "woff" -> "font/woff"; "woff2" -> "font/woff2"
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "mp4" -> "video/mp4"
        else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
    }

    companion object {
        const val RUNTIME_PATH = "/__haminn/bridge/runtime-v1.js"
        private const val MAX_INJECTABLE_HTML_BYTES = 2L * 1024 * 1024
        fun forStore(context: Context, injectRuntime: Boolean = false) = LocalContentGateway(
            context, Uri.parse("https://store.haminn.invalid/"), null, null, "store", "index.html", false, injectRuntime, false, null)
        fun forRelease(context: Context, runtimeUrl: String, root: File, entryPath: String, historyFallback: Boolean,
            injectRuntime: Boolean = false, allowNetworkFallback: Boolean) =
            LocalContentGateway(context, Uri.parse(runtimeUrl), root, null, null, entryPath, historyFallback, injectRuntime, allowNetworkFallback, null)
        fun forDev(context: Context, runtimeUrl: String, entryPath: String, historyFallback: Boolean,
            injectRuntime: Boolean = false, allowNetworkFallback: Boolean, revisionProvider: () -> Long?, resolver: (String) -> File?) =
            LocalContentGateway(context, Uri.parse(runtimeUrl), null, resolver, null, entryPath, historyFallback, injectRuntime, allowNetworkFallback, revisionProvider)
    }
}

private class BoundedFileInputStream(file: File, offset: Long, private var remaining: Long) : InputStream() {
    private val input = FileInputStream(file).also { it.channel.position(offset) }
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
