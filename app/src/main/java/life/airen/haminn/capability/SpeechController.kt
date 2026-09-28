package life.airen.haminn.capability

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine

/** Vendor-neutral facade over direct recognizers, on-device recognizers and recognition activities. */
class SpeechController(context: Context) {
    private data class Provider(val id: String, val label: String, val enabled: Boolean)
    private data class Selection(val component: ComponentName?)

    private val appContext = context.applicationContext
    private val preferences = VoicePreferences(appContext)
    private var recognizer: SpeechRecognizer? = null
    private var subscriptionId: String? = null

    /** Public capability data never requires a happ to know the device brand or recognizer vendor. */
    fun availability(): JSONObject {
        val direct = directProviders()
        val activities = activityProviders()
        val defaultDirect = SpeechRecognizer.isRecognitionAvailable(appContext)
        val streaming = defaultDirect || direct.any(Provider::enabled)
        val oneShot = activities.any(Provider::enabled)
        val onDevice = onDeviceAvailable()
        val state = when {
            streaming || onDevice -> "ready"
            oneShot -> "activity-only"
            else -> "unavailable"
        }
        return JSONObject()
            .put("available", streaming || oneShot || onDevice)
            .put("state", state)
            .put("streamingAvailable", streaming)
            .put("oneShotAvailable", oneShot)
            .put("onDeviceAvailable", onDevice)
            .put("partialResultsSupported", streaming || onDevice)
            .put("rmsEventsSupported", streaming || onDevice)
            .put("languageDetectionSupported", Build.VERSION.SDK_INT >= 34 && (streaming || onDevice))
            .put("settingsAvailable", Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).resolveActivity(appContext.packageManager) != null)
            .put("reasonCode", if (state == "unavailable") "no-system-recognizer" else JSONObject.NULL)
            .put("message", when (state) {
                "activity-only" -> "系统只提供带界面的一次性语音识别"
                "unavailable" -> "当前系统没有向普通应用提供可用的语音识别能力"
                else -> JSONObject.NULL
            })
    }

    fun preferences(): JSONObject {
        val config = preferences.speech()
        return JSONObject().put("language", config.language ?: JSONObject.NULL).put("preferOffline", config.preferOffline)
    }

    /** Ask the active Android recognizer for its optional language directory. */
    suspend fun languages(): JSONObject {
        val capability = availability()
        if (!capability.optBoolean("available")) return languageCatalog(emptyList(), null,
            capability.optString("message").takeIf(String::isNotBlank))
        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            lateinit var timeout: Runnable
            fun finish(languages: List<String>, preferred: String?, message: String? = null) {
                if (completed) return
                completed = true
                handler.removeCallbacks(timeout)
                if (continuation.isActive) continuation.resumeWith(Result.success(languageCatalog(languages, preferred, message)))
            }
            timeout = Runnable { finish(emptyList(), null, "当前识别服务没有公开可选语言目录") }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val extras = getResultExtras(false)
                    val supported: List<String> = extras?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)?.toList().orEmpty()
                    val preferred = extras?.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
                    finish(supported, preferred,
                        if (supported.isEmpty()) "当前识别服务没有公开可选语言目录" else null)
                }
            }
            continuation.invokeOnCancellation { completed = true; handler.removeCallbacks(timeout) }
            handler.postDelayed(timeout, 2_500)
            try {
                @Suppress("DEPRECATION")
                val intent = RecognizerIntent.getVoiceDetailsIntent(appContext)
                    ?: Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
                appContext.sendOrderedBroadcast(intent, null, receiver, handler, 0, null, null)
            } catch (_: Throwable) {
                finish(emptyList(), null, "当前识别服务没有公开可选语言目录")
            }
        }
    }

    /** Store-only provider details. */
    fun providerStatus(): JSONObject {
        val config = preferences.speech()
        val direct = directProviders()
        val activities = activityProviders()
        val configured = configuredService()
        return JSONObject().put("capability", availability())
            .put("preferences", preferences.speechJson(config))
            .put("configuredService", configured ?: JSONObject.NULL)
            .put("services", JSONArray(direct.map { it.toJson(config.serviceId, configured) }))
            .put("activities", JSONArray(activities.map { it.toJson(config.activityId, null) }))
            .put("serviceSelectionAvailable", config.serviceId == null || direct.any { it.id == config.serviceId && it.enabled })
            .put("activitySelectionAvailable", config.activityId == null || activities.any { it.id == config.activityId && it.enabled })
    }

    fun configure(params: JSONObject): JSONObject {
        val previous = preferences.speech()
        val serviceId = nullableSelection(params, "serviceId", previous.serviceId)
        val activityId = nullableSelection(params, "activityId", previous.activityId)
        val language = nullableSelection(params, "language", previous.language)
        val preferOffline = if (params.has("preferOffline")) params.optBoolean("preferOffline") else previous.preferOffline
        if (serviceId != null && directProviders().none { it.id == serviceId && it.enabled }) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统语音识别服务已不可用")
        }
        if (activityId != null && activityProviders().none { it.id == activityId && it.enabled }) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "所选系统语音识别界面已不可用")
        }
        val saved = VoicePreferences.Speech(serviceId, activityId, language, preferOffline)
        preferences.saveSpeech(saved)
        return JSONObject().put("saved", true).put("preferences", preferences.speechJson(saved))
            .put("capability", availability())
    }

    fun start(params: JSONObject, emit: (String, JSONObject) -> Unit): JSONObject {
        if (recognizer != null) throw HaminnException(ErrorCodes.CONFLICT, "已有语音识别任务")
        val id = UUID.randomUUID().toString()
        val requestedOnDevice = params.optBoolean("onDevice", false)
        val emitRms = params.optBoolean("rmsEvents", false)
        if (requestedOnDevice && !onDeviceAvailable()) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "设备没有可用的离线语音识别服务")
        }
        val selection = if (requestedOnDevice) Selection(null) else chooseDirectProvider()
        val created = try {
            when {
                requestedOnDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                selection.component != null -> SpeechRecognizer.createSpeechRecognizer(appContext, selection.component)
                SpeechRecognizer.isRecognitionAvailable(appContext) -> SpeechRecognizer.createSpeechRecognizer(appContext)
                else -> throw unavailableStreaming()
            }
        } catch (error: HaminnException) { throw error }
        catch (error: Throwable) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "系统语音识别服务无法启动：${error.message ?: "未知错误"}", true)
        }
        recognizer = created
        subscriptionId = id
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = emit("speech.ready", event(id))
            override fun onBeginningOfSpeech() = emit("speech.begin", event(id))
            override fun onRmsChanged(rmsdB: Float) {
                if (emitRms) emit("speech.rms", event(id).put("rmsDb", rmsdB.toDouble()))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = emit("speech.end", event(id))
            override fun onError(error: Int) {
                emit("speech.error", event(id).put("code", errorName(error)).put("message", errorMessage(error)))
                release(id)
            }
            override fun onResults(results: Bundle?) { emit("speech.final", resultJson(id, results)); release(id) }
            override fun onPartialResults(results: Bundle?) = emit("speech.partial", resultJson(id, results))
            override fun onEvent(eventType: Int, params: Bundle?) = emit("speech.serviceEvent", event(id).put("eventType", eventType))
            override fun onLanguageDetection(results: Bundle) {
                if (Build.VERSION.SDK_INT >= 34) emit("speech.language", event(id)
                    .put("language", results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: JSONObject.NULL)
                    .put("confidenceLevel", results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, -1))
                    .put("alternatives", JSONArray(results.getStringArrayList(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES).orEmpty())))
            }
        })
        val intent = recognitionIntent(params)
        try { created.startListening(intent) }
        catch (error: Throwable) {
            release(id)
            throw HaminnException(ErrorCodes.INTERNAL, "系统语音识别启动失败：${error.message ?: "未知错误"}", true)
        }
        return JSONObject().put("subscriptionId", id).put("onDevice", requestedOnDevice)
            .put("mode", "streaming")
    }

    fun activityIntent(params: JSONObject): Intent {
        val provider = chooseActivityProvider()
        return recognitionIntent(params).apply { provider.component?.let(::setComponent) }
    }

    fun activityResult(data: Intent?): JSONObject {
        val texts = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        val confidence = data?.getFloatArrayExtra(SpeechRecognizer.CONFIDENCE_SCORES)
        return JSONObject().put("cancelled", false).put("mode", "one-shot")
            .put("alternatives", JSONArray(texts.mapIndexed { index, text -> JSONObject().put("text", text)
                .put("confidence", confidence?.getOrNull(index)?.takeIf { it >= 0 } ?: JSONObject.NULL) }))
    }

    fun stop(id: String?): JSONObject {
        if (idMatches(id)) recognizer?.stopListening()
        return JSONObject().put("stopping", recognizer != null)
    }

    fun cancel(): JSONObject {
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null; subscriptionId = null
        return JSONObject().put("cancelled", true)
    }

    fun shutdown() = cancel()

    private fun recognitionIntent(params: JSONObject): Intent {
        val config = preferences.speech()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, when (params.optString("languageModel", "freeForm")) {
                "freeForm" -> RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                "webSearch" -> RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
                else -> throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "未知语音识别语言模型")
            })
            val language = params.optString("language").takeIf(String::isNotBlank) ?: config.language
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            params.optString("prompt").takeIf(String::isNotBlank)?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it.take(200)) }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, params.optBoolean("partial", true))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, params.optInt("maxResults", 3).coerceIn(1, 5))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, params.optBoolean("preferOffline", config.preferOffline))
            putExtra("calling_package", appContext.packageName)
            params.optLong("completeSilenceMs", 0).takeIf { it in 250..10_000 }?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it)
            }
            params.optLong("possiblyCompleteSilenceMs", 0).takeIf { it in 250..10_000 }?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it)
            }
            if (Build.VERSION.SDK_INT >= 34 && params.optBoolean("detectLanguage", false)) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            }
        }
    }

    private fun chooseDirectProvider(): Selection {
        val services = directProviders().filter(Provider::enabled)
        val selectedId = preferences.speech().serviceId
        val selected = services.firstOrNull { it.id == selectedId }
        if (selected != null) return Selection(ComponentName.unflattenFromString(selected.id))
        val configured = configuredService()?.let { id -> services.firstOrNull { it.id == id } }
        if (configured != null) return Selection(ComponentName.unflattenFromString(configured.id))
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) return Selection(null)
        val fallback = services.firstOrNull() ?: throw unavailableStreaming()
        return Selection(ComponentName.unflattenFromString(fallback.id))
    }

    private fun chooseActivityProvider(): Selection {
        val activities = activityProviders().filter(Provider::enabled)
        val selectedId = preferences.speech().activityId
        val provider = activities.firstOrNull { it.id == selectedId } ?: activities.firstOrNull()
            ?: throw HaminnException(ErrorCodes.UNSUPPORTED,
                "当前系统没有可用的语音识别界面，请在 Haminn 语音服务设置中检查系统能力")
        return Selection(ComponentName.unflattenFromString(provider.id))
    }

    private fun unavailableStreaming() = HaminnException(ErrorCodes.UNSUPPORTED,
        if (activityProviders().isNotEmpty()) "系统仅支持带界面的一次性识别，请使用 speech.recognizeOnce"
        else "当前系统没有向普通应用提供语音识别服务，请打开系统语音输入设置检查", true)

    private fun resultJson(id: String, results: Bundle?): JSONObject {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return event(id).put("alternatives", JSONArray(texts.mapIndexed { index, text -> JSONObject().put("text", text)
            .put("confidence", confidence?.getOrNull(index)?.takeIf { it >= 0 } ?: JSONObject.NULL) }))
    }

    private fun event(id: String) = JSONObject().put("subscriptionId", id)
    private fun release(id: String) { if (subscriptionId == id) { recognizer?.destroy(); recognizer = null; subscriptionId = null } }
    private fun idMatches(id: String?) = id == null || id == subscriptionId
    private fun onDeviceAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    private fun configuredService(): String? = Settings.Secure.getString(appContext.contentResolver, "voice_recognition_service")
        ?.let(ComponentName::unflattenFromString)?.flattenToString()

    @Suppress("DEPRECATION")
    private fun directProviders(): List<Provider> = appContext.packageManager
        .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .map { resolved -> Provider(ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name).flattenToString(),
            resolved.loadLabel(appContext.packageManager)?.toString() ?: resolved.serviceInfo.packageName, resolved.serviceInfo.enabled) }
        .distinctBy(Provider::id).sortedBy(Provider::label)

    @Suppress("DEPRECATION")
    private fun activityProviders(): List<Provider> = appContext.packageManager
        .queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        .map { resolved -> Provider(ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name).flattenToString(),
            resolved.loadLabel(appContext.packageManager)?.toString() ?: resolved.activityInfo.packageName, resolved.activityInfo.enabled) }
        .distinctBy(Provider::id).sortedBy(Provider::label)

    private fun Provider.toJson(selected: String?, systemDefault: String?) = JSONObject().put("id", id).put("label", label)
        .put("enabled", enabled).put("selected", id == selected).put("systemDefault", id == systemDefault)

    private fun languageCatalog(values: List<String>, preferred: String?, message: String?): JSONObject {
        val languages = values.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        val selected = preferred?.trim()?.takeIf { it in languages }
        return JSONObject().put("languageSelectionSupported", languages.isNotEmpty())
            .put("languages", JSONArray(languages))
            .put("preferredLanguage", selected ?: JSONObject.NULL)
            .put("source", "system-provider")
            .put("message", message ?: JSONObject.NULL)
    }

    private fun nullableSelection(params: JSONObject, key: String, old: String?): String? {
        if (!params.has(key)) return old
        if (params.isNull(key)) return null
        return params.optString(key).trim().takeIf { it.isNotEmpty() && it != "system" && it != "automatic" }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
        SpeechRecognizer.ERROR_NO_MATCH -> "no-match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        SpeechRecognizer.ERROR_SERVER -> "server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech-timeout"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "service-disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language-not-supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-unavailable"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too-many-requests"
        else -> "unknown-$error"
    }

    private fun errorMessage(error: Int): String = when (errorName(error)) {
        "permission" -> "麦克风权限未授予"
        "network" -> "当前语音服务需要网络，但网络不可用"
        "no-match" -> "没有识别到清晰语音"
        "busy" -> "系统语音服务正忙，请稍后重试"
        "speech-timeout" -> "等待语音输入超时"
        "service-disconnected" -> "系统语音服务已断开，请重试或重新选择服务"
        "language-not-supported" -> "系统语音服务不支持所选语言"
        "language-unavailable" -> "所选语言模型尚未安装或当前不可用"
        "too-many-requests" -> "语音识别请求过于频繁，请稍后重试"
        "audio" -> "系统无法读取麦克风音频"
        else -> "系统语音识别失败"
    }
}
