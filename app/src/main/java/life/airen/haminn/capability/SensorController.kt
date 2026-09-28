package life.airen.haminn.capability

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI

class SensorController(context: Context) {
    private data class SensorSpec(val type: Int, val unit: String, val labels: List<String>)
    private data class Watch(val listener: SensorEventListener, val sensor: Sensor)

    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val watches = ConcurrentHashMap<String, Watch>()

    fun availability(): JSONObject {
        val items = JSONArray()
        SPECS.forEach { (name, spec) ->
            val sensor = sensorFor(name, spec)
            items.put(JSONObject()
                .put("type", name)
                .put("available", sensor != null)
                .put("unit", spec.unit)
                .put("labels", JSONArray(spec.labels))
                .put("minDelayUs", sensor?.minDelay ?: JSONObject.NULL)
                .put("maxDelayUs", sensor?.maxDelay ?: JSONObject.NULL)
                .put("maximumRange", sensor?.maximumRange?.toDouble() ?: JSONObject.NULL)
                .put("resolution", sensor?.resolution?.toDouble() ?: JSONObject.NULL)
                .put("powerMa", sensor?.power?.toDouble() ?: JSONObject.NULL)
                .put("wakeUp", sensor?.isWakeUpSensor ?: JSONObject.NULL)
                .put("permission", if (name in STEP_TYPES) "android.permission.ACTIVITY_RECOGNITION" else JSONObject.NULL))
        }
        return JSONObject()
            .put("available", items.length() > 0 && (0 until items.length()).any { items.getJSONObject(it).getBoolean("available") })
            .put("maxRateHz", MAX_RATE_HZ)
            .put("sensors", items)
    }

    fun supported(type: String): Boolean = SPECS[type]?.let { sensorFor(type, it) != null } == true

    fun watch(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (watches.size >= MAX_WATCHES) throw HaminnException(ErrorCodes.QUOTA, "传感器订阅数量已达上限")
        val type = params.optString("type")
        val spec = SPECS[type] ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "不支持的传感器类型：$type")
        val sensor = sensorFor(type, spec) ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备没有此传感器：$type")
        val rateHz = params.optDouble("rateHz", DEFAULT_RATE_HZ).coerceIn(MIN_RATE_HZ, MAX_RATE_HZ)
        val periodUs = (1_000_000.0 / rateHz).toInt()
        val id = UUID.randomUUID().toString()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!watches.containsKey(id)) return
                val values = if (type == "orientation") orientation(event.values) else event.values.map(Float::toDouble)
                val wallClockMs = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
                emit("sensors.changed", JSONObject()
                    .put("subscriptionId", id)
                    .put("type", type)
                    .put("timestamp", wallClockMs)
                    .put("elapsedRealtimeNanos", event.timestamp)
                    .put("accuracy", event.accuracy)
                    .put("unit", spec.unit)
                    .put("labels", JSONArray(spec.labels))
                    .put("values", JSONArray(values)))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (watches.containsKey(id)) emit("sensors.accuracy", JSONObject()
                    .put("subscriptionId", id).put("type", type).put("accuracy", accuracy))
            }
        }
        watches[id] = Watch(listener, sensor)
        if (!manager.registerListener(listener, sensor, periodUs)) {
            watches.remove(id)
            throw HaminnException(ErrorCodes.UNSUPPORTED, "系统拒绝注册传感器：$type", true)
        }
        return JSONObject().put("subscriptionId", id).put("type", type).put("rateHz", rateHz)
    }

    fun clearWatch(id: String): JSONObject {
        val watch = watches.remove(id)
        if (watch != null) manager.unregisterListener(watch.listener, watch.sensor)
        return JSONObject().put("cleared", watch != null)
    }

    fun cancelAll() {
        watches.values.forEach { manager.unregisterListener(it.listener, it.sensor) }
        watches.clear()
    }

    private fun sensorFor(name: String, spec: SensorSpec): Sensor? = when (name) {
        "orientation" -> manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        else -> manager.getDefaultSensor(spec.type)
    }

    private fun orientation(rotationVector: FloatArray): List<Double> {
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, rotationVector)
        SensorManager.getOrientation(matrix, angles)
        val azimuth = ((angles[0] * 180.0 / PI) + 360.0) % 360.0
        return listOf(azimuth, angles[1] * 180.0 / PI, angles[2] * 180.0 / PI)
    }

    companion object {
        private const val MIN_RATE_HZ = 1.0
        private const val DEFAULT_RATE_HZ = 10.0
        private const val MAX_RATE_HZ = 60.0
        private const val MAX_WATCHES = 8
        val STEP_TYPES = setOf("stepCounter", "stepDetector")
        private val SPECS = linkedMapOf(
            "accelerometer" to SensorSpec(Sensor.TYPE_ACCELEROMETER, "m/s²", listOf("x", "y", "z")),
            "gyroscope" to SensorSpec(Sensor.TYPE_GYROSCOPE, "rad/s", listOf("x", "y", "z")),
            "magneticField" to SensorSpec(Sensor.TYPE_MAGNETIC_FIELD, "μT", listOf("x", "y", "z")),
            "orientation" to SensorSpec(Sensor.TYPE_ROTATION_VECTOR, "degree", listOf("azimuth", "pitch", "roll")),
            "rotationVector" to SensorSpec(Sensor.TYPE_ROTATION_VECTOR, "unitless", listOf("x", "y", "z", "scalar", "headingAccuracy")),
            "gravity" to SensorSpec(Sensor.TYPE_GRAVITY, "m/s²", listOf("x", "y", "z")),
            "linearAcceleration" to SensorSpec(Sensor.TYPE_LINEAR_ACCELERATION, "m/s²", listOf("x", "y", "z")),
            "light" to SensorSpec(Sensor.TYPE_LIGHT, "lux", listOf("illuminance")),
            "proximity" to SensorSpec(Sensor.TYPE_PROXIMITY, "cm", listOf("distance")),
            "pressure" to SensorSpec(Sensor.TYPE_PRESSURE, "hPa", listOf("pressure")),
            "ambientTemperature" to SensorSpec(Sensor.TYPE_AMBIENT_TEMPERATURE, "°C", listOf("temperature")),
            "relativeHumidity" to SensorSpec(Sensor.TYPE_RELATIVE_HUMIDITY, "%", listOf("humidity")),
            "stepCounter" to SensorSpec(Sensor.TYPE_STEP_COUNTER, "steps", listOf("stepsSinceBoot")),
            "stepDetector" to SensorSpec(Sensor.TYPE_STEP_DETECTOR, "event", listOf("step")),
        )
    }
}
