package life.airen.haminn.capability

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DeviceController(private val activity: Activity) {
    private val appContext = activity.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val cameras = appContext.getSystemService(CameraManager::class.java)
    private val networkWatches = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()
    private val batteryWatches = ConcurrentHashMap<String, BroadcastReceiver>()
    private val torchStates = ConcurrentHashMap<String, Boolean>()
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchStates[cameraId] = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { torchStates.remove(cameraId) }
    }

    init { runCatching { cameras.registerTorchCallback(torchCallback, null) } }

    fun networkStatus(): JSONObject {
        val network = connectivity.activeNetwork
        return networkJson(network, network?.let(connectivity::getNetworkCapabilities))
    }

    fun watchNetwork(emit: (String, JSONObject) -> Unit): JSONObject {
        if (networkWatches.size >= MAX_WATCHES) throw HaminnException(ErrorCodes.QUOTA, "网络订阅数量已达上限")
        val id = UUID.randomUUID().toString()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emit("network.changed", networkJson(network, connectivity.getNetworkCapabilities(network)).put("subscriptionId", id))
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                emit("network.changed", networkJson(network, capabilities).put("subscriptionId", id))
            override fun onLost(network: Network) = emit("network.changed", networkJson(null, null).put("subscriptionId", id))
        }
        networkWatches[id] = callback
        try { connectivity.registerDefaultNetworkCallback(callback) } catch (error: Throwable) {
            networkWatches.remove(id)
            throw HaminnException(ErrorCodes.INTERNAL, error.message ?: "网络状态订阅失败", true)
        }
        return JSONObject().put("subscriptionId", id).put("current", networkStatus())
    }

    fun clearNetworkWatch(id: String): JSONObject {
        val callback = networkWatches.remove(id)
        if (callback != null) runCatching { connectivity.unregisterNetworkCallback(callback) }
        return JSONObject().put("cleared", callback != null)
    }

    fun batteryStatus(intent: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))): JSONObject {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return JSONObject()
            .put("level", if (level >= 0 && scale > 0) level.toDouble() / scale else JSONObject.NULL)
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("status", status)
            .put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
            .put("temperatureC", intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0) ?: JSONObject.NULL)
            .put("voltageMv", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: JSONObject.NULL)
            .put("health", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN)
            .put("powerSave", appContext.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
    }

    fun watchBattery(emit: (String, JSONObject) -> Unit): JSONObject {
        if (batteryWatches.size >= MAX_WATCHES) throw HaminnException(ErrorCodes.QUOTA, "电池订阅数量已达上限")
        val id = UUID.randomUUID().toString()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (batteryWatches.containsKey(id)) emit("battery.changed", batteryStatus(intent).put("subscriptionId", id))
            }
        }
        batteryWatches[id] = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        return JSONObject().put("subscriptionId", id).put("current", batteryStatus())
    }

    fun clearBatteryWatch(id: String): JSONObject {
        val receiver = batteryWatches.remove(id)
        if (receiver != null) runCatching { appContext.unregisterReceiver(receiver) }
        return JSONObject().put("cleared", receiver != null)
    }

    fun torchStatus(): JSONObject {
        val ids = torchCameraIds()
        return JSONObject().put("supported", ids.isNotEmpty()).put("cameras", JSONArray(ids.map { id ->
            JSONObject().put("cameraId", id).put("enabled", torchStates[id] ?: JSONObject.NULL)
        }))
    }

    fun setTorch(enabled: Boolean): JSONObject {
        val cameraId = torchCameraIds().firstOrNull() ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "设备没有可控制的闪光灯")
        try { cameras.setTorchMode(cameraId, enabled) } catch (error: SecurityException) {
            throw HaminnException(ErrorCodes.OS_PERMISSION_DENIED, error.message ?: "Android 未允许控制闪光灯")
        } catch (error: Throwable) {
            throw HaminnException(ErrorCodes.CONFLICT, error.message ?: "闪光灯当前不可用", true)
        }
        torchStates[cameraId] = enabled
        return JSONObject().put("cameraId", cameraId).put("enabled", enabled)
    }

    fun openSettings(page: String): JSONObject {
        val intent = when (page) {
            "wifi" -> Intent(Settings.Panel.ACTION_WIFI)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "voiceInput" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "tts" -> listOf("com.android.settings.TTS_SETTINGS", "android.settings.TTS_SETTINGS")
                .map(::Intent).firstOrNull { it.resolveActivity(activity.packageManager) != null }
                ?: Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不支持的系统设置页：$page")
        }
        if (intent.resolveActivity(activity.packageManager) == null) throw HaminnException(ErrorCodes.UNSUPPORTED, "设备没有对应的系统设置页")
        activity.startActivity(intent)
        return JSONObject().put("opened", true).put("page", page)
    }

    fun cancelAll() {
        networkWatches.keys.toList().forEach(::clearNetworkWatch)
        batteryWatches.keys.toList().forEach(::clearBatteryWatch)
    }

    fun shutdown() {
        cancelAll()
        runCatching { cameras.unregisterTorchCallback(torchCallback) }
    }

    private fun networkJson(network: Network?, capabilities: NetworkCapabilities?): JSONObject {
        val transports = JSONArray()
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) transports.put("wifi")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) transports.put("cellular")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) transports.put("ethernet")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) transports.put("vpn")
        return JSONObject().put("connected", network != null)
            .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("internet", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("captivePortal", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            .put("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            .put("downstreamKbps", capabilities?.linkDownstreamBandwidthKbps ?: JSONObject.NULL)
            .put("upstreamKbps", capabilities?.linkUpstreamBandwidthKbps ?: JSONObject.NULL)
            .put("transports", transports)
    }

    private fun torchCameraIds(): List<String> = runCatching { cameras.cameraIdList.filter { id ->
        val characteristics = cameras.getCameraCharacteristics(id)
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } }.getOrDefault(emptyList())

    companion object { private const val MAX_WATCHES = 4 }
}
