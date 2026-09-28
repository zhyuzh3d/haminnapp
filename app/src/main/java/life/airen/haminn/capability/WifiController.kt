package life.airen.haminn.capability

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WifiController(context: Context) {
    private data class Connection(val callback: ConnectivityManager.NetworkCallback, val bindProcess: Boolean)

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val location = appContext.getSystemService(LocationManager::class.java)
    private val connections = ConcurrentHashMap<String, Connection>()
    @Volatile private var boundConnectionId: String? = null

    @SuppressLint("MissingPermission")
    fun status(): JSONObject {
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.takeUnless { it == WifiManager.UNKNOWN_SSID }?.trim('"')
        val bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        return JSONObject()
            .put("supported", appContext.packageManager.hasSystemFeature("android.hardware.wifi"))
            .put("enabled", wifi.isWifiEnabled)
            .put("connected", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", connectivity.isActiveNetworkMetered)
            .put("locationServiceEnabled", location.isLocationEnabled)
            .put("ssid", ssid ?: JSONObject.NULL)
            .put("bssid", bssid ?: JSONObject.NULL)
            .put("frequencyMHz", info?.frequency ?: JSONObject.NULL)
            .put("linkSpeedMbps", info?.linkSpeed ?: JSONObject.NULL)
            .put("rssiDbm", info?.rssi ?: JSONObject.NULL)
            .put("systemManaged", true)
            .put("directSavedNetworkChangesAllowed", false)
            .put("temporaryNetworkRequestAllowed", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(): JSONObject {
        if (!appContext.packageManager.hasSystemFeature("android.hardware.wifi")) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备没有 Wi-Fi")
        }
        if (!location.isLocationEnabled) {
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, "Android 要求先开启位置信息才能扫描 Wi-Fi")
        }
        val completion = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && !completion.isCompleted) {
                    completion.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        }
        val started = try { wifi.startScan() } catch (error: SecurityException) {
            runCatching { appContext.unregisterReceiver(receiver) }
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, error.message ?: "Android 拒绝 Wi-Fi 扫描")
        }
        val fresh = try {
            if (started) withTimeoutOrNull(SCAN_TIMEOUT_MS) { completion.await() } == true else false
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        val results = runCatching { wifi.scanResults }.getOrElse {
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, it.message ?: "无法读取 Wi-Fi 扫描结果")
        }
        return JSONObject().put("fresh", fresh).put("throttled", !started).put("networks", JSONArray(
            results.sortedByDescending(ScanResult::level).take(MAX_RESULTS).map(::scanResultJson)
        ))
    }

    @SuppressLint("MissingPermission")
    fun requestNetwork(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw HaminnException(ErrorCodes.UNSUPPORTED, "系统版本不支持临时 Wi-Fi 连接")
        if (connections.size >= MAX_CONNECTIONS) throw HaminnException(ErrorCodes.QUOTA, "已有 Wi-Fi 连接请求")
        val ssid = params.optString("ssid")
        if (ssid.isBlank() || ssid.length > 32) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Wi-Fi 名称为空或过长")
        val security = params.optString("security", if (params.optString("passphrase").isBlank()) "open" else "wpa2")
        val passphrase = params.optString("passphrase")
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            setIsHiddenSsid(params.optBoolean("hidden", false))
            when (security) {
                "open" -> if (passphrase.isNotEmpty()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "开放网络不能提供密码")
                "wpa2" -> setWpa2Passphrase(validatePassphrase(passphrase))
                "wpa3" -> setWpa3Passphrase(validatePassphrase(passphrase))
                else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不支持的 Wi-Fi 安全类型：$security")
            }
        }.build()
        val id = UUID.randomUUID().toString()
        val bindProcess = params.optBoolean("bindProcess", false)
        if (bindProcess && boundConnectionId != null) throw HaminnException(ErrorCodes.CONFLICT, "已有 Wi-Fi 连接绑定到 Haminn 进程")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!connections.containsKey(id)) return
                if (bindProcess) {
                    connectivity.bindProcessToNetwork(network)
                    boundConnectionId = id
                }
                emit("wifi.connected", networkJson(id, network).put("processBound", bindProcess))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (connections.containsKey(id)) emit("wifi.changed", networkJson(id, network, capabilities))
            }

            override fun onLost(network: Network) {
                emit("wifi.lost", JSONObject().put("connectionId", id))
                releaseNetwork(id)
            }

            override fun onUnavailable() {
                emit("wifi.unavailable", JSONObject().put("connectionId", id))
                releaseNetwork(id)
            }
        }
        connections[id] = Connection(callback, bindProcess)
        try {
            connectivity.requestNetwork(request, callback, params.optInt("timeoutMs", 30_000).coerceIn(10_000, 120_000))
        } catch (error: Throwable) {
            connections.remove(id)
            throw HaminnException(ErrorCodes.INTERNAL, error.message ?: "Wi-Fi 连接请求失败", true)
        }
        return JSONObject().put("connectionId", id).put("pendingSystemApproval", true).put("processBound", bindProcess)
    }

    fun releaseNetwork(id: String): JSONObject {
        val connection = connections.remove(id)
        if (connection != null) runCatching { connectivity.unregisterNetworkCallback(connection.callback) }
        if (boundConnectionId == id) {
            connectivity.bindProcessToNetwork(null)
            boundConnectionId = null
        }
        return JSONObject().put("released", connection != null)
    }

    fun cancelAll() = connections.keys.toList().forEach(::releaseNetwork)

    @Suppress("DEPRECATION")
    private fun scanResultJson(result: ScanResult) = JSONObject()
        .put("ssid", result.SSID)
        .put("bssid", result.BSSID)
        .put("capabilities", result.capabilities)
        .put("frequencyMHz", result.frequency)
        .put("levelDbm", result.level)
        .put("timestampUs", result.timestamp)

    private fun networkJson(id: String, network: Network, supplied: NetworkCapabilities? = null): JSONObject {
        val caps = supplied ?: connectivity.getNetworkCapabilities(network)
        return JSONObject().put("connectionId", id)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("metered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
    }

    private fun validatePassphrase(value: String): String {
        if (value.length !in 8..63) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "Wi-Fi 密码长度必须为 8 至 63 个字符")
        return value
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val MAX_RESULTS = 80
        private const val MAX_CONNECTIONS = 1
    }
}
