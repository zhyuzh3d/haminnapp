package life.airen.haminn.capability

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val watches = ConcurrentHashMap<String, LocationListener>()

    fun availability(): JSONObject = JSONObject()
        .put("supported", manager.allProviders.isNotEmpty())
        .put("enabled", manager.isLocationEnabled)
        .put("providers", org.json.JSONArray(manager.allProviders.map { provider ->
            JSONObject().put("name", provider).put("enabled", runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false))
        }))

    @SuppressLint("MissingPermission")
    suspend fun getCurrent(params: JSONObject): JSONObject {
        val provider = chooseProvider(params.optBoolean("precise", false))
        val maxAge = params.optLong("maxAgeMs", 0).coerceIn(0, 3_600_000)
        if (maxAge > 0) {
            val cached = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            if (cached != null && System.currentTimeMillis() - cached.time <= maxAge) return cached.json(true)
        }
        val timeout = params.optLong("timeoutMs", 15_000).coerceIn(1_000, 60_000)
        return try {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(appContext)) { location ->
                            if (!continuation.isActive) return@getCurrentLocation
                            if (location == null) continuation.resumeWithException(HaminnException(ErrorCodes.TIMEOUT, "系统没有返回位置", true))
                            else continuation.resume(location.json(false))
                        }
                    } else {
                        val listener = object : CompatLocationListener() {
                            override fun onLocationChanged(location: Location) {
                                manager.removeUpdates(this)
                                if (continuation.isActive) continuation.resume(location.json(false))
                            }
                        }
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                }
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw HaminnException(ErrorCodes.TIMEOUT, "定位超时", true)
        }
    }

    @SuppressLint("MissingPermission")
    fun watch(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (watches.size >= MAX_WATCHES) throw HaminnException(ErrorCodes.QUOTA, "定位订阅数量已达上限")
        val provider = chooseProvider(params.optBoolean("precise", false))
        val id = UUID.randomUUID().toString()
        val listener = object : CompatLocationListener() {
            override fun onLocationChanged(location: Location) {
                if (watches.containsKey(id)) emit("location.changed", location.json(false).put("subscriptionId", id))
            }
        }
        watches[id] = listener
        try {
            manager.requestLocationUpdates(
                provider,
                params.optLong("minTimeMs", 2_000).coerceIn(1_000, 60_000),
                params.optDouble("minDistanceM", 0.0).coerceIn(0.0, 10_000.0).toFloat(),
                listener,
                Looper.getMainLooper(),
            )
        } catch (error: Throwable) {
            watches.remove(id)
            throw error
        }
        return JSONObject().put("subscriptionId", id)
    }

    fun clearWatch(id: String): JSONObject {
        val listener = watches.remove(id)
        if (listener != null) manager.removeUpdates(listener)
        return JSONObject().put("cleared", listener != null)
    }

    fun cancelAll() {
        watches.values.forEach(manager::removeUpdates)
        watches.clear()
    }

    private fun chooseProvider(precise: Boolean): String {
        val candidates = if (precise) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "定位服务未开启")
    }

    private fun Location.json(cached: Boolean) = JSONObject()
        .put("latitude", latitude).put("longitude", longitude).put("accuracyM", accuracy.toDouble())
        .put("altitudeM", if (hasAltitude()) altitude else JSONObject.NULL)
        .put("speedMps", if (hasSpeed()) speed.toDouble() else JSONObject.NULL)
        .put("bearingDeg", if (hasBearing()) bearing.toDouble() else JSONObject.NULL)
        .put("time", time).put("cached", cached)

    companion object { private const val MAX_WATCHES = 4 }
}

private abstract class CompatLocationListener : LocationListener {
    @Deprecated("Required for Android 10 LocationListener compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
