package life.airen.haminn.capability

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Vendor-neutral facade over every Android TextToSpeech engine visible to the host. */
class TtsController(context: Context) {
    private data class Handle(val requestedEngine: String?, val engineId: String?, val tts: TextToSpeech, val fallbackFrom: String?)

    private val appContext = context.applicationContext
    private val preferences = VoicePreferences(appContext)
    private val mutex = Mutex()
    @Volatile private var handle: Handle? = null
    private val callbacks = ConcurrentHashMap<String, (String, JSONObject) -> Unit>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun isAvailable(): Boolean = installedEngines().isNotEmpty() || systemDefaultEngine() != null || handle != null

    /** Cheap snapshot for runtime catalogs; availability() performs the operational probe. */
    fun capabilitySnapshot(): JSONObject {
        return JSONObject()
            .put("available", isAvailable())
            .put("probeRequired", handle == null)
            .put("voiceSelectionSupported", false)
            .put("languageSelectionSupported", false)
            .put("fileSynthesisSupported", true)
            .put("networkMayBeRequired", true)
            .put("settingsAvailable", listOf("com.android.settings.TTS_SETTINGS", "android.settings.TTS_SETTINGS")
                .any { Intent(it).resolveActivity(appContext.packageManager) != null })
    }

    /** Public status verifies that the selected or fallback engine really initializes. */
    suspend fun availability(): JSONObject {
        val status = capabilitySnapshot()
        return try {
            val current = getEngine()
            val voices = current.tts.voices.orEmpty()
            val languages = current.tts.availableLanguages.orEmpty()
            status.put("available", true).put("operational", true)
                .put("state", if (current.fallbackFrom == null) "ready" else "fallback")
                .put("voiceSelectionSupported", voices.isNotEmpty())
                .put("languageSelectionSupported", languages.isNotEmpty())
                .put("voiceCount", voices.size)
                .put("languageCount", languages.size)
                .put("reasonCode", if (current.fallbackFrom == null) JSONObject.NULL else "selected-provider-unavailable")
                .put("message", if (current.fallbackFrom == null) JSONObject.NULL else "所选朗读引擎不可用，已切换到系统可用引擎")
        } catch (error: HaminnException) {
            status.put("available", false).put("operational", false).put("state", "unavailable")
                .put("reasonCode", "tts-initialization-failed").put("message", error.message)
        }
    }

    fun preferences(): JSONObject {
        val config = preferences.tts()
        return JSONObject().put("language", config.language ?: JSONObject.NULL)
            .put("rate", config.rate.toDouble()).put("pitch", config.pitch.toDouble())
    }

    suspend fun voices(): JSONObject {
        val selected = preferences.tts()
        return voiceCatalog(getEngine(), selected.voiceId)
    }

