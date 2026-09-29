package life.airen.haminn.deploy

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import life.airen.haminn.BuildConfig
import life.airen.haminn.install.InstallCoordinator
import life.airen.haminn.model.HaminnException
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Installed plugin identity. Renamed from the legacy id, which stays accepted while existing installs migrate. */
private const val PLUGIN_ID = "haminn-dev-plugin"
private const val LEGACY_PLUGIN_ID = "haminn-device"

/** An explicitly enabled, process-scoped developer control plane, separate from page RPC authority. */
class AgentDevelopmentServer(
    private val context: Context,
    private val registry: AppRegistry,
    private val installer: InstallCoordinator,
    private val devWorkspaces: DevWorkspaceManager,
    private val scope: CoroutineScope,
) {
    private val workspace = AgentWorkspace(context.cacheDir, registry, installer)
    private val catalogText by lazy { asset("agent/tools.json") }
    private val catalog by lazy { JSONArray(catalogText) }
    private val guidanceVersion by lazy { AgentWorkspace.sha(guide().toByteArray()) }
    private val resourceDigests by lazy {
        JSONObject().put("guide", guidanceVersion)
            .put("tools", AgentWorkspace.sha(catalogText.toByteArray()))
            .put("intentIndex", AgentWorkspace.sha(intentIndex().toString().toByteArray()))
            .put("webappGuide", AgentWorkspace.sha(asset("agent/webapp-authoring.md").toByteArray()))
            .put("pageApi", AgentWorkspace.sha(asset("agent/haminn-api.d.ts").toByteArray()))
    }
    @Volatile private var active: Endpoint? = null
    @Volatile private var uiHandler: (suspend (String, JSONObject) -> JSONObject)? = null
    @Volatile private var uiHandlerOwner: Any? = null
    private data class RenderOperation(
        val id: String, val appId: String, val revision: Long, val createdAt: Long,
        val result: CompletableDeferred<JSONObject> = CompletableDeferred(),
    )
    private val renderOperations = ConcurrentHashMap<String, RenderOperation>()
    private val devDiagnostics = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()
    @Volatile var lastStopReason = "Not started"
        private set
    private var monitor: Job? = null
    @Volatile private var networkAvailable = false
    @Volatile private var stateHandler: ((Boolean) -> Unit)? = null
    @Volatile private var stateHandlerOwner: Any? = null
    @Synchronized fun setStateHandler(owner: Any, handler: (Boolean) -> Unit) {
        stateHandlerOwner = owner
        stateHandler = handler
        handler(enabled())
    }
    @Synchronized fun clearStateHandler(owner: Any) {
        if (stateHandlerOwner !== owner) return
        stateHandlerOwner = null
        stateHandler = null
    }
    private val preferences = context.getSharedPreferences("agent-development", Context.MODE_PRIVATE)

    private fun enabled() = preferences.getBoolean("enabled", false)
    private fun configuredMode() = preferences.getString("mode", "lan").takeIf { it == "usb" } ?: "lan"
    private fun configuredPort() = preferences.getInt("port", DEFAULT_PORT).takeIf { it in 0..65535 } ?: DEFAULT_PORT

    @Synchronized fun passwordForUi(): String {
        val saved = preferences.getString("password", null)
        if (saved != null && saved.matches(PASSWORD_PATTERN)) return saved
        return newPassword(null)
    }

    private fun newPassword(previous: String?): String {
        var value: String
        do { value = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0') } while (value == previous)
        check(preferences.edit().putString("password", value).commit()) { "Unable to save password" }
        return value
    }

    @Synchronized fun resetPassword(requested: String? = null): JSONObject {
        if (requested != null && !requested.matches(PASSWORD_PATTERN)) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "密码必须是 6 位数字或大小写字母")
        }
        if (requested == null) newPassword(passwordForUi())
        else check(preferences.edit().putString("password", requested).commit()) { "Unable to save password" }
        return status()
    }

    private fun authorized(header: String) = constantEquals("Bearer " + passwordForUi(), header)

    @Synchronized fun setUiHandler(owner: Any, handler: suspend (String, JSONObject) -> JSONObject) {
        uiHandlerOwner = owner
        uiHandler = handler
    }

    @Synchronized fun clearUiHandler(owner: Any) {
        if (uiHandlerOwner !== owner) return
        uiHandlerOwner = null
        uiHandler = null
    }

    fun reportDevRender(appId: String, revision: Long, url: String) {
        renderOperations.values.filter { it.appId == appId && it.revision == revision && !it.result.isCompleted }.forEach { operation ->
            operation.result.complete(JSONObject().put("state", "rendered").put("operationId", operation.id)
                .put("appId", appId).put("revision", revision).put("url", url).put("renderedAt", System.currentTimeMillis()))
        }
    }

    fun recordDevDiagnostic(appId: String, revision: Long?, kind: String, message: String, url: String?) {
        val events = devDiagnostics.computeIfAbsent(appId) { ArrayDeque() }
        synchronized(events) {
            if (events.size >= 100) events.removeFirst()
            events.addLast(JSONObject().put("kind", kind).put("message", message.take(2048))
                .put("revision", revision ?: JSONObject.NULL).put("url", url?.take(2048) ?: JSONObject.NULL)
                .put("time", System.currentTimeMillis()))
        }
    }
    fun addresses(): List<String> = runCatching {
        val networks = context.getSystemService(ConnectivityManager::class.java)
        val interfaces = networks.allNetworks.mapNotNull { network ->
            val capabilities = networks.getNetworkCapabilities(network) ?: return@mapNotNull null
            val name = networks.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
            name to (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val lan = interfaces.filter { it.second }.map { it.first }.toSet()
        val hotspotPrefixes = listOf("wlan", "swlan", "ap", "eth", "en", "rndis")
        val blockedPrefixes = listOf("rmnet", "ccmni", "pdp", "wwan", "tun", "dummy")
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { network ->
                val name = network.name.lowercase()
                network.isUp && !network.isLoopback && !network.isPointToPoint &&
                    blockedPrefixes.none(name::startsWith) &&
                    (network.name in lan || hotspotPrefixes.any(name::startsWith))
            }
            .sortedBy { if (it.name in lan) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    @Synchronized fun start(bindAddress: String? = null, port: Int = 8766): JSONObject {
        val host = bindAddress ?: addresses().firstOrNull() ?: throw IllegalStateException("没有可用的局域网 IPv4 地址，请连接 Wi-Fi")
        if (host != "127.0.0.1" && host !in addresses()) throw IllegalArgumentException("Selected LAN address is unavailable")
        passwordForUi()
        stop("Replaced")
        val bindHost = if (host == "127.0.0.1") host else "0.0.0.0"
        val endpoint = Endpoint(if (host == "127.0.0.1") "usb" else "lan", bindHost, host, port)
        try { endpoint.start(15_000, false); active = endpoint } catch (error: Exception) { endpoint.stop(); throw error }
        networkAvailable = true
        stateHandler?.invoke(true)
        return status()
    }

    @Synchronized fun enable(mode: String, bindAddress: String? = null, port: Int = DEFAULT_PORT): JSONObject {
        require(mode == "lan" || mode == "usb") { "Unsupported developer connection mode" }
        check(preferences.edit().putBoolean("enabled", true).putString("mode", mode).putInt("port", port).commit()) {
            "Unable to save developer service setting"
        }
        reconcileSafelyLocked("开发模式已开启", bindAddress)
        ensureMonitorLocked()
        stateHandler?.invoke(true)
        return status()
    }

    fun restoreIfEnabled() {
        synchronized(this) {
            if (!enabled()) return
            reconcileSafelyLocked("应用已重新启动")
            ensureMonitorLocked()
            stateHandler?.invoke(true)
        }
    }

    @Synchronized fun refreshNetwork(): JSONObject {
        if (enabled()) reconcileSafelyLocked("已刷新开发服务地址")
        return status()
    }

    @Synchronized fun disable(reason: String): JSONObject {
        check(preferences.edit().putBoolean("enabled", false).commit()) { "Unable to save developer service setting" }
        return stop(reason)
    }

    private fun ensureMonitorLocked() {
        if (monitor?.isActive == true) return
        monitor = scope.launch {
            while (isActive) {
                delay(NETWORK_MONITOR_MS)
                synchronized(this@AgentDevelopmentServer) {
                    if (!enabled()) return@launch
                    reconcileSafelyLocked("设备网络已变化")
                }
            }
        }
    }

    private fun reconcileSafelyLocked(reason: String, requestedAddress: String? = null) {
        try { reconcileLocked(reason, requestedAddress) }
        catch (error: Exception) {
            networkAvailable = false
            lastStopReason = "开发模式保持开启，服务恢复失败：${error.message ?: "端口不可用"}"
            recordEndpointObservation(null, false, lastStopReason)
        }
    }

    private fun reconcileLocked(reason: String, requestedAddress: String? = null) {
        val mode = configuredMode()
        val currentAddresses = if (mode == "lan") addresses() else emptyList()
        val host = if (mode == "usb") "127.0.0.1" else requestedAddress?.takeIf { it in currentAddresses } ?: currentAddresses.firstOrNull()
        var endpoint = active
        if (endpoint == null || endpoint.mode != mode) {
            endpoint?.stop()
            endpoint = startPersistentEndpointLocked(mode, host, configuredPort())
        }
        val available = mode == "usb" || host != null
        val advertisedHost = if (available) host!! else "127.0.0.1"
        endpoint.updateAdvertisedHost(advertisedHost)
        networkAvailable = available
        lastStopReason = if (available) "Running" else "开发模式保持开启，等待 Wi-Fi 或手机热点"
        recordEndpointObservation(if (available) endpoint.address else null, available, reason)
    }

    private fun startPersistentEndpointLocked(mode: String, host: String?, port: Int): Endpoint {
        val bindHost = if (mode == "usb") "127.0.0.1" else "0.0.0.0"
        val advertisedHost = host ?: "127.0.0.1"
        fun bind(value: Int): Endpoint {
            val endpoint = Endpoint(mode, bindHost, advertisedHost, value)
            try { endpoint.start(15_000, false) } catch (error: Exception) { endpoint.stop(); throw error }
            return endpoint
        }
        val endpoint = try { bind(port) } catch (error: Exception) {
            if (port == 0) throw error
            bind(0)
        }
        active = endpoint
        return endpoint
    }

    private fun recordEndpointObservation(address: String?, available: Boolean, reason: String) {
        val previousAddress = preferences.getString("observedAddress", null)
        val lastUsableAddress = preferences.getString("lastAddress", null)
        val previousAvailable = preferences.getBoolean("lastAvailable", false)
        val firstObservation = !preferences.contains("lastAvailable") && previousAddress == null && lastUsableAddress == null
        val changed = !firstObservation && (previousAddress != address || previousAvailable != available)
        val editor = preferences.edit().putBoolean("lastAvailable", available)
        if (address == null) editor.remove("observedAddress")
        else editor.putString("observedAddress", address).putString("lastAddress", address)
        if (changed) {
            val revision = preferences.getLong("changeRevision", 0L) + 1L
            editor.putLong("changeRevision", revision)
                .putString("changePreviousAddress", previousAddress ?: lastUsableAddress)
                .putString("changeAddress", address)
                .putBoolean("changeAvailable", available)
                .putString("changeReason", reason)
                .putLong("changeTime", System.currentTimeMillis())
        }
        editor.apply()
    }

    @Synchronized fun stop(reason: String): JSONObject {
        val previous = active; active = null; monitor?.cancel(); monitor = null
        previous?.stop()
        networkAvailable = false
        stateHandler?.invoke(enabled())
        renderOperations.values.forEach { if (!it.result.isCompleted) it.result.cancel() }
        renderOperations.clear()
        devDiagnostics.clear()
        lastStopReason = reason
        return JSONObject().put("stopped", previous != null)
    }

    @Synchronized fun status(): JSONObject {
        val endpoint = active
        val addresses = addresses()
        val result = JSONObject().put("enabled", enabled()).put("active", endpoint != null)
            .put("networkAvailable", networkAvailable).put("mode", configuredMode())
            .put("persistent", true).put("reason", lastStopReason)
            .put("password", passwordForUi()).put("addresses", JSONArray(addresses))
            .put("monitorIntervalSeconds", NETWORK_MONITOR_MS / 1000)
        if (endpoint != null) {
            if (networkAvailable) result.put("address", endpoint.address)
            result.put("serverVersion", BuildConfig.VERSION_NAME).put("runId", endpoint.runId)
                .put("usbAddress", "http://127.0.0.1:${endpoint.listeningPort}")
                .put("usbCommand", "adb forward tcp:${endpoint.listeningPort} tcp:${endpoint.listeningPort}")
        }
        val revision = preferences.getLong("changeRevision", 0L)
        if (revision > 0) result.put("endpointChange", JSONObject().put("revision", revision)
            .put("previousAddress", preferences.getString("changePreviousAddress", null))
            .put("address", preferences.getString("changeAddress", null))
            .put("available", preferences.getBoolean("changeAvailable", false))
            .put("reason", preferences.getString("changeReason", "设备网络已变化"))
            .put("time", preferences.getLong("changeTime", 0L)))
        return result
    }

    private fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
    private val guideText by lazy { asset("agent/$PLUGIN_ID/SKILL.md") }
    private val webappGuideText by lazy { asset("agent/webapp-authoring.md") }
    private val pageApiText by lazy { asset("agent/haminn-api.d.ts") }
    private fun guide() = guideText
    private fun toolIndex() = JSONArray().apply {
        for (index in 0 until catalog.length()) {
            val tool = catalog.getJSONObject(index)
            put(JSONObject().put("name", tool.getString("name"))
                .put("description", tool.optString("description")))
        }
    }
    private fun intentIndex() = JSONArray().apply {
        put(JSONObject().put("intent", "connect").put("description", "恢复设备级连接并缓存能力说明").put("recommended", JSONArray(listOf("haminn_open_agent_session"))))
        put(JSONObject().put("intent", "target").put("description", "列出或读取任意已安装 happ").put("recommended", JSONArray(listOf("haminn_list_installed_happs", "haminn_get_happ_dev_status"))))
        put(JSONObject().put("intent", "prepare").put("description", "恢复或重新初始化指定 happ 的开发工作区").put("recommended", JSONArray(listOf("haminn_prepare_happ_development"))))
        put(JSONObject().put("intent", "hot-update").put("description", "原子提交变更并自动刷新开发页面").put("recommended", JSONArray(listOf("haminn_hot_update_happ"))))
        put(JSONObject().put("intent", "page").put("description", "读取页面状态或按相对路由刷新当前页面").put("recommended", JSONArray(listOf("haminn_get_page_state", "haminn_refresh_happ_page"))))
        put(JSONObject().put("intent", "device-tree").put("description", "下载设备端开发树供开发端自行判断").put("recommended", JSONArray(listOf("haminn_download_dev_tree"))))
        put(JSONObject().put("intent", "stage-release").put("description", "把 DEV 树阶段性安装为版本并继续 DEV").put("recommended", JSONArray(listOf("haminn_promote_dev_release"))))
        put(JSONObject().put("intent", "release").put("description", "上传正式 ZIP 并明确切换 stable").put("recommended", JSONArray(listOf("haminn_install_happ_release", "haminn_switch_happ_runtime_mode"))))
    }
    private fun resource(uri: String) = when (uri) {
        "haminn://guide" -> guide()
        "haminn://intent-index" -> intentIndex().toString()
        "haminn://tool-index" -> toolIndex().toString()
        "haminn://tools" -> catalogText
        "haminn://webapp-guide" -> webappGuideText
        "haminn://page-api" -> pageApiText
        else -> uri.removePrefix("haminn://tool/").takeIf { uri.startsWith("haminn://tool/") && it.matches(Regex("[a-zA-Z0-9_-]+")) }
            ?.let { name -> (0 until catalog.length()).asSequence().map { catalog.getJSONObject(it) }.firstOrNull { it.optString("name") == name }?.toString() }
            ?: throw RpcError(-32602, "Unknown resource URI")
    }

    private fun buildPluginPackage(baseAddress: String): ByteArray {
        val codexVersion = "${BuildConfig.VERSION_NAME}+codex.${AgentWorkspace.sha(baseAddress.toByteArray()).take(12)}"
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val manifest = JSONObject().put("kind", "haminn-agent-plugin")
                .put("protocol", 1).put("id", PLUGIN_ID).put("version", BuildConfig.VERSION_NAME)
                .put("codexVersion", codexVersion)
                .put("replaceScope", "$PLUGIN_ID-only")
                .put("replaces", JSONArray(listOf(LEGACY_PLUGIN_ID)))
                .put("packageFormat", "codex-plugin-archive-v1")
                .put("files", JSONArray(listOf(
                    "manifest.json", ".codex-plugin/plugin.json",
                    "SKILL.md", "skills/$PLUGIN_ID/SKILL.md", "haminn-agent.py", "install.md"
                )))
            val codexManifest = JSONObject()
                .put("name", PLUGIN_ID)
                .put("version", codexVersion)
                .put("description", "Develop runnable Haminn happs on an authorized Android device.")
                .put("author", JSONObject().put("name", "Haminn"))
                .put("license", "UNLICENSED")
                .put("keywords", JSONArray(listOf("haminn", "happ", "android", "mcp")))
                .put("skills", "./skills/")
                .put("interface", JSONObject()
                    .put("displayName", "Haminn Device")
                    .put("shortDescription", "Develop and deploy runnable Haminn happs on your phone.")
                    .put("longDescription", "Connect to the Haminn development service, edit any installed happ, hot update it, and promote stable releases when requested.")
                    .put("developerName", "Haminn")
                    .put("category", "Developer Tools")
                    .put("capabilities", JSONArray(listOf("Write", "Interactive")))
                    .put("defaultPrompt", JSONArray(listOf("Prepare the selected Haminn happ for local development."))))
            val files = linkedMapOf(
                "manifest.json" to manifest.toString(2),
                ".codex-plugin/plugin.json" to codexManifest.toString(2),
                "SKILL.md" to guide(),
                "skills/$PLUGIN_ID/SKILL.md" to guide(),
                "haminn-agent.py" to asset("agent/haminn-agent.py"),
                "install.md" to "This is a Haminn agent bundle. Install atomically at ~/plugins/$PLUGIN_ID, verify packageSha256, then run haminn-agent.py --address <address> connect once so the six-character password is stored privately. All device work then runs through that helper's command line; this bundle registers no MCP server. It supersedes the legacy $LEGACY_PLUGIN_ID installation of the same product."
            )
            files.forEach { (name, content) ->
                val entry = ZipEntry(name).apply { time = 0L }
                zip.putNextEntry(entry); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private class RpcError(val code: Int, override val message: String) : RuntimeException(message)

    private inner class Endpoint(val mode: String, private val bindHost: String, advertisedHost: String, port: Int) : NanoHTTPD(bindHost, port) {
        @Volatile private var advertisedHost = advertisedHost
        val runId: String = UUID.randomUUID().toString()
        val address get() = "http://$advertisedHost:$listeningPort"
        @Volatile private var packageCache: Pair<String, ByteArray>? = null
        private fun pluginPackage(): ByteArray {
            val currentAddress = address
            packageCache?.takeIf { it.first == currentAddress }?.let { return it.second }
            return synchronized(this) {
                packageCache?.takeIf { it.first == currentAddress }?.second
                    ?: buildPluginPackage(currentAddress).also { packageCache = currentAddress to it }
            }
        }
        private fun pluginSha256() = AgentWorkspace.sha(pluginPackage())
        fun updateAdvertisedHost(value: String) { advertisedHost = value }
        private val receipts = LinkedHashMap<String, Pair<String, JSONObject>>()
        private val writes = ConcurrentHashMap<String, Semaphore>()
        private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
        @Volatile var lastAccess = SystemClock.elapsedRealtime()
        init { setAsyncRunner(BoundedAsyncRunner(4)) }
        private fun live() { if (active !== this) throw IllegalStateException("Developer session stopped") }
        private fun live(authorization: String) { live(); check(authorized(authorization)) { "Password changed; reconnect with the current password" } }
        private fun guarded(authorization: String, action: () -> Unit) = synchronized(this@AgentDevelopmentServer) { live(authorization); action() }
        private fun requireApp(id: String) {
            if (registry.getInstance(id) == null) throw IllegalArgumentException("App not found")
        }

        override fun serve(session: IHTTPSession): Response {
            try {
                if (active !== this) return httpError(503, "Developer session stopped")
                if (session.headers["host"] !in setOf("$advertisedHost:$listeningPort", "127.0.0.1:$listeningPort", "localhost:$listeningPort")) return httpError(400, "Invalid Host")
                if (session.headers["origin"] != null) return httpError(403, "Browser-origin API requests are not accepted")
                if (session.headers.entries.sumOf { it.key.length + it.value.length } > 16384) return httpError(431, "Headers too large")
                val peer = session.remoteIpAddress
                if (session.method == Method.GET) when (session.uri) {
                    "/" -> return bootstrapResponse(session)
                    "/connect" -> return text(200, "text/plain", connectionGuide())
                    "/.well-known/haminn-agent" -> return json(200, discovery())
                    "/skills/$PLUGIN_ID/SKILL.md", "/skills/$LEGACY_PLUGIN_ID/SKILL.md" -> return text(200, "text/markdown", guide())
                    "/haminn-agent.py" -> return text(200, "text/x-python", asset("agent/haminn-agent.py"))
                    "/plugin/$PLUGIN_ID", "/plugin/$LEGACY_PLUGIN_ID" -> return binary(200, "application/zip", pluginPackage(), "$PLUGIN_ID.zip")
                }
                val authorization = session.headers["authorization"]
                if (authorization.isNullOrBlank()) return authenticationRequired()
                val now = SystemClock.elapsedRealtime()
                val failure = failures[peer]
                if (failure != null && failure.first >= 5 && now - failure.second < 60_000)
                    return json(429, JSONObject().put("error", "authentication_rate_limited")
                        .put("reason", "ip_locked").put("nextAction", "wait")
                        .put("message", "Haminn 开发服务已暂时锁定当前地址，请等待后再试。")
                        .put("retryAfter", 60).put("instructionsUrl", "$address/connect"), close = true).apply { addHeader("Retry-After", "60") }
                if (!authorized(authorization)) {
                    synchronized(failures) {
                        failures.entries.removeIf { now - it.value.second >= 60_000 }
                        if (failures.size >= 256 && !failures.containsKey(peer)) return httpError(429, "Authentication temporarily rate limited")
                        val previous = failures[peer]
                        failures[peer] = ((previous?.first ?: 0) + 1) to (previous?.second ?: now)
                    }
                    return authenticationRequired("invalid_credentials")
                }
                failures.remove(peer)
                lastAccess = SystemClock.elapsedRealtime()
                if (session.uri == "/mcp") {
                    if (session.method !in setOf(Method.POST)) return httpError(405, "Only MCP POST is supported; no SSE stream").apply { addHeader("Allow", "POST") }
                    val protocol = session.headers["mcp-protocol-version"]
                    if (protocol != null && protocol !in PROTOCOLS) return httpError(400, "Unsupported MCP-Protocol-Version")
                    val accept = session.headers["accept"].orEmpty()
                    if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return httpError(406, "Accept must include application/json and text/event-stream")
                    val request = body(session, MAX_RPC)
                    if (protocol == MODERN_PROTOCOL) validateModernHeaders(session, request)
                    return rpc(authorization, request, protocol == MODERN_PROTOCOL)
                }
                if (session.method == Method.GET && session.uri.matches(Regex("/v2/builds/[a-zA-Z0-9-]+"))) return downloadBuild(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v2/apps/[a-zA-Z0-9-]+/dev/file"))) return uploadDevFile(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v2/apps/[a-zA-Z0-9-]+/dev/tree"))) return uploadDevTree(authorization, session)
                if (session.method == Method.PUT && session.uri.matches(Regex("/v1/apps/[a-zA-Z0-9-]+/release"))) return upload(authorization, session)
                return httpError(404, "Not found")
            } catch (error: RpcError) { return json(400, rpcError(JSONObject.NULL, error.code, error.message)) }
            catch (error: Exception) { return httpError(400, error.message?.take(300) ?: "Invalid request") }
        }

        private fun discovery() = bootstrap()

        private fun bootstrapResponse(session: IHTTPSession): Response {
            val accept = session.headers["accept"].orEmpty().lowercase()
            return if (accept.contains("text/html") && !accept.contains("application/json")) {
                text(200, "text/html", bootstrapHtml())
            } else json(200, bootstrap()).apply {
                addHeader("Link", "<$address/.well-known/haminn-agent>; rel=\"service-desc\", <$address/plugin/$PLUGIN_ID>; rel=\"plugin\"")
            }
        }

        private fun bootstrap() = JSONObject().put("kind", "haminn-agent-bootstrap")
            .put("protocol", 1).put("product", "Haminn").put("schema", 3)
            .put("serverVersion", BuildConfig.VERSION_NAME).put("runId", runId)
            .put("protocolVersions", JSONArray(PROTOCOLS))
            .put("packageFormat", "codex-plugin-archive-v1")
            .put("plugin", JSONObject().put("id", PLUGIN_ID).put("version", BuildConfig.VERSION_NAME)
                .put("codexVersion", "${BuildConfig.VERSION_NAME}+codex.${AgentWorkspace.sha(address.toByteArray()).take(12)}")
                .put("displayName", "Haminn happ development"))
            .put("install", JSONObject().put("action", "install_or_update").put("requiresUserConfirmation", true)
                .put("packageUrl", "$address/plugin/$PLUGIN_ID").put("packageFormat", "codex-plugin-archive-v1")
                .put("packageSha256", pluginSha256()).put("replaceScope", "$PLUGIN_ID-only")
                .put("replaces", JSONArray(listOf(LEGACY_PLUGIN_ID)))
                .put("target", "~/plugins/$PLUGIN_ID")
                .put("strategy", "atomic_replace_if_hash_differs")
                .put("existingSameVersion", "no_op")
                .put("installer", JSONObject()
                    .put("url", "$address/haminn-agent.py")
                    .put("sha256", AgentWorkspace.sha(asset("agent/haminn-agent.py").toByteArray()))
                    .put("downloadName", "haminn-agent.py")
                    .put("commands", JSONObject()
                        .put("posix", JSONArray(listOf("python3", "<downloadedHelper>", "--address", address,
                            "install-plugin", "--package-url", "$address/plugin/$PLUGIN_ID",
                            "--package-sha256", pluginSha256(), "--plugin-version", BuildConfig.VERSION_NAME)))
                        .put("windows", JSONArray(listOf("py", "-3", "<downloadedHelper>", "--address", address,
                            "install-plugin", "--package-url", "$address/plugin/$PLUGIN_ID",
                            "--package-sha256", pluginSha256(), "--plugin-version", BuildConfig.VERSION_NAME)))))
                .put("authenticateCommand", JSONArray(listOf("<python>", "<pluginDir>/haminn-agent.py", "--address", address, "connect")))
                .put("afterInstall", "authenticate")
                .put("fallback", JSONObject().put("skillUrl", "$address/skills/$PLUGIN_ID/SKILL.md")
                    .put("helperUrl", "$address/haminn-agent.py")
                    .put("helperSha256", AgentWorkspace.sha(asset("agent/haminn-agent.py").toByteArray()))))
            .put("recovery", JSONObject()
                .put("addressUnavailable", JSONObject().put("nextAction", "ask_user_for_current_address")
                    .put("message", "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发服务地址。"))
                .put("passwordInvalid", JSONObject().put("nextAction", "ask_user_for_current_password")
                    .put("message", "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发密码。")))
            .put("clientContract", JSONObject()
                .put("firstRequest", JSONObject().put("method", "GET").put("path", "/")
                    .put("headers", JSONObject().put("Accept", "application/json")))
                .put("doNot", JSONArray(listOf("read workspace instructions", "search repositories", "open a browser", "scan the LAN", "guess passwords")))
                .put("successStates", JSONArray(listOf("installed", "authenticated"))))
            .put("bootstrapInstruction", "安装或更新 $PLUGIN_ID（它取代旧的 $LEGACY_PLUGIN_ID 安装），首次用 install.authenticateCommand 私下录入密码，之后按插件说明用 haminn-agent.py 的命令行准备本地 happ 开发。本插件不注册任何 MCP 服务，也不需要连接器。")
            .put("discoveryInstruction", "Read GET /.well-known/haminn-agent for the same cached Bootstrap contract.")
            .put("guidanceVersion", guidanceVersion).put("schemaDigest", AgentWorkspace.sha(catalogText.toByteArray()))
            .put("resourceDigests", resourceDigests).put("intentIndex", intentIndex()).put("toolIndex", toolIndex())
            .put("limits", JSONObject().put("rpcBytes", MAX_RPC).put("zipBytes", MAX_ZIP)
                .put("textFileBytes", 512 * 1024).put("binaryFileBytes", 64 * 1024 * 1024))

        private fun bootstrapHtml() = """
            <!doctype html><meta charset=\"utf-8\"><title>Haminn 智能体开发插件</title>
            <h1>Haminn 智能体开发插件</h1>
            <p>把这个地址提供给智能体：它从这里安装或更新 Haminn 开发插件，然后用插件自带的命令行工具开发本机上的 happ。</p>
            <p>插件：$PLUGIN_ID ${BuildConfig.VERSION_NAME}</p>
            <p>开发密码：请在手机打开 Haminn 应用，在“开发配置”中查看。</p>
            <p>手机网络变化或密码失效时，也请在“开发配置”中获取当前地址和密码。</p>
        """.trimIndent()

        private fun connectionGuide() = """
            Haminn ${BuildConfig.VERSION_NAME} agent development connection
            Address: $address
            Read GET / with Accept: application/json to obtain the haminn-agent-bootstrap contract. Install or update $PLUGIN_ID from install.packageUrl, verify its packageSha256, then run its helper once with the authenticateCommand. /.well-known/haminn-agent returns the same contract for cache refresh. Fetch the short skill only when guidanceVersion changes. Read haminn://tool-index and one haminn://tool/TOOL_NAME schema on demand; do not load full schemas unnecessarily.
            This is a trusted-LAN HTTP service, not an encrypted Internet endpoint.
            Ask the user for the current six-character password displayed in Haminn. No pairing or per-computer identity.
            The one persistent password authorizes all exposed developer tools and all apps on any computer.
            Changing the password invalidates old credentials on the next request, including existing HTTP connections.
            Optional standalone Python helper: GET /haminn-agent.py. Inspect it before running. It needs Python 3.10+ standard library only.
            The helper works on Windows, macOS and Linux; no files from the developer's computer are assumed.
            python3 haminn-agent.py --address $address connect
            Windows launcher alternative: py -3 haminn-agent.py --address $address connect
            Continuous local development: python3 haminn-agent.py --address $address develop-dir /path/to/happ --quiet
            One-shot local preparation: python3 haminn-agent.py --address $address prepare-dir /path/to/happ
            Stable device upgrade: python3 haminn-agent.py --address $address update-dir /path/to/happ --bump patch
            python3 haminn-agent.py --address $address install-plugin
            The helper is the only development entry point; this service registers no MCP server and needs no connector. A successful helper call already proves the global development service is enabled; never check that switch again. For a local happ directory, prefer the helper's develop-dir command: it opens a global session, reads the target dev version, asks for an explicit whole-tree policy, then sends later changes with atomic hot updates and state-preserving refresh. Use update-dir only for an explicit stable device upgrade; it preserves the original instance and data through the standard release transaction. The lower-level prepare-dir, sync-dir and watch commands remain available. Before a direct write cycle call haminn_get_happ_dev_status for the selected target. HaminnUI is protected and never exposed as a development target.
            No frontend build or framework support is needed. Author native HTML + JS + CSS; other tools' finished static output is accepted neutrally.
            Optimize for fast iteration: make focused edits, run only the smallest directly relevant technical check, then sync and refresh immediately. Do not default to full-suite tests, release packaging, screenshots or visual inspection unless the change or user requires them.
            Android 10 / API 29 devices with older vendor WebViews are the recommended compatibility baseline unless the task chooses a newer target. Android has no fixed "WebView 10": prefer older-compatible JavaScript syntax or transpiled output, feature-detect newer browser APIs, and provide fallbacks. For Android system abilities, use only capabilities HaminnApp supports through its public Bridge, query availability first, and treat anything absent from the Bridge as unavailable rather than calling undocumented Native or vendor interfaces. This is agent guidance only; HaminnApp does not scan, certify or reject happ code for these choices. Keep any compatibility check focused rather than expanding each edit into a full test.
            The developer switch persists across app restarts. Wi-Fi changes update the advertised address without disabling developer mode; use the current address shown by Haminn.
        """.trimIndent()

        private fun authenticationRequired(error: String = "authentication_required") = json(401, JSONObject()
            .put("error", "authentication_required")
            .put("reason", if (error == "invalid_credentials") "invalid_credentials" else "missing_credentials")
            .put("nextAction", "ask_user_for_current_password")
            .put("message", "请在手机打开 Haminn 应用，在开发配置中查看并提供当前开发密码。")
            .put("discoveryUrl", "$address/.well-known/haminn-agent")
            .put("instructionsUrl", "$address/connect")
            .put("authentication", "Authorization: Bearer <current six-character password>"), close = true)
            .apply {
                addHeader("WWW-Authenticate", "Bearer realm=\"Haminn developer\"")
                addHeader("Link", "<$address/.well-known/haminn-agent>; rel=\"service-desc\", <$address/connect>; rel=\"help\"")
            }

        private fun conciseServerInstructions() = """
            Authenticate with the password shown by HaminnApp; a successful request proves the development service is enabled.
            Cache the short guide by guidanceVersion and tools/list by schemaDigest. Read page-authoring and Bridge resources only when the task needs them.
            For a local directory prefer haminn-agent.py develop-dir: it prepares once, keeps one authenticated global session and watches incremental saves after an explicit whole-tree choice. Use update-dir only for an explicit stable upgrade; version labels never decide development synchronization.
            Direct callers should check haminn_runtime_status once, enter DEV only when needed, batch writes with expectedDevRevision and a fresh requestId, and wait for render only after a returned renderOperationId.
            Preserve app identity, data and grants. Do not publish, reset, capture the screen or expand validation without task authority. Server ${BuildConfig.VERSION_NAME}; guide $guidanceVersion.
        """.trimIndent()


        private fun capabilities() = JSONObject().put("tools", JSONObject()).put("resources", JSONObject()).put("prompts", JSONObject())

        private fun validateModernHeaders(session: IHTTPSession, request: JSONObject) {
            val method = request.optString("method")
            if (session.headers["mcp-method"] != method) throw RpcError(-32020, "Mcp-Method header does not match the request")
            val expectedName = when (method) {
                "tools/call", "prompts/get" -> request.optJSONObject("params")?.optString("name")
                "resources/read" -> request.optJSONObject("params")?.optString("uri")
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (expectedName != null && session.headers["mcp-name"] != expectedName) {
                throw RpcError(-32020, "Mcp-Name header does not match the request")
            }
            if (expectedName == null && session.headers["mcp-name"] != null) {
                throw RpcError(-32020, "Mcp-Name is not valid for this request")
            }
        }

        private fun rpc(authorization: String, request: JSONObject, modern: Boolean): Response {
            val id = request.opt("id") ?: JSONObject.NULL
            try {
                if (request.optString("jsonrpc") != "2.0" || request.opt("method") !is String || (request.has("id") && id !is String && id !is Number)) throw RpcError(-32600, "Invalid JSON-RPC request")
                val method = request.getString("method")
                if (!request.has("id")) {
                    if (!method.startsWith("notifications/")) throw RpcError(-32600, "Only notifications may omit id")
                    return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "").apply {
                        setKeepAlive(true)
                        addHeader("Cache-Control", "no-store")
                    }
                }
                val params = if (!request.has("params")) JSONObject() else request.optJSONObject("params") ?: throw RpcError(-32602, "params must be an object")
                val result: JSONObject = when (method) {
                    "server/discover" -> JSONObject().put("supportedVersions", JSONArray(PROTOCOLS))
                        .put("capabilities", capabilities()).put("instructions", conciseServerInstructions())
                        .put("ttlMs", 60_000).put("cacheScope", "private")
                    "initialize" -> {
                        if (modern) throw RpcError(-32601, "initialize is not used by MCP $MODERN_PROTOCOL")
                        val protocol = params.optString("protocolVersion")
                        if (protocol.isBlank() || params.optJSONObject("clientInfo") == null || params.optJSONObject("capabilities") == null) throw RpcError(-32602, "protocolVersion, clientInfo and capabilities required")
                        val selected = protocol.takeIf { it in LEGACY_PROTOCOLS } ?: LEGACY_PROTOCOLS.first()
                        JSONObject().put("protocolVersion", selected)
                            .put("capabilities", capabilities())
                            .put("serverInfo", JSONObject().put("name", PLUGIN_ID).put("version", BuildConfig.VERSION_NAME))
                            .put("instructions", conciseServerInstructions())
                    }
                    "ping" -> JSONObject()
                    "tools/list" -> { noCursor(params); JSONObject().put("tools", catalog).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "tools/call" -> callTool(authorization, params)
                    "resources/list" -> { noCursor(params); JSONObject().put("resources", JSONArray(listOf("guide", "intent-index", "tool-index", "webapp-guide", "page-api").map { JSONObject().put("uri", "haminn://$it").put("name", it).put("mimeType", if (it == "intent-index" || it == "tool-index") "application/json" else "text/plain") })).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "resources/read" -> {
                        val uri = params.optString("uri")
                        val mime = if (uri == "haminn://intent-index" || uri == "haminn://tool-index" || uri.startsWith("haminn://tool/")) "application/json" else "text/plain"
                        JSONObject().put("contents", JSONArray().put(JSONObject().put("uri", uri).put("mimeType", mime).put("text", resource(uri)))).put("ttlMs", 60_000).put("cacheScope", "private")
                    }
                    "resources/templates/list" -> JSONObject().put("resourceTemplates", JSONArray().put(JSONObject()
                        .put("uriTemplate", "haminn://tool/{name}").put("name", "tool-schema")
                        .put("description", "Detailed schema for one Haminn tool").put("mimeType", "application/json")))
                        .put("ttlMs", 60_000).put("cacheScope", "private")
                    "prompts/list" -> { noCursor(params); JSONObject().put("prompts", JSONArray().put(JSONObject().put("name", "develop-webapp").put("description", "Current Haminn native page development workflow"))).put("ttlMs", 60_000).put("cacheScope", "private") }
                    "prompts/get" -> { if (params.optString("name") != "develop-webapp") throw RpcError(-32602, "Unknown prompt"); JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONObject().put("type", "text").put("text", guide())))) }
                    else -> throw RpcError(-32601, "Method not found")
                }
                if (modern) {
                    result.put("resultType", "complete")
                    result.put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo",
                        JSONObject().put("name", PLUGIN_ID).put("version", BuildConfig.VERSION_NAME)))
                }
                return json(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
            } catch (error: RpcError) { return json(200, rpcError(id, error.code, error.message)) }
            catch (error: Exception) { return json(200, rpcError(id, -32603, "Request failed")) }
        }

        private fun callTool(authorization: String, params: JSONObject): JSONObject {
            val name = params.optString("name")
            val definition = (0 until catalog.length()).map { catalog.getJSONObject(it) }
                .find { it.getString("name") == name } ?: throw RpcError(-32602, "Unknown tool")
            val args = if (!params.has("arguments")) JSONObject() else params.optJSONObject("arguments")
                ?: throw RpcError(-32602, "arguments must be an object")
            validate(args, definition.getJSONObject("inputSchema"), "arguments")
            val readOnly = definition.getJSONObject("annotations").getBoolean("readOnlyHint")
            val appId = args.optString("appId").takeIf { it.isNotBlank() }
            val writeGate = if (readOnly) null else writes.computeIfAbsent(appId ?: "__device__") { Semaphore(1) }
            var acquired = false
            try {
                live(authorization)
                if (appId != null) {
                    if (name in DEV_WORKSPACE_TOOLS) devWorkspaces.requireDevelopableApp(appId) else requireApp(appId)
                }
                if (writeGate != null) {
                    acquired = writeGate.tryAcquire()
                    if (!acquired) throw HaminnException(ErrorCodes.CONFLICT, "This happ already has a write in progress")
                }
                live(authorization)
                val requestId = args.optString("requestId").takeIf { it.isNotBlank() }
                val fingerprint = AgentWorkspace.sha((name + canonical(args)).toByteArray())
                if (requestId != null) synchronized(receipts) {
                    receipts[requestId]?.let { receipt ->
                        if (receipt.first != fingerprint) throw HaminnException(ErrorCodes.CONFLICT, "requestId was already used for different input")
                        return toolResult(receipt.second)
                    }
                }
                val result = when (name) {
                    "haminn_open_agent_session", "haminn_resume_agent_session" -> JSONObject()
                        .put("sessionId", runId).put("scope", "global").put("runId", runId)
                        .put("targeting", "每次操作通过 happId 或 appId 指定目标，不锁定单一 happ")
                        .put("apps", installedAppsJson(false).getJSONArray("apps"))
                    "haminn_list_installed_happs" -> installedAppsJson(args.optBoolean("includeIcons", false))
                    "haminn_get_happ_dev_status" -> {
                        val target = resolveTargetAppId(args)
                        devWorkspaces.status(target).put("appId", target)
                            .put("happId", registry.getInstance(target)?.happId ?: JSONObject.NULL)
                    }
                    "haminn_prepare_happ_development" -> {
                        val target = resolveTargetAppId(args)
                        val strategy = args.optString("strategy", "resume")
                        if (strategy !in setOf("resume", "reset")) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "strategy 必须是 resume 或 reset")
                        val start = args.optString("startRuntimeMode", "dev")
                        val prepared = if (strategy == "reset") {
                            guardedValue(authorization) { devWorkspaces.reset(target) }
                        } else if (start == "dev") {
                            guardedValue(authorization) { devWorkspaces.enter(target) }
                        } else {
                            guardedValue(authorization) { devWorkspaces.prepare(target) }
                        }
                        val result = prepared.put("happId", registry.getInstance(target)?.happId ?: JSONObject.NULL)
                        if (start == "dev") {
                            val runtime = safeUi("open", JSONObject().put("appId", target)
                                .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL), authorization)
                            result.put("runtime", runtime)
                        }
                        result
                    }
                    "haminn_hot_update_happ" -> {
                        val target = resolveTargetAppId(args)
                        val expected = if (args.optBoolean("force", false)) {
                            devWorkspaces.status(target).getLong("revision")
                        } else args.getLong("expectedDevRevision")
                        val changed = guardedValue(authorization) {
                            devWorkspaces.apply(target, expected, args.getJSONArray("files"))
                        }
                        refreshDev(changed, args, target, authorization)
                    }
                    "haminn_download_dev_tree" -> {
                        val target = resolveTargetAppId(args)
                        val revision = args.optLong("expectedDevRevision", Long.MIN_VALUE)
                            .takeIf { it != Long.MIN_VALUE }
                        val artifact = guardedValue(authorization) { devWorkspaces.buildShare(target, revision ?: devWorkspaces.status(target).getLong("revision")) }
                        JSONObject().put("appId", target).put("buildId", artifact.id)
                            .put("devRevision", artifact.revision).put("sha256", artifact.sha256)
                            .put("bytes", artifact.bytes).put("downloadUrl", "$address/v2/builds/${artifact.id}")
                    }
                    "haminn_refresh_happ_page" -> {
                        val target = resolveTargetAppId(args)
                        val payload = JSONObject(args.toString()).put("appId", target)
                        ui("reload", payload, authorization)
                    }
                    "haminn_switch_happ_runtime_mode" -> {
                        val target = resolveTargetAppId(args)
                        val mode = args.getString("runtimeMode")
                        val value = when (mode) {
                            "dev" -> guardedValue(authorization) { devWorkspaces.enter(target) }
                            "stable" -> guardedValue(authorization) { devWorkspaces.leave(target) }
                            else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "runtimeMode 必须是 dev 或 stable")
                        }
                        value.put("runtime", safeUi(if (mode == "dev") "open" else "switch", JSONObject().put("appId", target), authorization))
                    }
                    "haminn_install_happ_release" -> {
                        val target = resolveTargetAppId(args)
                        JSONObject().put("method", "PUT")
                            .put("url", "$address/v1/apps/$target/release")
                            .put("headers", JSONObject().put("Content-Type", "application/zip")
                                .put("Content-Length", args.getLong("bytes"))
                                .put("X-Haminn-Expected-Release", args.getString("expectedReleaseId"))
                                .put("Idempotency-Key", args.getString("requestId"))
                                .put("X-Haminn-Content-SHA256", args.getString("sha256")))
                    }
                    "haminn_promote_dev_release" -> promoteDevRelease(authorization, resolveTargetAppId(args), args)
                    "haminn_get_guide" -> JSONObject().put("serverVersion", BuildConfig.VERSION_NAME)
                        .put("runId", runId).put("guidanceVersion", guidanceVersion)
                        .put("resourceDigests", resourceDigests).put("text", guide())
                    "haminn_runtime_status" -> runtimeStatus(authorization)
                    "haminn_get_page_state" -> {
                        val payload = JSONObject(args.toString())
                        if (payload.optString("appId").isBlank() && payload.optString("happId").isNotBlank()) {
                            payload.put("appId", resolveTargetAppId(payload))
                        }
                        ui("page-state", payload, authorization)
                    }
                    "haminn_capture_screen" -> ui("capture-screen", args, authorization)
                    "haminn_list_apps" -> JSONObject().put("apps", JSONArray(registry.listInstances()
                        .filter { args.optString("happId").isBlank() || it.happId == args.optString("happId") }
                        .map { app ->
                        app.toJson().apply {
                            if (!args.optBoolean("includeIcons", false)) {
                                remove("iconUrl"); remove("customIconUrl"); remove("defaultIconUrl")
                            }
                        }.put("devWorkspace", devWorkspaces.status(app.appId))
                    }))
                    "haminn_get_app" -> registry.getInstance(appId!!)!!.toJson().apply {
                        if (!args.optBoolean("includeIcons", false)) {
                            remove("iconUrl"); remove("customIconUrl"); remove("defaultIconUrl")
                        }
                    }.put("devWorkspace", devWorkspaces.status(appId))
                    "haminn_create_dev_app" -> workspace.createDev(
                        args.getString("name"), args.getString("happId"), devWorkspaces
                    ) { guarded(authorization, it) }
                    "haminn_enter_dev_mode" -> {
                        val targetAppId = appId!!
                        val workspace = guardedValue(authorization) { devWorkspaces.enter(targetAppId) }
                        val operation = createRenderOperation(targetAppId, workspace.getLong("revision"))
                        val openArgs = JSONObject().put("appId", targetAppId)
                            .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                        val runtime = safeUi("open", openArgs, authorization)
                        if (runtime.optString("state") == "not-visible") operation.let(::discardRenderOperation)
                        workspace.put("runtime", runtime)
                            .put("renderOperationId", if (runtime.optString("state") == "not-visible") JSONObject.NULL else operation.id)
                    }
                    "haminn_leave_dev_mode" -> guardedValue(authorization) { devWorkspaces.leave(appId!!) }.also {
                        it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    "haminn_get_dev_status" -> devWorkspaces.status(appId!!)
                    "haminn_list_dev_files" -> devWorkspaces.list(appId!!)
                    "haminn_read_dev_file" -> devWorkspaces.read(appId!!, args.getString("path"))
                    "haminn_apply_dev_files", "haminn_sync_dev_changes" -> {
                        val started = SystemClock.elapsedRealtime()
                        val changed = guardedValue(authorization) {
                            devWorkspaces.apply(appId!!, args.getLong("expectedDevRevision"), args.getJSONArray("files"))
                        }
                        changed.put("timing", JSONObject().put("nativeCommitMs", SystemClock.elapsedRealtime() - started))
                        refreshDev(changed, args, appId!!, authorization)
                    }
                    "haminn_put_dev_file" -> JSONObject().put("method", "PUT")
                        .put("url", "$address/v2/apps/$appId/dev/file?path=" + java.net.URLEncoder.encode(args.getString("path"), "UTF-8"))
                            .put("headers", JSONObject()
                                .put("Content-Type", args.optString("contentType", "application/octet-stream"))
                                .put("Content-Length", args.getLong("bytes"))
                            .put("X-Haminn-Expected-Dev-Revision", args.optLong("expectedDevRevision", 0L))
                            .put("X-Haminn-Force", args.optBoolean("force", false))
                            .put("Idempotency-Key", args.getString("requestId"))
                            .put("X-Haminn-Content-SHA256", args.getString("sha256")))
                    "haminn_replace_dev_tree" -> JSONObject().put("method", "PUT")
                        .put("url", "$address/v2/apps/$appId/dev/tree")
                        .put("headers", JSONObject().put("Content-Type", "application/zip")
                            .put("Content-Length", args.getLong("bytes"))
                            .put("X-Haminn-Expected-Dev-Revision", args.optLong("expectedDevRevision", 0L))
                            .put("X-Haminn-Force", args.optBoolean("force", false))
                            .put("Idempotency-Key", args.getString("requestId"))
                            .put("X-Haminn-Content-SHA256", args.getString("sha256")))
                    "haminn_open_app" -> {
                        val app = registry.getInstance(appId!!)!!
                        val operation = if (app.launchChannel == life.airen.haminn.model.LaunchChannel.DEV) {
                            registry.getDevWorkspace(appId)?.let { createRenderOperation(appId, it.revision) }
                        } else null
                        val ui = ui("open", JSONObject().put("appId", appId)
                            .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL), authorization)
                        if (ui.optString("state") == "not-visible") operation?.let(::discardRenderOperation)
                        ui.put("renderOperationId", operation?.id ?: JSONObject.NULL)
                    }
                    "haminn_reload_app" -> {
                        val app = registry.getInstance(appId!!)!!
                        val workspace = if (app.launchChannel == life.airen.haminn.model.LaunchChannel.DEV) registry.getDevWorkspace(appId) else null
                        val operation = workspace?.let { createRenderOperation(appId, it.revision) }
                        val payload = JSONObject(args.toString()).put("appId", appId)
                            .put("revision", workspace?.revision ?: JSONObject.NULL)
                        val ui = ui("reload", payload, authorization)
                        if (ui.optString("state") == "not-visible") operation?.let(::discardRenderOperation)
                        ui.put("renderOperationId", operation?.id ?: JSONObject.NULL)
                    }
                    "haminn_reload_shell" -> ui("reload-shell", args, authorization)
                    "haminn_wait_dev_render" -> waitForRender(args.getString("operationId"), args.optLong("timeoutMs", 5000))
                    "haminn_get_dev_diagnostics" -> diagnostics(appId!!)
                    "haminn_build_dev_package" -> guardedValue(authorization) {
                        devWorkspaces.build(appId!!, args.getLong("expectedDevRevision"),
                            args.getLong("versionCode"), args.getString("versionName"))
                    }.also {
                        it.put("downloadUrl", "$address/v2/builds/" + it.getString("buildId"))
                    }
                    "haminn_install_dev_package" -> {
                        val artifact = devWorkspaces.artifact(args.getString("buildId"))
                            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "构建产物已过期或不存在")
                        if (artifact.appId != appId || artifact.revision != args.getLong("expectedDevRevision")) {
                            throw HaminnException(ErrorCodes.CONFLICT, "构建产物不属于当前开发 revision")
                        }
                        val stable = registry.getInstance(appId!!)!!.activeReleaseId
                        if (stable != args.getString("expectedStableReleaseId")) {
                            throw HaminnException(ErrorCodes.CONFLICT, "正式版本已经变化")
                        }
                        val installed = runBlocking(Dispatchers.IO) {
                            artifact.file.inputStream().use { input ->
                                installer.installZip(input, null, appId, "agent-dev-publish",
                                    "agent-dev:" + args.getString("requestId"), stable, artifact.sha256,
                                    commitGuard = { guarded(authorization, it) })
                            }
                        }
                        devWorkspaces.installed(appId, artifact.revision, installed.releaseId)
                            .put("releaseId", installed.releaseId).put("operationId", installed.operationId)
                            .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }
                    "haminn_reset_dev_workspace" -> guardedValue(authorization) { devWorkspaces.reset(appId!!) }.also {
                        it.put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }

                    "haminn_list_releases" -> JSONObject().put("activeReleaseId", registry.getInstance(appId!!)?.activeReleaseId ?: JSONObject.NULL)
                        .put("releases", JSONArray(registry.listReleases(appId).map {
                            JSONObject().put("releaseId", it.releaseId).put("treeHash", it.treeHash)
                                .put("versionCode", it.versionCode ?: JSONObject.NULL)
                                .put("versionName", it.versionName ?: JSONObject.NULL)
                                .put("createdAt", it.createdAt).put("provenance", it.provenance)
                        }))
                    "haminn_rollback" -> {
                        registry.getInstance(appId!!)
                            ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "App not found")
                        guarded(authorization) { registry.activateRelease(appId, args.getString("releaseId"), args.getString("expectedReleaseId")) }
                        JSONObject().put("appId", appId).put("releaseId", args.getString("releaseId"))
                            .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization))
                    }

                    else -> throw RpcError(-32602, "Unknown tool")
                }
                if (requestId != null) synchronized(receipts) {
                    if (receipts.size >= 128) receipts.remove(receipts.keys.first())
                    receipts[requestId] = fingerprint to result
                }
                record(name, appId, "ok")
                return if (name == "haminn_capture_screen") imageToolResult(result) else toolResult(result)
            } catch (error: Exception) {
                val code = (error as? HaminnException)?.code ?: "E_OPERATION_FAILED"
                record(name, appId, code)
                return toolResult(JSONObject().put("code", code).put("message", error.message?.take(400) ?: "Operation failed"), true)
            } finally {
                if (acquired) writeGate?.release()
            }
        }

        private fun resolveTargetAppId(args: JSONObject): String {
            args.optString("appId").takeIf { it.isNotBlank() }?.let {
                requireApp(it)
                return it
            }
            val happId = args.optString("happId").takeIf { it.isNotBlank() }
                ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "必须指定 happId 或 appId")
            val matches = registry.listInstances().filter { it.happId == happId }
            if (matches.isEmpty()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "没有已安装的 happ：$happId")
            if (matches.size > 1) throw HaminnException(ErrorCodes.CONFLICT, "同一 happId 存在多个实例，请指定 appId")
            return matches.single().appId
        }

        private fun installedAppsJson(includeIcons: Boolean = false): JSONObject = JSONObject().put("apps", JSONArray(registry.listInstances().map { app ->
            app.toJson().apply {
                if (!includeIcons) {
                    remove("iconUrl"); remove("customIconUrl"); remove("defaultIconUrl")
                }
            }.put("devWorkspace", devWorkspaces.status(app.appId))
        }))

        private fun promoteDevRelease(authorization: String, targetAppId: String, args: JSONObject): JSONObject {
            val expectedRevision = args.getLong("expectedDevRevision")
            val expectedStable = args.getString("expectedStableReleaseId")
            val built = guardedValue(authorization) {
                devWorkspaces.build(targetAppId, expectedRevision, args.getLong("versionCode"), args.getString("versionName"))
            }
            val artifact = devWorkspaces.artifact(built.getString("buildId"))
                ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "构建产物已过期或不存在")
            if (registry.getInstance(targetAppId)?.activeReleaseId != expectedStable) {
                throw HaminnException(ErrorCodes.CONFLICT, "正式版本已经变化")
            }
            val installed = runBlocking(Dispatchers.IO) {
                artifact.file.inputStream().use { input ->
                    installer.installZip(input, null, targetAppId, "agent-dev-publish",
                        "agent-promote:" + args.getString("requestId"), expectedStable, artifact.sha256,
                        commitGuard = { guarded(authorization, it) })
                }
            }
            return devWorkspaces.installed(targetAppId, expectedRevision, installed.releaseId, keepDev = true)
                .put("releaseId", installed.releaseId)
                .put("operationId", installed.operationId)
                .put("runtimeMode", "dev")
                .put("runtime", safeUi("open", JSONObject().put("appId", targetAppId), authorization))
        }

        private fun refreshDev(result: JSONObject, args: JSONObject, appId: String, authorization: String): JSONObject {
            val refreshMode = args.optString("refreshMode", if (args.optBoolean("reload", true)) "auto" else "none")
            if (refreshMode == "none") return result.put("refreshState", "skipped").put("renderOperationId", JSONObject.NULL)
            val revision = result.getLong("revision")
            val operation = createRenderOperation(appId, revision)
            val payload = JSONObject().put("appId", appId).put("revision", revision).put("refreshMode", refreshMode)
                .put("changedPaths", result.optJSONArray("changedPaths") ?: JSONArray())
                .put("route", args.optString("route").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            val runtime = safeUi("refresh", payload, authorization)
            if (runtime.optString("state") == "not-visible") {
                discardRenderOperation(operation)
                return result.put("refreshState", "not-visible").put("renderOperationId", JSONObject.NULL)
            }
            return result.put("refreshState", runtime.optString("state", "scheduled"))
                .put("runtime", runtime).put("renderOperationId", operation.id)
        }

        private fun runtimeStatus(authorization: String): JSONObject {
            val result = safeUi("status", JSONObject(), authorization)
            val appId = result.optString("appId").takeIf { it.isNotBlank() }
            val app = appId?.let(registry::getInstance)
            return result.put("launchChannel", app?.launchChannel?.name?.lowercase() ?: JSONObject.NULL)
                .put("runtimeMode", app?.runtimeMode?.name?.lowercase() ?: JSONObject.NULL)
                .put("isDevelopmentCopy", app?.launchChannel == life.airen.haminn.model.LaunchChannel.DEV)
                .put("serverVersion", BuildConfig.VERSION_NAME).put("runId", runId)
        }

        private fun createRenderOperation(appId: String, revision: Long): RenderOperation {
            val operation = RenderOperation(UUID.randomUUID().toString(), appId, revision, System.currentTimeMillis())
            renderOperations[operation.id] = operation
            renderOperations.entries.removeIf { it.value.createdAt < System.currentTimeMillis() - RENDER_TTL_MS }
            return operation
        }

        private fun discardRenderOperation(operation: RenderOperation) {
            renderOperations.remove(operation.id)
            operation.result.cancel()
        }

        private fun waitForRender(operationId: String, requestedTimeout: Long): JSONObject {
            val operation = renderOperations[operationId]
                ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "渲染操作不存在或已过期")
            val timeout = requestedTimeout.coerceIn(0, 10_000)
            val completed = runBlocking {
                if (timeout == 0L) {
                    if (operation.result.isCompleted) runCatching { operation.result.await() }.getOrNull() else null
                } else withTimeoutOrNull(timeout) { operation.result.await() }
            }
            return completed ?: JSONObject().put("state", "render-timeout").put("operationId", operation.id)
                .put("appId", operation.appId).put("revision", operation.revision)
                .put("diagnostics", diagnostics(operation.appId).getJSONArray("events"))
        }

        private fun diagnostics(appId: String): JSONObject {
            val events = devDiagnostics[appId]
            val values = if (events == null) emptyList() else synchronized(events) { events.toList() }
            return JSONObject().put("appId", appId)
                .put("revision", registry.getDevWorkspace(appId)?.revision ?: JSONObject.NULL)
                .put("events", JSONArray(values))
        }

        private fun ui(action: String, args: JSONObject, authorization: String): JSONObject = runBlocking {
            withContext(Dispatchers.Main) {
                synchronized(this@AgentDevelopmentServer) {
                    live(authorization)
                }
                uiHandler?.invoke(action, args) ?: throw IllegalStateException("Haminn has no foreground activity")
            }
        }

        private fun <T> guardedValue(authorization: String, action: () -> T): T =
            synchronized(this@AgentDevelopmentServer) { live(authorization); action() }

        private fun safeUi(action: String, args: JSONObject, authorization: String) =
            try { ui(action, args, authorization) } catch (_: Exception) {
                JSONObject().put("state", "not-visible").put("appId", args.optString("appId").takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            }

        private fun downloadBuild(authorization: String, session: IHTTPSession): Response {
            live(authorization)
            val buildId = session.uri.substringAfterLast('/')
            val artifact = devWorkspaces.artifact(buildId) ?: return httpError(404, "Build artifact expired or not found")
            return newFixedLengthResponse(Response.Status.OK, "application/zip", FileInputStream(artifact.file), artifact.bytes).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Haminn-Content-SHA256", artifact.sha256)
                addHeader("Content-Disposition", "attachment; filename=\"happ-$buildId.zip\"")
                addHeader("Connection", "close")
            }
        }

        private fun uploadDevFile(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            try {
                live(authorization)
                val path = session.parameters["path"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: return httpError(400, "path query parameter required")
                val force = session.headers["x-haminn-force"]?.equals("true", true) == true
                val expected = if (force) devWorkspaces.status(appId).getLong("revision")
                    else session.headers["x-haminn-expected-dev-revision"]?.toLongOrNull()
                        ?: return httpError(400, "X-Haminn-Expected-Dev-Revision required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 }
                    ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-haminn-content-sha256"]?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return httpError(400, "SHA256 required")
                val length = contentLength(session, MAX_ZIP)
                val fingerprint = AgentWorkspace.sha("dev-file|$appId|$path|$expected|$force|$length|$hash".toByteArray())
                synchronized(receipts) { receipts["http:$key"]?.let { receipt ->
                    if (receipt.first != fingerprint) return httpError(409, "Idempotency-Key was used for different input")
                    return json(200, receipt.second)
                } }
                lateinit var result: JSONObject
                guarded(authorization) {
                    result = devWorkspaces.put(appId, expected, path, FixedLengthInputStream(session.inputStream, length), length, hash)
                }
                result = refreshDev(result, JSONObject().put("refreshMode", session.headers["x-haminn-refresh-mode"] ?: "auto"), appId, authorization)
                synchronized(receipts) { receipts["http:$key"] = fingerprint to result }
                record("put_dev_file", appId, "ok")
                return json(200, result)
            } catch (error: HaminnException) {
                return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed")
            } finally { gate.release() }
        }

        private fun uploadDevTree(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            val incoming = File.createTempFile("dev-tree-", ".zip", context.cacheDir)
            try {
                live(authorization)
                if (session.headers["content-type"]?.substringBefore(';') != "application/zip") return httpError(415, "application/zip required")
                val force = session.headers["x-haminn-force"]?.equals("true", true) == true
                val expected = if (force) devWorkspaces.status(appId).getLong("revision")
                    else session.headers["x-haminn-expected-dev-revision"]?.toLongOrNull()
                        ?: return httpError(400, "X-Haminn-Expected-Dev-Revision required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 }
                    ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-haminn-content-sha256"]?.lowercase()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    ?: return httpError(400, "SHA256 required")
                val length = contentLength(session, MAX_ZIP)
                val fingerprint = AgentWorkspace.sha("dev-tree|$appId|$expected|$force|$length|$hash".toByteArray())
                synchronized(receipts) { receipts["http:$key"]?.let { receipt ->
                    if (receipt.first != fingerprint) return httpError(409, "Idempotency-Key was used for different input")
                    return json(200, receipt.second)
                } }
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(incoming).use { output ->
                    val input = FixedLengthInputStream(session.inputStream, length); val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); digest.update(buffer, 0, read) }
                    output.fd.sync()
                }
                if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, true)) return httpError(400, "ZIP digest mismatch")
                lateinit var result: JSONObject
                guarded(authorization) { incoming.inputStream().use { result = devWorkspaces.replace(appId, expected, it) } }
                result = refreshDev(result, JSONObject().put("refreshMode", session.headers["x-haminn-refresh-mode"] ?: "auto"), appId, authorization)
                synchronized(receipts) { receipts["http:$key"] = fingerprint to result }
                record("replace_dev_tree", appId, "ok")
                return json(200, result)
            } catch (error: HaminnException) {
                return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed")
            } finally { incoming.delete(); gate.release() }
        }

        private fun upload(authorization: String, session: IHTTPSession): Response {
            val appId = session.uri.split('/')[3]; requireApp(appId)
            val gate = writes.computeIfAbsent(appId) { Semaphore(1) }
            if (!gate.tryAcquire()) return httpError(409, "This happ already has a write in progress")
            try {
                live(authorization); workspace.local(appId)
                if (session.headers["content-type"]?.substringBefore(';') != "application/zip") return httpError(415, "application/zip required")
                val length = contentLength(session, MAX_ZIP)
                val expected = session.headers["x-haminn-expected-release"]?.takeIf { it.isNotBlank() } ?: return httpError(400, "X-Haminn-Expected-Release required")
                val key = session.headers["idempotency-key"]?.takeIf { it.length in 8..128 } ?: return httpError(400, "Idempotency-Key required")
                val hash = session.headers["x-haminn-content-sha256"]?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) } ?: return httpError(400, "SHA256 required")
                val result = runBlocking(Dispatchers.IO) {
                    installer.installZip(FixedLengthInputStream(session.inputStream, length), null, appId, "agent", "agent:" + key, expected, hash, commitGuard = { guarded(authorization, it) })
                }
                record("publish_zip", appId, "ok")
                return json(200, JSONObject().put("appId", appId).put("releaseId", result.releaseId).put("operationId", result.operationId)
                    .put("runtime", safeUi("switch", JSONObject().put("appId", appId), authorization)))
            } catch (error: HaminnException) { return httpError(if (error.code == ErrorCodes.CONFLICT) 409 else 400, error.message ?: "Upload failed") }
            finally { gate.release() }
        }

        /** Key development operations are logged only; the UI never shows them. */
        private fun record(tool: String, appId: String?, result: String) {
            Log.i(TAG, "dev-operation tool=$tool app=${appId ?: "-"} result=$result")
        }
    }

    companion object {
        private val DEV_WORKSPACE_TOOLS = setOf(
            "haminn_enter_dev_mode", "haminn_leave_dev_mode", "haminn_get_dev_status",
            "haminn_list_dev_files", "haminn_read_dev_file", "haminn_apply_dev_files",
            "haminn_sync_dev_changes", "haminn_put_dev_file", "haminn_replace_dev_tree",
            "haminn_get_dev_diagnostics", "haminn_build_dev_package",
            "haminn_install_dev_package", "haminn_reset_dev_workspace",
            "haminn_prepare_happ_development", "haminn_hot_update_happ", "haminn_download_dev_tree",
            "haminn_refresh_happ_page", "haminn_switch_happ_runtime_mode", "haminn_promote_dev_release",
            "haminn_install_happ_release",
        )
        private val PASSWORD_PATTERN = Regex("[0-9A-Za-z]{6}")
        private const val MODERN_PROTOCOL = "2026-07-28"
        private val LEGACY_PROTOCOLS = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        private val PROTOCOLS = listOf(MODERN_PROTOCOL) + LEGACY_PROTOCOLS
        private const val DEFAULT_PORT = 8766
        private const val NETWORK_MONITOR_MS = 15_000L
        private const val MAX_RPC = 4L * 1024 * 1024
        private const val MAX_ZIP = 64L * 1024 * 1024
        private const val TAG = "HaminnAgentDev"
        private const val RENDER_TTL_MS = 2L * 60 * 1000
        private fun constantEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
        private fun noCursor(params: JSONObject) { if (params.has("cursor")) throw RpcError(-32602, "No pagination cursor is available") }
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
        private fun validate(value: Any, schema: JSONObject, path: String) {
            fun bad(): Nothing = throw RpcError(-32602, "Invalid $path")
            when (schema.optString("type")) {
                "object" -> {
                    if (value !is JSONObject) bad()
                    val fields = schema.optJSONObject("properties") ?: JSONObject()
                    val required = schema.optJSONArray("required") ?: JSONArray()
                    for (i in 0 until required.length()) if (!value.has(required.getString(i))) bad()
                    value.keys().forEach { key -> if (!fields.has(key)) bad(); validate(value.get(key), fields.getJSONObject(key), "$path.$key") }
                }
                "array" -> { if (value !is JSONArray || value.length() !in schema.optInt("minItems", 0)..schema.optInt("maxItems", 1024)) bad(); for (i in 0 until value.length()) validate(value.get(i), schema.getJSONObject("items"), "$path[$i]") }
                "string" -> if (value !is String || value.length !in schema.optInt("minLength", 0)..schema.optInt("maxLength", 1_000_000)) bad()
                "boolean" -> if (value !is Boolean) bad()
                "integer" -> {
                    if (value !is Number) bad()
                    val number = value.toLong()
                    if (number < schema.optLong("minimum", Long.MIN_VALUE) || number > schema.optLong("maximum", Long.MAX_VALUE)) bad()
                }
            }
        }
        private fun contentLength(session: NanoHTTPD.IHTTPSession, max: Long): Long {
            if (session.headers["transfer-encoding"] != null) throw RpcError(-32600, "Chunked request bodies are not supported; send Content-Length")
            return session.headers["content-length"]?.toLongOrNull()?.takeIf { it in 1..max } ?: throw RpcError(-32600, "Missing or excessive Content-Length")
        }
        private fun body(session: NanoHTTPD.IHTTPSession, max: Long): JSONObject {
            if (session.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") throw RpcError(-32600, "application/json required")
            val length = contentLength(session, max)
            val bytes = ByteArray(length.toInt()); var offset = 0
            while (offset < bytes.size) { val n = session.inputStream.read(bytes, offset, bytes.size - offset); if (n <= 0) throw RpcError(-32700, "Truncated JSON"); offset += n }
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                val source = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                var depth = 0; var quoted = false; var escaped = false
                for (c in source) { if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false } else { if (c == '"') quoted = true else if (c == '{' || c == '[') { depth++; if (depth > 24) throw RpcError(-32700, "JSON too deep") } else if (c == '}' || c == ']') depth-- } }
                val tokener = JSONTokener(source); val value = tokener.nextValue()
                if (value !is JSONObject || tokener.nextClean() != '\u0000') throw RpcError(-32600, "One JSON object is required")
                return value
            } catch (e: RpcError) { throw e } catch (_: Exception) { throw RpcError(-32700, "Invalid JSON") }
        }
        private fun rpcError(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))
        private fun toolResult(value: JSONObject, error: Boolean = false) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", value.toString()))).put("structuredContent", value).put("isError", error)
        private fun imageToolResult(value: JSONObject): JSONObject {
            val data = value.remove("_imageData") as? String ?: throw RpcError(-32603, "Screenshot data unavailable")
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", value.toString()))
                .put(JSONObject().put("type", "image").put("data", data).put("mimeType", value.getString("mimeType")))
            return JSONObject().put("content", content).put("structuredContent", value).put("isError", false)
        }
        private fun binary(code: Int, mime: String, body: ByteArray, filename: String? = null): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.lookup(code), mime, ByteArrayInputStream(body), body.size.toLong()
        ).apply {
            if (filename != null) addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
            addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff")
        }
        private fun text(code: Int, mime: String, body: String, close: Boolean = false): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.lookup(code), "$mime; charset=utf-8", body).apply {
            setKeepAlive(!close)
            if (close) addHeader("Connection", "close")
            addHeader("Cache-Control", "no-store"); addHeader("X-Content-Type-Options", "nosniff"); addHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        }
        private fun json(code: Int, value: JSONObject, close: Boolean = false) = text(code, "application/json", value.toString(), close)
        private fun httpError(code: Int, message: String) = json(code, JSONObject().put("error", message), close = true)
    }
}
