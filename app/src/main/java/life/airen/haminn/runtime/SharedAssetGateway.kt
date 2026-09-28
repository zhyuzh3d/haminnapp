package life.airen.haminn.runtime

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Public, APK-owned assets only. Never maps a caller-supplied path onto a directory. */
class SharedAssetGateway(private val context: Context, private val origin: String, private val runtimeSource: String? = null) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val base = Uri.parse(origin)
        fun port(value: Uri) = if (value.port != -1) value.port else if (value.scheme == "https") 443 else 80
        if (uri.scheme != base.scheme || !uri.host.equals(base.host, true) || port(uri) != port(base)) return null
        val path = uri.encodedPath ?: return null
        if (!path.startsWith("/__haminn/")) return null
        val headers = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        fun empty(code: Int, reason: String) = WebResourceResponse("text/plain", "UTF-8", code, reason, headers, ByteArrayInputStream(ByteArray(0)))
        if (request.method !in setOf("GET", "HEAD")) return empty(405, "Method Not Allowed")
        if (path == LocalContentGateway.RUNTIME_PATH && runtimeSource != null) {
            val bytes = runtimeSource.toByteArray(Charsets.UTF_8)
            return WebResourceResponse("text/javascript", "UTF-8", 200, "OK", headers + ("Content-Length" to bytes.size.toString()),
                if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(bytes))
        }
        val file = allowed[path] ?: return empty(404, "Not Found")
        return try {
            if (path == FONT_AWESOME_CSS_PATH) {
                val bytes = context.assets.open(file.first).use { it.readBytes() } + FONT_AWESOME_LEGACY_CSS.toByteArray(Charsets.UTF_8)
                return WebResourceResponse(file.second, "UTF-8", 200, "OK", headers + ("Content-Length" to bytes.size.toString()),
                    if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0)) else ByteArrayInputStream(bytes))
            }
            WebResourceResponse(file.second, if (file.second.startsWith("text/")) "UTF-8" else null,
                200, "OK", headers, if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0)) else context.assets.open(file.first))
        } catch (_: java.io.IOException) { empty(404, "Not Found") }
    }

    companion object {
        private const val FONT_AWESOME_CSS_PATH = "/__haminn/icons/fontawesome/css/all.min.css"
        // Font Awesome 7 uses :is() for this selector. Chromium versions before 88 drop it entirely.
        // Keep the upstream file intact and append an equivalent selector list for old OEM WebViews.
        private const val FONT_AWESOME_LEGACY_CSS =
            ".fas:before,.far:before,.fab:before,.fa-solid:before,.fa-regular:before,.fa-brands:before,.fa-classic:before,.fa:before{content:var(--fa)}"
        private val allowed = buildMap {
            val base = "/__haminn/icons/fontawesome/"
            put(base + "css/all.min.css", "shared/fontawesome/css/all.min.css" to "text/css")
            put(base + "LICENSE.txt", "shared/fontawesome/LICENSE.txt" to "text/plain")
            for (name in listOf("fa-solid-900", "fa-regular-400", "fa-brands-400", "fa-v4compatibility")) {
                put(base + "webfonts/$name.woff2", "shared/fontawesome/webfonts/$name.woff2" to "font/woff2")
            }
        }
    }
}
