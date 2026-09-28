package life.airen.haminn.capability

import android.content.Context
import android.hardware.ConsumerIrManager
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject

class InfraredController(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConsumerIrManager::class.java)

    fun status(): JSONObject {
        val ranges = runCatching { manager?.carrierFrequencies.orEmpty() }.getOrDefault(emptyArray())
        return JSONObject()
            .put("supported", manager?.hasIrEmitter() == true)
            .put("transmitOnly", true)
            .put("receiveSupported", false)
            .put("frequencyRanges", JSONArray(ranges.map {
                JSONObject().put("minHz", it.minFrequency).put("maxHz", it.maxFrequency)
            }))
    }

    fun transmit(params: JSONObject): JSONObject {
        val current = manager
        if (current == null || !current.hasIrEmitter()) throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备没有消费级红外发射器")
        val frequency = params.optInt("carrierFrequencyHz")
        val raw = params.optJSONArray("patternUs") ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "缺少红外脉冲序列")
        if (raw.length() !in 2..MAX_SEGMENTS) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "红外脉冲序列长度无效")
        val pattern = IntArray(raw.length()) { index -> raw.optInt(index).also {
            if (it !in 1..MAX_SEGMENT_US) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "红外脉冲时长无效")
        } }
        if (pattern.sumOf(Int::toLong) > MAX_TOTAL_US) throw HaminnException(ErrorCodes.QUOTA, "红外发射时长不能超过 2 秒")
        val supportedRange = current.carrierFrequencies.orEmpty().any { frequency in it.minFrequency..it.maxFrequency }
        if (!supportedRange) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "设备不支持此红外载波频率")
        try { current.transmit(frequency, pattern) } catch (error: Throwable) {
            throw HaminnException(ErrorCodes.INTERNAL, error.message ?: "红外发射失败", true)
        }
        return JSONObject().put("transmitted", true).put("carrierFrequencyHz", frequency)
            .put("segments", pattern.size).put("durationUs", pattern.sumOf(Int::toLong))
    }

    companion object {
        private const val MAX_SEGMENTS = 512
        private const val MAX_SEGMENT_US = 1_000_000
        private const val MAX_TOTAL_US = 2_000_000L
    }
}
