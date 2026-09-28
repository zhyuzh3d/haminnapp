package life.airen.haminn

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import life.airen.haminn.runtime.SharedAssetGateway
import life.airen.haminn.runtime.LocalContentGateway
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedAssetGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun request(url: String, method: String = "GET", mainFrame: Boolean = false) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
    @Test fun injectsAndServesCompatibilityRuntimeForOldProviders() {
        val local = LocalContentGateway.forStore(context, injectRuntime = true)
        val html = local.intercept(request("https://store.haminn.invalid/", mainFrame = true))!!
        assertEquals(200, html.statusCode)
        html.data.use { assertTrue(it.readBytes().decodeToString().contains(LocalContentGateway.RUNTIME_PATH)) }

        val shared = SharedAssetGateway(context, "https://store.haminn.invalid", "window.compatibilityRuntime=true")
        val runtime = shared.intercept(request("https://store.haminn.invalid${LocalContentGateway.RUNTIME_PATH}"))!!
        assertEquals(200, runtime.statusCode)
        assertEquals("text/javascript", runtime.mimeType)
        runtime.data.use { assertTrue(it.readBytes().decodeToString().contains("compatibilityRuntime")) }
    }
    @Test fun servesEveryStyleOfflineOnLocalAndOnlineOrigins() {
        for (origin in listOf("https://app.haminn.invalid", "https://example.test", "http://127.0.0.1:8787")) {
            val gateway = SharedAssetGateway(context, origin)
            val css = gateway.intercept(request("$origin/__haminn/icons/fontawesome/css/all.min.css"))!!
            assertEquals(200, css.statusCode); assertEquals("text/css", css.mimeType)
            css.data.use {
                val text = it.readBytes().decodeToString()
                assertTrue(text.contains("Font Awesome 7"))
                assertTrue(text.contains(".fa-solid:before"))
            }
            for (name in listOf("fa-solid-900", "fa-regular-400", "fa-brands-400", "fa-v4compatibility")) {
                val font = gateway.intercept(request("$origin/__haminn/icons/fontawesome/webfonts/$name.woff2"))!!
                assertEquals(200, font.statusCode); assertEquals("font/woff2", font.mimeType)
                font.data.use { assertEquals("wOF2", it.readBytes().take(4).toByteArray().decodeToString()) }
            }
            val head = gateway.intercept(request("$origin/__haminn/icons/fontawesome/css/all.min.css", "HEAD"))!!
            assertEquals(200, head.statusCode); head.data.use { assertEquals(-1, it.read()) }
        }
    }
    @Test fun rejectsTraversalWritesAndUnlistedResourcesWithoutInterceptingOtherOrigins() {
        val gateway = SharedAssetGateway(context, "https://example.test")
        assertNull(gateway.intercept(request("https://other.test/__haminn/icons/fontawesome/css/all.min.css")))
        assertNull(gateway.intercept(request("https://example.test:444/__haminn/icons/fontawesome/css/all.min.css")))
        assertNull(gateway.intercept(request("http://example.test/__haminn/icons/fontawesome/css/all.min.css")))
        assertNull(gateway.intercept(request("https://example.test/app.js")))
        for (path in listOf("../store/index.html", "icons/fontawesome/css/../../LICENSE.txt", "icons/fontawesome/css/%2e%2e/LICENSE.txt", "private.db")) {
            val result = gateway.intercept(request("https://example.test/__haminn/$path"))!!
            assertEquals(404, result.statusCode); result.data.close()
        }
        val post = gateway.intercept(request("https://example.test/__haminn/icons/fontawesome/css/all.min.css", "POST"))!!
        assertEquals(405, post.statusCode); post.data.close()
    }
}
