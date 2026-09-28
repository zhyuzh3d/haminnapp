package life.airen.haminn.capability

import android.content.Context
import org.json.JSONObject

/** User-owned provider choices. Public happ APIs consume these preferences without exposing vendor branching. */
class VoicePreferences(context: Context) {
    data class Tts(
        val engineId: String?,
        val voiceId: String?,
        val language: String?,
        val rate: Float,
        val pitch: Float,
    )

    data class Speech(
        val serviceId: String?,
        val activityId: String?,
        val language: String?,
        val preferOffline: Boolean,
    )

    private val values = context.applicationContext.getSharedPreferences("voice-capabilities", Context.MODE_PRIVATE)

    fun tts(): Tts = Tts(
        engineId = values.getString(TTS_ENGINE, null),
        voiceId = values.getString(TTS_VOICE, null),
        language = values.getString(TTS_LANGUAGE, null),
        rate = values.getFloat(TTS_RATE, 1f).coerceIn(0.25f, 2f),
        pitch = values.getFloat(TTS_PITCH, 1f).coerceIn(0.5f, 2f),
    )

    fun speech(): Speech = Speech(
        serviceId = values.getString(SPEECH_SERVICE, null),
        activityId = values.getString(SPEECH_ACTIVITY, null),
        language = values.getString(SPEECH_LANGUAGE, null),
        preferOffline = values.getBoolean(SPEECH_OFFLINE, false),
    )

    fun saveTts(config: Tts) {
        values.edit()
            .putNullableString(TTS_ENGINE, config.engineId)
            .putNullableString(TTS_VOICE, config.voiceId)
            .putNullableString(TTS_LANGUAGE, config.language)
            .putFloat(TTS_RATE, config.rate.coerceIn(0.25f, 2f))
            .putFloat(TTS_PITCH, config.pitch.coerceIn(0.5f, 2f))
            .apply()
    }

    fun saveSpeech(config: Speech) {
        values.edit()
            .putNullableString(SPEECH_SERVICE, config.serviceId)
            .putNullableString(SPEECH_ACTIVITY, config.activityId)
            .putNullableString(SPEECH_LANGUAGE, config.language)
            .putBoolean(SPEECH_OFFLINE, config.preferOffline)
            .apply()
    }

    fun ttsJson(config: Tts = tts()): JSONObject = JSONObject()
        .put("engineSelection", config.engineId ?: "system")
        .put("voiceSelection", config.voiceId ?: "automatic")
        .put("language", config.language ?: JSONObject.NULL)
        .put("rate", config.rate.toDouble())
        .put("pitch", config.pitch.toDouble())

    fun speechJson(config: Speech = speech()): JSONObject = JSONObject()
        .put("serviceSelection", config.serviceId ?: "system")
        .put("activitySelection", config.activityId ?: "system")
        .put("language", config.language ?: JSONObject.NULL)
        .put("preferOffline", config.preferOffline)

    fun applyJson(ttsJson: JSONObject?, speechJson: JSONObject?) {
        ttsJson?.let {
            saveTts(Tts(
                it.optString("engineSelection").takeIf { value -> value.isNotBlank() && value != "system" },
                it.optString("voiceSelection").takeIf { value -> value.isNotBlank() && value != "automatic" },
                it.optString("language").takeIf { value -> value.isNotBlank() && value != "null" },
                it.optDouble("rate", 1.0).toFloat(), it.optDouble("pitch", 1.0).toFloat()))
        }
        speechJson?.let {
            saveSpeech(Speech(
                it.optString("serviceSelection").takeIf { value -> value.isNotBlank() && value != "system" },
                it.optString("activitySelection").takeIf { value -> value.isNotBlank() && value != "system" },
                it.optString("language").takeIf { value -> value.isNotBlank() && value != "null" },
                it.optBoolean("preferOffline", false)))
        }
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(key: String, value: String?) = apply {
        if (value.isNullOrBlank()) remove(key) else putString(key, value)
    }

    companion object {
        private const val TTS_ENGINE = "tts.engine"
        private const val TTS_VOICE = "tts.voice"
        private const val TTS_LANGUAGE = "tts.language"
        private const val TTS_RATE = "tts.rate"
        private const val TTS_PITCH = "tts.pitch"
        private const val SPEECH_SERVICE = "speech.service"
        private const val SPEECH_ACTIVITY = "speech.activity"
        private const val SPEECH_LANGUAGE = "speech.language"
        private const val SPEECH_OFFLINE = "speech.offline"
    }
}
