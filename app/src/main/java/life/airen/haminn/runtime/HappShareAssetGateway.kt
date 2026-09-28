package life.airen.haminn.runtime

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import life.airen.haminn.share.HappShareManager
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/** Serves only short-lived QR and preview files owned by the active share flow. */
class HappShareAssetGateway(private val origin: String, private val shares: HappShareManager) {
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val base = Uri.parse(origin)
        val uri = request.url
        fun port(value: Uri) = if (value.port != -1) value.port else if (value.scheme.equals("https", true)) 443 else 80
        if (!uri.scheme.equals(base.scheme, true) || !uri.host.equals(base.host, true) || port(uri) != port(base)) return null
        val path = uri.encodedPath ?: return null
        if (!path.startsWith("/__haminn/share/")) return null
        if (request.method !in setOf("GET", "HEAD")) return empty(405, "Method Not Allowed")
        val asset = shares.openAsset(path) ?: return empty(404, "Not Found")
        val stream = if (request.method == "HEAD") ByteArrayInputStream(ByteArray(0)) else FileInputStream(asset.file)
        return WebResourceResponse(asset.mime, null, 200, "OK", mapOf(
            "Cache-Control" to "private, no-store",
            "Content-Length" to asset.file.length().toString(),
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'",
        ), stream)
    }

    private fun empty(code: Int, reason: String) = WebResourceResponse(
        "text/plain", "UTF-8", code, reason,
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