    suspend fun languageAvailability(language: String): JSONObject {
        if (language.isBlank()) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "缺少语言代码")
        val result = getEngine().tts.isLanguageAvailable(Locale.forLanguageTag(language))
        return JSONObject().put("language", language).put("nativeCode", result).put("support", when (result) {
            TextToSpeech.LANG_AVAILABLE -> "language"
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "country"
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "variant"
            TextToSpeech.LANG_MISSING_DATA -> "missing-data"
            TextToSpeech.LANG_NOT_SUPPORTED -> "unsupported"
            else -> "unknown"
        }).put("available", result >= TextToSpeech.LANG_AVAILABLE)
    }

    /** Store-only details used by HaminnUI to select and diagnose providers. */
    suspend fun providerStatus(): JSONObject {
        val config = preferences.tts()
        val discovered = installedEngines()
        val systemDefault = systemDefaultEngine()
        val status = JSONObject()
            .put("capability", capabilitySnapshot())
            .put("preferences", preferences.ttsJson(config))
            .put("defaultEngine", systemDefault ?: JSONObject.NULL)
            .put("engines", JSONArray(discovered.map { it.toJson(config.engineId, systemDefault) }))
            .put("selectionAvailable", config.engineId == null || discovered.any { it.id == config.engineId })
        return try {
            val current = getEngine()
            status.put("operational", true).put("effectiveEngine", current.engineId ?: JSONObject.NULL)
                .put("fallbackFrom", current.fallbackFrom ?: JSONObject.NULL)
                .put("voiceCount", current.tts.voices?.size ?: 0)
                .put("currentVoice", current.tts.voice?.name ?: JSONObject.NULL)
        } catch (error: HaminnException) {
            status.put("operational", false).put("effectiveEngine", JSONObject.NULL)
                .put("fallbackFrom", JSONObject.NULL).put("error", error.message)
        }
    }

    suspend fun providerVoices(engineId: String?): JSONObject {
        val normalized = engineId?.takeIf { it.isNotBlank() && it != "system" }
        val current = createEngine(normalized, strict = normalized != null)
        return try { voiceCatalog(current, preferences.tts().voiceId) }
        finally { if (handle?.tts !== current.tts) current.tts.shutdown() }
    }

    suspend fun configure(params: JSONObject): JSONObject {
        val previous = preferences.tts()
        val engineId = nullableSelection(params, "engineId", previous.engineId, "system")
        val voiceId = nullableSelection(params, "voiceId", previous.voiceId, "automatic")
        val language = nullableSelection(params, "language", previous.language, "automatic")
        val rate = if (params.has("rate")) params.optDouble("rate", 1.0).toFloat().coerceIn(0.25f, 2f) else previous.rate
        val pitch = if (params.has("pitch")) params.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2f) else previous.pitch
        if (engineId != null && installedEngines().none { it.id == engineId }) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统 TTS 引擎已不可用")
        }
        val candidate = createEngine(engineId, strict = engineId != null)
        try { configureVoice(candidate.tts, voiceId, language) }
        catch (error: Throwable) { candidate.tts.shutdown(); throw error }
        replaceHandle(candidate)
        val saved = VoicePreferences.Tts(engineId, voiceId, language, rate, pitch)
        preferences.saveTts(saved)
        return JSONObject().put("saved", true).put("preferences", preferences.ttsJson(saved))
            .put("effectiveEngine", candidate.engineId ?: JSONObject.NULL)
            .put("currentVoice", candidate.tts.voice?.name ?: JSONObject.NULL)
    }

    suspend fun speak(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        val text = params.optString("text")
        if (text.isBlank() || text.length > 10_000) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "朗读文本为空或过长")
        val config = preferences.tts()
        val current = getEngine()
        configureVoice(current.tts, params.optString("voiceId").takeIf(String::isNotBlank) ?: config.voiceId,
            params.optString("language").takeIf(String::isNotBlank) ?: config.language)
        current.tts.setSpeechRate(params.optDouble("rate", config.rate.toDouble()).toFloat().coerceIn(0.25f, 2f))
        current.tts.setPitch(params.optDouble("pitch", config.pitch.toDouble()).toFloat().coerceIn(0.5f, 2f))
        val id = UUID.randomUUID().toString()
        callbacks[id] = emit
        val audioParams = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, params.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f))
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, params.optDouble("pan", 0.0).toFloat().coerceIn(-1f, 1f))
        }
        val queueMode = if (params.optString("queue", "flush") == "add") TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        if (current.tts.speak(text, queueMode, audioParams, id) != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            throw HaminnException(ErrorCodes.INTERNAL, "系统 TTS 拒绝了朗读任务", true)
        }
        return JSONObject().put("utteranceId", id).put("voiceId", current.tts.voice?.name ?: JSONObject.NULL)
    }

    suspend fun synthesize(params: JSONObject, file: File): JSONObject {
        val text = params.optString("text")
        if (text.isBlank() || text.length > 10_000) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "朗读文本为空或过长")
        val config = preferences.tts()
        val current = getEngine()
        configureVoice(current.tts, params.optString("voiceId").takeIf(String::isNotBlank) ?: config.voiceId,
            params.optString("language").takeIf(String::isNotBlank) ?: config.language)
        current.tts.setSpeechRate(params.optDouble("rate", config.rate.toDouble()).toFloat().coerceIn(0.25f, 2f))
        current.tts.setPitch(params.optDouble("pitch", config.pitch.toDouble()).toFloat().coerceIn(0.5f, 2f))
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        completions[id] = completion
        if (current.tts.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) {
            completions.remove(id)
            throw HaminnException(ErrorCodes.INTERNAL, "系统 TTS 拒绝了语音文件生成任务", true)
        }
        val completed = try { withTimeout(60_000) { completion.await() } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            current.tts.stop(); throw HaminnException(ErrorCodes.TIMEOUT, "系统 TTS 生成语音文件超时", true)
        } finally { completions.remove(id) }
        if (!completed || !file.isFile || file.length() == 0L) throw HaminnException(ErrorCodes.INTERNAL, "系统 TTS 未生成有效语音文件", true)
        return JSONObject().put("utteranceId", id).put("voiceId", current.tts.voice?.name ?: JSONObject.NULL)
    }

    fun stop(): JSONObject {
        handle?.tts?.stop(); callbacks.clear(); completions.values.forEach { it.cancel() }; completions.clear()
        return JSONObject().put("stopped", true)
    }

    fun shutdown() {
        handle?.tts?.shutdown(); handle = null; callbacks.clear(); completions.values.forEach { it.cancel() }; completions.clear()
    }

    private suspend fun getEngine(): Handle = handle ?: mutex.withLock {
        handle ?: run {
            val configured = preferences.tts().engineId
            val selected = configured?.takeIf { id -> installedEngines().any { it.id == id } }
            var created = try { createEngine(selected, strict = selected != null) }
            catch (selectedError: HaminnException) {
                if (selected == null) throw selectedError
                createEngine(null, strict = false).copy(fallbackFrom = selected)
            }
            if (configured != null && selected == null) created = created.copy(fallbackFrom = configured)
            replaceHandle(created); created
        }
    }

    private suspend fun createEngine(engineId: String?, strict: Boolean): Handle {
        val ready = CompletableDeferred<Int>()
        val created = if (engineId == null) TextToSpeech(appContext) { ready.complete(it) }
        else TextToSpeech(appContext, { ready.complete(it) }, engineId)
        val status = try { withTimeout(15_000) { ready.await() } }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            created.shutdown(); throw HaminnException(ErrorCodes.TIMEOUT, "系统 TTS 初始化超时，请检查语音引擎设置", true)
        }
        if (status != TextToSpeech.SUCCESS || (strict && created.defaultEngine != engineId)) {
            created.shutdown(); throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统 TTS 引擎无法初始化")
        }
        installListener(created)
        return Handle(engineId, created.defaultEngine ?: engineId, created, null)
    }

    private fun replaceHandle(next: Handle) {
        val old = handle
        if (old?.tts !== next.tts) {
            old?.tts?.stop(); old?.tts?.shutdown(); callbacks.clear(); completions.values.forEach { it.cancel() }; completions.clear()
        }
        handle = next
    }

    private fun installListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { id?.let { callbacks[it]?.invoke("tts.start", JSONObject().put("utteranceId", it)) } }
            override fun onDone(id: String?) { id?.let {
                callbacks.remove(it)?.invoke("tts.done", JSONObject().put("utteranceId", it)); completions.remove(it)?.complete(true)
            } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { completeError(id, TextToSpeech.ERROR) }
            override fun onError(id: String?, errorCode: Int) { completeError(id, errorCode) }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let {
                callbacks.remove(it)?.invoke("tts.stop", JSONObject().put("utteranceId", it).put("interrupted", interrupted)); completions.remove(it)?.complete(false)
            } }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { id?.let {
                callbacks[it]?.invoke("tts.range", JSONObject().put("utteranceId", it).put("start", start).put("end", end).put("frame", frame))
            } }
        })
    }

    private fun completeError(id: String?, code: Int) { id?.let {
        callbacks.remove(it)?.invoke("tts.error", JSONObject().put("utteranceId", it).put("code", code)); completions.remove(it)?.complete(false)
    } }

    private fun configureVoice(tts: TextToSpeech, voiceId: String?, language: String?) {
        if (voiceId != null) {
            val voice = tts.voices?.firstOrNull { it.name == voiceId }
                ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统声音已不可用，请重新选择")
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS) throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统声音无法使用")
        } else if (language != null) {
            val result = tts.setLanguage(Locale.forLanguageTag(language))
            if (result < TextToSpeech.LANG_AVAILABLE) throw HaminnException(ErrorCodes.UNSUPPORTED, "当前系统 TTS 不支持语言：$language")
        }
    }

    private fun voiceCatalog(current: Handle, selectedVoice: String?): JSONObject {
        val voices = current.tts.voices?.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name })).orEmpty()
        val languages = current.tts.availableLanguages.orEmpty().map(Locale::toLanguageTag)
            .filter(String::isNotBlank).distinct().sorted()
        return JSONObject().put("currentVoice", current.tts.voice?.name ?: JSONObject.NULL)
            .put("selectedVoice", selectedVoice ?: JSONObject.NULL)
            .put("languageSelectionSupported", languages.isNotEmpty())
            .put("voiceSelectionSupported", voices.isNotEmpty())
            .put("languages", JSONArray(languages))
            .put("voices", JSONArray(voices.map { voice -> JSONObject().put("id", voice.name)
                .put("locale", voice.locale.toLanguageTag()).put("networkRequired", voice.isNetworkConnectionRequired)
                .put("quality", voice.quality).put("latency", voice.latency)
                .put("features", JSONArray(voice.features?.sorted().orEmpty())) }))
    }

    @Suppress("DEPRECATION")
    private fun installedEngines(): List<Provider> = appContext.packageManager
        .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .map { resolved -> Provider(resolved.serviceInfo.packageName,
            resolved.loadLabel(appContext.packageManager)?.toString() ?: resolved.serviceInfo.packageName,
            resolved.serviceInfo.enabled) }
        .distinctBy(Provider::id).sortedBy(Provider::label)

    private fun systemDefaultEngine(): String? = Settings.Secure.getString(appContext.contentResolver, "tts_default_synth")?.takeIf(String::isNotBlank)

    private data class Provider(val id: String, val label: String, val enabled: Boolean) {
        fun toJson(selected: String?, systemDefault: String?) = JSONObject().put("id", id).put("label", label)
            .put("enabled", enabled).put("selected", selected == id).put("systemDefault", systemDefault == id)
    }

    private fun nullableSelection(params: JSONObject, key: String, old: String?, automatic: String): String? {
        if (!params.has(key)) return old
        if (params.isNull(key)) return null
        return params.optString(key).trim().takeIf { it.isNotEmpty() && it != automatic }
    }
}
