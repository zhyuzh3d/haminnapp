package life.airen.haminn.runtime

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Applies the small page-network contract after local and APK-owned resources were resolved. */
class RuntimeNetworkPolicy(
    runtimeUrl: String,
    private val allowCrossOriginNetwork: Boolean,
) {
    private val origin = Uri.parse(runtimeUrl)

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val target = request.url
        if (target.scheme !in setOf("http", "https")) return forbidden()
        if (sameOrigin(target, origin) || allowCrossOriginNetwork || isPassiveResource(request)) return null
        return forbidden()
    }

    private fun isPassiveResource(request: WebResourceRequest): Boolean {
        val destination = request.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value?.lowercase()
        if (destination in setOf("image", "style", "font", "audio", "video", "track")) return true
        if (destination != null && destination !in setOf("empty")) return false
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.lowercase().orEmpty()
        return accept.startsWith("image/") || accept.startsWith("audio/") || accept.startsWith("video/") ||
            accept.contains("text/css") || accept.contains("font/")
    }

    private fun sameOrigin(a: Uri, b: Uri) = a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
    private fun port(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun forbidden() = WebResourceResponse("text/plain", "UTF-8", 403, "Cross-Origin Request Blocked",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
}
