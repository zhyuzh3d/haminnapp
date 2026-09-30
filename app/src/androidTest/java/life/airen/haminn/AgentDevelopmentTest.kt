package life.airen.haminn

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import life.airen.haminn.deploy.AgentDevelopmentServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AgentDevelopmentTest {
    private lateinit var app: HaminnApplication
    private lateinit var server: AgentDevelopmentServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var base: String
    private lateinit var password: String
    private var before = emptySet<String>()

    @Before fun start() {
        app = ApplicationProvider.getApplicationContext()
        before = app.registry.listInstances().map { it.appId }.toSet()
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        server = app.agentServer
        server.disable("Test setup")
        base = server.start("127.0.0.1", 0).getString("address")
        password = server.passwordForUi()
    }

    @After fun finish() {
        server.disable("Test complete")
        scenario.close()
        app.registry.listInstances().filter { it.appId !in before }.forEach {
            app.devWorkspaces.delete(it.appId)
            app.registry.deleteInstance(it.appId); app.installer.deleteAppFiles(it.appId); app.registry.finishDelete(it.appId)
        }
    }

    private fun request(path: String, method: String = "GET", body: ByteArray? = null, credential: String? = password, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 5000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25")
            if (credential != null) connection.setRequestProperty("Authorization", "Bearer $credential")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            val code = connection.responseCode
            val result = (if (code >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to result
        } finally { connection.disconnect() }
    }

    private fun requestBytes(path: String, credential: String? = password): Pair<Int, ByteArray> {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"; connection.connectTimeout = 5000; connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/zip")
            if (credential != null) connection.setRequestProperty("Authorization", "Bearer $credential")
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return code to (stream?.readBytes() ?: ByteArray(0))
        } finally { connection.disconnect() }
    }

    private fun rpc(method: String, args: JSONObject = JSONObject(), credential: String = password): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", args)
        val response = request("/mcp", "POST", payload.toString().toByteArray(), credential)
        assertEquals(200, response.first)
        val result = JSONObject(response.second)
        assertFalse(result.toString(), result.has("error"))
        return result.getJSONObject("result")
    }

    private fun tool(name: String, args: JSONObject = JSONObject(), failure: Boolean = false): JSONObject {
        val result = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        assertEquals(result.toString(), failure, result.getBoolean("isError"))
        return result.getJSONObject("structuredContent")
    }

    private fun create(): JSONObject = tool("haminn_create_dev_app", JSONObject()
        .put("name", "Agent test")
        .put("happId", "com.example.agent${UUID.randomUUID().toString().replace("-", "")}")
        .put("requestId", UUID.randomUUID().toString()))

    @Test fun sharedPasswordPersistsAndReplacesWithoutPairingOrClientIdentity() {
        assertTrue(password.matches(Regex("[0-9]{6}")))
        val ping = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping").toString().toByteArray()
        val unauthenticated = request("/mcp", "POST", ping, null)
        assertEquals(401, unauthenticated.first)
        JSONObject(unauthenticated.second).let {
            assertEquals("authentication_required", it.getString("error"))
            assertEquals("missing_credentials", it.getString("reason"))
            assertEquals("ask_user_for_current_password", it.getString("nextAction"))
            assertTrue(it.getString("message").contains("开发配置"))
        }
        assertEquals(200, request("/mcp", "POST", ping).first)
        // Independent HTTP connections need no initialize identity, pairing, session ID or peer registration.
        assertEquals(200, request("/mcp", "POST", ping, password, mapOf("User-Agent" to "second-computer")).first)
        val old = password
        server.resetPassword(); password = server.passwordForUi()
        assertTrue(old != password)
        assertEquals(401, request("/mcp", "POST", ping, old).first)
        assertEquals(200, request("/mcp", "POST", ping).first)
        server.resetPassword("aB12cD"); password = server.passwordForUi()
        assertEquals("aB12cD", password)
        assertEquals(200, request("/mcp", "POST", ping).first)
        server.resetPassword(); password = server.passwordForUi()
        server.stop("Test restart"); base = server.start("127.0.0.1", 0).getString("address")
        assertTrue(password == server.passwordForUi())
        val recreated = AgentDevelopmentServer(app, app.registry, app.installer, app.devWorkspaces, app.applicationScope)
        assertTrue(password == recreated.passwordForUi())
        assertEquals(200, request("/mcp", "POST", ping).first)
        assertEquals(404, request("/pair", "POST", "{}".toByteArray()).first)
    }

    @Test fun protocolDiscoveryOriginAndBruteForceLimits() {
        val root = request("/", credential = null)
        assertEquals(200, root.first)
        assertEquals("haminn-agent-bootstrap", JSONObject(root.second).getString("kind"))
        val discovery = JSONObject(request("/.well-known/haminn-agent", credential = null).second)
        assertFalse(discovery.toString().contains(password)); assertFalse(discovery.has("pairUrl"))
        assertEquals(3, discovery.getInt("schema"))
        assertTrue(discovery.getJSONArray("toolIndex").length() >= 26)
        assertTrue(discovery.getJSONArray("intentIndex").length() >= 5)
        assertTrue(discovery.getJSONObject("resourceDigests").has("webappGuide"))
        assertEquals(200, request("/skills/haminn-dev-plugin/SKILL.md", credential = null).first)
        assertEquals(200, request("/haminn-agent.py", credential = null).first)
        val initialized = rpc("initialize", JSONObject().put("protocolVersion", "2025-11-25").put("clientInfo", JSONObject().put("name", "test").put("version", "1")).put("capabilities", JSONObject()))
        assertEquals("2025-11-25", initialized.getString("protocolVersion"))
        assertTrue(initialized.getString("instructions").contains("password"))
        assertTrue(rpc("tools/list").getJSONArray("tools").length() >= 26)
        assertTrue(rpc("resources/read", JSONObject().put("uri", "haminn://tool-index"))
            .getJSONArray("contents").getJSONObject(0).getString("text").contains("haminn_runtime_status"))
        assertTrue(rpc("resources/read", JSONObject().put("uri", "haminn://tool/haminn_runtime_status"))
            .getJSONArray("contents").getJSONObject(0).getString("text").contains("inputSchema"))
        assertTrue(rpc("resources/read", JSONObject().put("uri", "haminn://webapp-guide")).getJSONArray("contents").getJSONObject(0).getString("text").contains("HTML"))
        assertTrue(rpc("prompts/get", JSONObject().put("name", "develop-webapp"))
            .getJSONArray("messages").getJSONObject(0).getJSONObject("content").getString("text").contains("haminn_enter_dev_mode"))
        val modernPayload = JSONObject().put("jsonrpc", "2.0").put("id", "discover")
            .put("method", "server/discover").put("params", JSONObject().put("_meta", JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", "2026-07-28")
                .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "portable-test").put("version", "1"))
                .put("io.modelcontextprotocol/clientCapabilities", JSONObject())))
        val modernResponse = request("/mcp", "POST", modernPayload.toString().toByteArray(), headers = mapOf(
            "MCP-Protocol-Version" to "2026-07-28", "Mcp-Method" to "server/discover"))
        assertEquals(200, modernResponse.first)
        val modernResult = JSONObject(modernResponse.second).getJSONObject("result")
        assertTrue(modernResult.getJSONArray("supportedVersions").toString().contains("2026-07-28"))
        assertEquals("complete", modernResult.getString("resultType"))
        assertEquals("haminn-dev-plugin", modernResult.getJSONObject("_meta")
            .getJSONObject("io.modelcontextprotocol/serverInfo").getString("name"))
        val notice = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}".toByteArray()
        assertEquals(202, request("/mcp", "POST", notice).first)
        assertEquals(405, request("/mcp").first)
        assertEquals(403, request("/mcp", "POST", notice, headers = mapOf("Origin" to "https://untrusted.invalid")).first)
        assertEquals(400, request("/mcp", "POST", notice, headers = mapOf("MCP-Protocol-Version" to "invalid")).first)
        repeat(8) {
            val missing = request("/mcp", "POST", notice, credential = null)
            assertEquals(401, missing.first)
            assertEquals("authentication_required", JSONObject(missing.second).getString("error"))
        }
        repeat(5) { assertEquals(401, request("/mcp", "POST", notice, "invalid").first) }
        val locked = request("/mcp", "POST", notice, "invalid")
        assertEquals(429, locked.first)
        assertEquals("wait", JSONObject(locked.second).getString("nextAction"))
    }

    @Test fun bootstrapDescribesAndServesHaminnPlugin() {
        val bootstrapResponse = request("/", headers = mapOf("Accept" to "application/json"))
        assertEquals(200, bootstrapResponse.first)
        val bootstrap = JSONObject(bootstrapResponse.second)
        assertEquals("haminn-agent-bootstrap", bootstrap.getString("kind"))
        assertEquals("codex-plugin-archive-v1", bootstrap.getString("packageFormat"))
        assertFalse(bootstrap.has("nativeCodexPlugin"))
        assertFalse(bootstrap.has("codexIntegration"))
        assertFalse(bootstrap.has("connection"))
        assertTrue(bootstrap.getJSONObject("plugin").getString("codexVersion").startsWith(bootstrap.getJSONObject("plugin").getString("version") + "+codex."))
        assertEquals("install_or_update", bootstrap.getJSONObject("install").getString("action"))
        assertEquals("codex-plugin-archive-v1", bootstrap.getJSONObject("install").getString("packageFormat"))
        assertEquals("~/plugins/haminn-dev-plugin", bootstrap.getJSONObject("install").getString("target"))
        assertEquals("atomic_replace_if_hash_differs", bootstrap.getJSONObject("install").getString("strategy"))
        assertEquals("no_op", bootstrap.getJSONObject("install").getString("existingSameVersion"))
        assertEquals("authenticate", bootstrap.getJSONObject("install").getString("afterInstall"))
        // The connector is gone: nothing registers or advertises an MCP server.
        assertFalse(bootstrap.getJSONObject("install").has("mcpRegistration"))
        assertEquals("/", bootstrap.getJSONObject("clientContract").getJSONObject("firstRequest").getString("path"))
        assertEquals(2, bootstrap.getJSONObject("clientContract").getJSONArray("successStates").length())
        assertEquals("ask_user_for_current_address", bootstrap.getJSONObject("recovery").getJSONObject("addressUnavailable").getString("nextAction"))
        assertEquals("ask_user_for_current_password", bootstrap.getJSONObject("recovery").getJSONObject("passwordInvalid").getString("nextAction"))
        assertFalse(bootstrap.toString().contains(password))

        val discovery = JSONObject(request("/.well-known/haminn-agent", credential = null).second)
        assertEquals(bootstrap.getString("kind"), discovery.getString("kind"))
        val packagePath = URL(bootstrap.getJSONObject("install").getString("packageUrl")).path
        val packageResponse = requestBytes(packagePath, credential = null)
        assertEquals(200, packageResponse.first)
        val packageBytes = packageResponse.second
        val digest = MessageDigest.getInstance("SHA-256").digest(packageBytes).joinToString("") { "%02x".format(it) }
        assertEquals(bootstrap.getJSONObject("install").getString("packageSha256"), digest)
        val entries = mutableListOf<String>()
        var codexManifest: JSONObject? = null
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
                if (entry.name == ".codex-plugin/plugin.json") codexManifest = JSONObject(String(zip.readBytes()))
            }
        }
        assertTrue(entries.contains(".codex-plugin/plugin.json"))
        assertFalse(entries.contains(".mcp.json"))
        assertTrue(entries.contains("skills/haminn-dev-plugin/SKILL.md"))
        assertEquals(bootstrap.getJSONObject("plugin").getString("codexVersion"), codexManifest!!.getString("version"))
        assertFalse(codexManifest!!.has("mcpServers"))
    }

    @Test fun screenshotReturnsMcpImageAndRecentOperationsStayBounded() {
        val envelope = rpc("tools/call", JSONObject().put("name", "haminn_capture_screen").put("arguments", JSONObject().put("maxEdge", 720)))
        assertFalse(envelope.getBoolean("isError"))
        val content = envelope.getJSONArray("content")
        val image = (0 until content.length()).map(content::getJSONObject).first { it.getString("type") == "image" }
        assertTrue(image.getString("mimeType") in setOf("image/png", "image/jpeg"))
        assertTrue(android.util.Base64.decode(image.getString("data"), android.util.Base64.DEFAULT).size > 100)
        val metadata = envelope.getJSONObject("structuredContent")
        assertEquals("STORE", metadata.getString("role"))
        assertFalse(metadata.has("_imageData"))
        assertTrue(metadata.getInt("width") <= 720)
        assertTrue(metadata.getInt("height") <= 720)

        repeat(24) { tool("haminn_get_guide") }
        assertEquals(20, server.status().getJSONArray("events").length())
    }

    @Test fun devPatchRetryConflictReadOpenReloadRollbackAndAllAppsVisible() {
        val created = create(); val id = created.getString("appId"); val first = created.getString("activeReleaseId")
        val secondApp = create()
        assertTrue(tool("haminn_list_apps").getJSONArray("apps").toString().contains(secondApp.getString("appId")))
        val revision = created.getJSONObject("devWorkspace").getLong("revision")
        val args = JSONObject().put("appId", id).put("expectedDevRevision", revision).put("requestId", UUID.randomUUID().toString()).put("refreshMode", "none")
            .put("files", JSONArray().put(JSONObject().put("path", "index.html").put("content", "<!doctype html><html><title>MCP updated</title><body>native page</body></html>")))
        val applied = tool("haminn_apply_dev_files", args)
        assertEquals(applied.getLong("revision"), tool("haminn_apply_dev_files", args).getLong("revision"))
        val stale = JSONObject(args.toString()).put("requestId", UUID.randomUUID().toString())
        assertEquals("E_CONFLICT", tool("haminn_apply_dev_files", stale, true).getString("code"))
        val file = tool("haminn_read_dev_file", JSONObject().put("appId", id).put("path", "index.html"))
        assertTrue(file.getString("content").contains("native page"))
        tool("haminn_read_dev_file", JSONObject().put("appId", id).put("path", "../secret"), true)
        assertTrue(tool("haminn_list_dev_files", JSONObject().put("appId", id)).getJSONArray("files").length() > 0)
        assertTrue(tool("haminn_open_app", JSONObject().put("appId", id)).getString("state") in setOf("opening", "reloading"))
        assertEquals(id, tool("haminn_runtime_status").getString("appId"))
        assertEquals("reloading", tool("haminn_reload_app", JSONObject().put("appId", id)).getString("state"))
        assertEquals("E_INVALID_ARGUMENT", tool("haminn_reload_app", JSONObject().put("appId", secondApp.getString("appId")), true).getString("code"))
        val built = tool("haminn_build_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", applied.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("versionCode", 2).put("versionName", "1.0.0"))
        val installed = tool("haminn_install_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", applied.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("buildId", built.getString("buildId")).put("expectedStableReleaseId", first))
        assertNotEquals(first, installed.getString("releaseId"))
        assertTrue(tool("haminn_list_releases", JSONObject().put("appId", id)).getJSONArray("releases").length() >= 2)
        tool("haminn_rollback", JSONObject().put("appId", id).put("releaseId", first).put("expectedReleaseId", installed.getString("releaseId")).put("requestId", UUID.randomUUID().toString()))
        assertEquals(first, app.registry.getInstance(id)!!.activeReleaseId)
    }

    @Test fun devWorkspaceSupportsAtomicEditsConflictsBuildAndPublish() {
        val happId = "com.example.agent" + UUID.randomUUID().toString().replace("-", "")
        val created = tool("haminn_create_dev_app", JSONObject()
            .put("name", "Dev workspace test").put("happId", happId).put("requestId", UUID.randomUUID().toString()))
        val id = created.getString("appId")
        val stableRelease = created.getString("activeReleaseId")
        val initial = created.getJSONObject("devWorkspace")
        assertEquals("dev", created.getString("launchChannel"))
        assertEquals("clean", initial.getString("state"))

        val revision = initial.getLong("revision")
        val changes = JSONArray()
            .put(JSONObject().put("path", "index.html").put("content", "<!doctype html><title>Dev</title><link rel=\"stylesheet\" href=\"theme.css\"><h1>dev tree</h1>"))
            .put(JSONObject().put("from", "style.css").put("to", "theme.css").put("move", true))
            .put(JSONObject().put("path", "notes/new.txt").put("content", "created in dev"))
        val applied = tool("haminn_apply_dev_files", JSONObject().put("appId", id)
            .put("expectedDevRevision", revision).put("requestId", UUID.randomUUID().toString())
            .put("refreshMode", "none").put("files", changes))
        assertEquals("dirty", applied.getString("state"))
        assertEquals(stableRelease, app.registry.getInstance(id)!!.activeReleaseId)
        assertTrue(tool("haminn_read_dev_file", JSONObject().put("appId", id).put("path", "notes/new.txt"))
            .getString("content").contains("created in dev"))
        assertEquals("E_CONFLICT", tool("haminn_apply_dev_files", JSONObject().put("appId", id)
            .put("expectedDevRevision", revision).put("requestId", UUID.randomUUID().toString())
            .put("refreshMode", "none").put("files", JSONArray().put(JSONObject().put("path", "late.txt").put("content", "stale"))), true).getString("code"))

        tool("haminn_leave_dev_mode", JSONObject().put("appId", id).put("requestId", UUID.randomUUID().toString()))
        assertEquals("E_DEV_MODE_REQUIRED", tool("haminn_apply_dev_files", JSONObject().put("appId", id)
            .put("expectedDevRevision", applied.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("refreshMode", "none").put("files", JSONArray().put(JSONObject().put("path", "blocked.txt").put("content", "blocked"))), true).getString("code"))
        val resumed = tool("haminn_enter_dev_mode", JSONObject().put("appId", id).put("requestId", UUID.randomUUID().toString()))
        assertEquals(applied.getLong("revision"), resumed.getLong("revision"))
        assertTrue(resumed.getJSONObject("runtime").getString("state") in setOf("opening", "reloading"))
        val runtime = tool("haminn_runtime_status")
        assertEquals(id, runtime.getString("appId"))
        assertEquals("dev", runtime.getString("launchChannel"))
        assertTrue(runtime.getBoolean("isDevelopmentCopy"))

        val built = tool("haminn_build_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", resumed.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("versionCode", 2).put("versionName", "1.0.0"))
        val downloaded = request(URL(built.getString("downloadUrl")).path, credential = password)
        assertEquals(200, downloaded.first)
        val installed = tool("haminn_install_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", resumed.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("buildId", built.getString("buildId")).put("expectedStableReleaseId", stableRelease))
        assertNotEquals(stableRelease, installed.getString("releaseId"))
        assertEquals("stable", app.registry.getInstance(id)!!.launchChannel.name.lowercase())
        assertEquals("clean", tool("haminn_get_dev_status", JSONObject().put("appId", id)).getString("state"))
    }

    @Test fun devStatusReportsContentFactsAndNamesTheForkWithoutAsking() {
        val happId = "com.example.agent" + UUID.randomUUID().toString().replace("-", "")
        val created = tool("haminn_create_dev_app", JSONObject()
            .put("name", "Fork facts test").put("happId", happId).put("requestId", UUID.randomUUID().toString()))
        val id = created.getString("appId")

        // A fresh workspace is a copy of the phone's release: the two sides agree, so there is
        // nothing to ask about and the status has to say so in content terms.
        val initial = tool("haminn_get_dev_status", JSONObject().put("appId", id))
        assertEquals("clean", initial.getString("state"))
        assertTrue(initial.getBoolean("matchesActive"))
        assertFalse(initial.getBoolean("dirty"))
        assertFalse(initial.getBoolean("baseOutdated"))
        assertEquals(initial.getString("treeHash"), initial.getString("activeTreeHash"))
        assertEquals(created.getString("activeReleaseId"), initial.getJSONObject("activeRelease").getString("releaseId"))

        fun edit(marker: String, revision: Long) = tool("haminn_apply_dev_files", JSONObject()
            .put("appId", id).put("expectedDevRevision", revision).put("requestId", UUID.randomUUID().toString())
            .put("refreshMode", "none")
            .put("files", JSONArray().put(JSONObject().put("path", "index.html").put("content", "<h1>$marker</h1>"))))

        // Only the development side moved. Still nothing to ask about, and the phone's release is
        // untouched — no version number is consulted anywhere in this.
        val applied = edit("mine", initial.getLong("revision"))
        val dirty = tool("haminn_get_dev_status", JSONObject().put("appId", id))
        assertEquals("dirty", dirty.getString("state"))
        assertTrue(dirty.getBoolean("dirty"))
        assertFalse(dirty.getBoolean("baseOutdated"))
        assertFalse(dirty.getBoolean("matchesActive"))
        assertEquals(created.getString("activeReleaseId"), app.registry.getInstance(id)!!.activeReleaseId)

        // Publishing my own package is the agreement point: afterwards the workspace must be back
        // in step, or every later session would ask about a fork that does not exist.
        val built = tool("haminn_build_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", applied.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("versionCode", 2).put("versionName", "1.0.0"))
        val promoted = tool("haminn_install_dev_package", JSONObject().put("appId", id)
            .put("expectedDevRevision", applied.getLong("revision")).put("requestId", UUID.randomUUID().toString())
            .put("buildId", built.getString("buildId")).put("expectedStableReleaseId", created.getString("activeReleaseId")))
        val published = tool("haminn_get_dev_status", JSONObject().put("appId", id))
        assertEquals("clean", published.getString("state"))
        assertTrue(published.getBoolean("matchesActive"))
        assertFalse(published.getBoolean("baseOutdated"))
        assertFalse(published.getBoolean("dirty"))

        // Somebody else's package lands on the phone while unpublished edits sit in the copy. Only
        // now may anything be asked, and the status has to show which side is which. Publishing put
        // the launch channel back to stable, so the copy has to be entered again before it takes edits.
        val resumed = tool("haminn_enter_dev_mode", JSONObject().put("appId", id).put("requestId", UUID.randomUUID().toString()))
        edit("mine again", resumed.getLong("revision"))
        val foreign = packageZip(mapOf(
            "haminn.json" to ("""{"schema":2,"happId":"$happId","name":"Fork facts test","author":"someone-else",""" +
                """"maintainer":"someone-else","version":{"code":9,"name":"9.0.0"},"entry":"index.html"}"""),
            "index.html" to "<h1>theirs</h1>",
            "haminn-build.json" to ("""{"schema":1,"happId":"$happId","name":"Fork facts test","versionName":"9.0.0",""" +
                """"versionCode":9,"author":"someone-else","maintainer":"someone-else","packagedAt":"2026-09-30T00:00:00Z"}"""),
        ))
        val installed = kotlinx.coroutines.runBlocking {
            app.installer.installZip(ByteArrayInputStream(foreign), null, id, "import",
                expectedReleaseId = promoted.getString("releaseId"))
        }
        val forked = tool("haminn_get_dev_status", JSONObject().put("appId", id))
        assertEquals("forked", forked.getString("state"))
        assertTrue(forked.getBoolean("dirty"))
        assertTrue(forked.getBoolean("baseOutdated"))
        assertFalse(forked.getBoolean("matchesActive"))
        // The two columns a human has to weigh: what this phone verified, and what the packager says.
        assertEquals(installed.releaseId, forked.getJSONObject("activeRelease").getString("releaseId"))
        assertEquals("someone-else",
            forked.getJSONObject("activeRelease").getJSONObject("declared").getString("maintainer"))
    }

    @Test fun haminnUiIdentityCannotBecomeADevelopmentTarget() {
        val error = assertThrows(life.airen.haminn.model.HaminnException::class.java) {
            app.devWorkspaces.requireDevelopableApp("__haminn_store__")
        }
        assertEquals("E_PROTECTED_TARGET", error.code)
    }

    @Test fun globalSessionTargetsAnyHappAndHighLevelHotUpdateWorkflow() {
        val first = create(); val second = create(); val secondId = second.getString("appId")
        val session = tool("haminn_open_agent_session")
        assertEquals("global", session.getString("scope"))
        assertTrue(session.getJSONArray("apps").length() >= 2)
        val prepared = tool("haminn_prepare_happ_development", JSONObject()
            .put("appId", secondId).put("strategy", "resume").put("startRuntimeMode", "dev")
            .put("requestId", UUID.randomUUID().toString()))
        val updated = tool("haminn_hot_update_happ", JSONObject()
            .put("appId", secondId).put("expectedDevRevision", prepared.getLong("revision"))
            .put("requestId", UUID.randomUUID().toString()).put("refreshMode", "none")
            .put("files", JSONArray().put(JSONObject().put("path", "index.html")
                .put("content", "<!doctype html><title>high-level</title>"))))
        assertTrue(updated.getLong("revision") > prepared.getLong("revision"))
        val status = tool("haminn_get_happ_dev_status", JSONObject().put("appId", secondId))
        assertEquals(updated.getLong("revision"), status.getLong("revision"))
        val download = tool("haminn_download_dev_tree", JSONObject().put("appId", secondId))
        assertTrue(download.getString("downloadUrl").contains("/v2/builds/"))
        tool("haminn_switch_happ_runtime_mode", JSONObject().put("appId", secondId)
            .put("runtimeMode", "stable").put("requestId", UUID.randomUUID().toString()))
        assertEquals("stable", app.registry.getInstance(secondId)!!.launchChannel.name.lowercase())
        assertNotEquals(first.getString("appId"), secondId)
    }

    @Test fun passwordRotationDuringUploadPreventsActivation() {
        val created = create(); val id = created.getString("appId"); val release = created.getString("activeReleaseId")
        val zip = ByteArrayOutputStream().also { buffer -> ZipOutputStream(buffer).use {
            it.putNextEntry(ZipEntry("index.html")); it.write("<h1>must not commit</h1>".toByteArray()); it.closeEntry()
        } }.toByteArray()
        val sha = life.airen.haminn.deploy.AgentWorkspace.sha(zip)
        val address = URL(base)
        Socket(address.host, address.port).use { socket ->
            socket.soTimeout = 15000
            val header = "PUT /v1/apps/$id/release HTTP/1.1\r\nHost: ${address.host}:${address.port}\r\nAuthorization: Bearer $password\r\nContent-Type: application/zip\r\nContent-Length: ${zip.size}\r\nX-Haminn-Expected-Release: $release\r\nIdempotency-Key: ${UUID.randomUUID()}\r\nX-Haminn-Content-SHA256: $sha\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray()); socket.getOutputStream().write(zip, 0, 1); socket.getOutputStream().flush()
            val incoming = java.io.File(app.filesDir, "instances/$id/incoming")
            var receiving = false
            for (i in 0 until 50) { if (incoming.listFiles()?.isNotEmpty() == true) { receiving = true; break }; Thread.sleep(20) }
            assertTrue("Upload must be receiving before rotation; server active=" + server.status().optBoolean("active") + "; reason=" + server.lastStopReason + "; response=" + if (receiving) "ready" else socket.getInputStream().bufferedReader().readLine(), receiving)
            server.resetPassword(); password = server.passwordForUi()
            socket.getOutputStream().write(zip, 1, zip.size - 1); socket.getOutputStream().flush()
            val firstLine = socket.getInputStream().bufferedReader().readLine()
            assertFalse(firstLine, firstLine.contains("200 OK"))
        }
        assertEquals(release, app.registry.getInstance(id)!!.activeReleaseId)
    }

    @Test fun enabledServiceSurvivesBackgroundAndRestoresAfterProcessRestart() {
        server.stop("Switch to persistent test")
        base = server.enable("usb", port = 0).getString("address")
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        assertTrue(server.status().getBoolean("enabled"))
        assertTrue(server.status().getBoolean("active"))
        assertTrue(password == server.passwordForUi())
        server.stop("Simulated process termination")
        assertTrue(server.status().getBoolean("enabled"))
        assertFalse(server.status().getBoolean("active"))
        server.restoreIfEnabled()
        assertTrue(server.status().getBoolean("active"))
        assertTrue(password == server.passwordForUi())
    }

    /** A package archive, with or without the declared build record the packaging tools now write. */
    private fun packageZip(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun externalClientInterop() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("externalMcp") == "true")
        server.stop("External interop")
        base = server.start(port = 8766).getString("address")
        val connection = java.io.File(app.filesDir, "agent-interop.json")
        val done = java.io.File(app.filesDir, "agent-interop.done")
        val rotate = java.io.File(app.filesDir, "agent-interop.rotate")
        fun publish() { connection.writeText(JSONObject().put("address", base).put("password", server.passwordForUi()).toString()) }
        try {
            done.delete(); rotate.delete(); publish()
            for (i in 0 until 1200) {
                if (done.exists()) return
                assertTrue("External fixture stopped: " + server.lastStopReason, server.status().optBoolean("active"))
                if (rotate.exists()) { server.resetPassword(); publish(); rotate.delete() }
                Thread.sleep(100)
            }
            fail("External MCP client did not complete in 120 seconds")
        } finally { connection.delete(); done.delete(); rotate.delete() }
    }
}
