package life.airen.haminn.deploy

import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.registry.AppRegistry
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** Reuses the release transaction; never writes into an installed version directory. */
class AgentWorkspace(private val cache: File, private val registry: AppRegistry, private val installer: InstallCoordinator) {
    fun local(appId: String) = registry.getInstance(appId)?.also {
        if (it.activeReleaseId == null) fail("This action requires locally installed code")
    } ?: fail("App not found")

    fun createDev(name: String, happId: String, dev: DevWorkspaceManager,
        guard: (() -> Unit) -> Unit = { it() }): JSONObject {
        val normalizedName = name.trim().takeIf { it.isNotBlank() }?.take(80) ?: fail("App name required")
        if (!happId.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")) || happId.length > 160) fail("Invalid happId")
        val archive = File.createTempFile("agent-create-dev-", ".zip", cache)
        try {
            val files = linkedMapOf(
                "haminn.json" to JSONObject().put("schema", 2).put("happId", happId).put("name", normalizedName)
                    .put("version", JSONObject().put("code", 1).put("name", "0.1.0"))
                    .put("entry", "index.html").put("routing", "hash").toString(2),
                "index.html" to "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${escapeHtml(normalizedName)}</title><link rel=\"stylesheet\" href=\"style.css\"></head><body><main><h1>${escapeHtml(normalizedName)}</h1><p>开始编写你的 HAPP 应用。</p></main><script src=\"app.js\"></script></body></html>",
                "style.css" to ":root{color-scheme:light dark;font-family:system-ui,sans-serif}body{margin:0;padding:32px;background:#08090d;color:#f7f8ff}main{max-width:720px;margin:auto}",
                "app.js" to "window.haminn?.call('app.ready', {}).catch(() => {});\n",
            )
            ZipOutputStream(archive.outputStream()).use { zip -> files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            } }
            val result = runBlocking(Dispatchers.IO) {
                archive.inputStream().use { installer.installZip(it, normalizedName, provenance = "agent-seed", commitGuard = guard) }
            }
            val workspace = dev.enter(result.appId)
            return registry.getInstance(result.appId)!!.toJson().put("devWorkspace", workspace)
        } finally { archive.delete() }
    }

    companion object {
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun hashFile(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun fail(message: String): Nothing = throw HaminnException(ErrorCodes.INVALID_ARGUMENT, message)
        private fun escapeHtml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
