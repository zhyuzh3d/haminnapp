package life.airen.haminn.capability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import life.airen.haminn.data.FileStore
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import life.airen.haminn.registry.AppRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Screen capture and screen recording for happs.
 *
 * The controller lives on [life.airen.haminn.HaminnApplication] rather than on the
 * activity, because the whole point of recording is that it keeps running while the user
 * is in another application and the page is in the background. The activity only lends it
 * two things it cannot own: the consent dialog and the page the events are addressed to.
 *
 * Every projection needs its own user consent. Haminn's own grant for `screen.*` may be
 * remembered, but the system dialog is asked every single time, and one consent only ever
 * produces one [VirtualDisplay] — that is why a recording and a screenshot cannot overlap.
 */
class ScreenCaptureController(
    private val context: Context,
    private val registry: AppRegistry,
    private val files: FileStore,
    private val scope: CoroutineScope,
) {
    /** Asks the user to approve one projection. Returns null when the user declined. */
    fun interface Consent {
        suspend fun request(): Intent?
    }

    /** Delivers a `screen.*` event to the page that started the recording, if it is still there. */
    fun interface Listener {
        fun onEvent(ownerAppId: String, event: String, data: JSONObject)
    }

    private class LiveProjection(
        val token: String,
        val projection: MediaProjection,
        val callback: MediaProjection.Callback,
        var virtualDisplay: VirtualDisplay? = null,
        var imageReader: ImageReader? = null,
    )

    private class Recording(
        val id: String,
        val appId: String,
        val generation: String,
        val request: ScreenRecordingRequest,
        val deliveredAudio: ScreenAudioMode,
        val width: Int,
        val height: Int,
        /** One video file per segment, oldest first; the last one is still being written. */
        val videoFiles: MutableList<File>,
        /** One continuous audio stream for the whole session, sliced per segment when muxing. */
        val audioFile: File,
        /** Scratch container reused for assembling the segment that is being delivered. */
        val muxFile: File,
        val recorder: MediaRecorder,
        val live: LiveProjection,
        val audioCapture: ScreenAudioCapture?,
        val startedAt: Long,
    ) {
        /** The file the recorder switches to at the next size limit, queued in the 90% window. */
        var pendingVideoFile: File? = null

        /** Audio presentation time the segment being written started at. */
        var segmentStartUs: Long = Long.MIN_VALUE

        /** Summaries of the segments already delivered, in order. */
        val segments = mutableListOf<JSONObject>()

        /** The in-flight delivery of a closed segment, awaited before the session is finalized. */
        var segmentJob: Job? = null

        /** Set when the file store refused a segment, which is where a session has to end. */
        @Volatile var storeMessage: String? = null

        /** Set on cancel and on encoder failure, so a segment in flight stops importing. */
        @Volatile var abandoned = false

        /** Guards [videoFiles], [pendingVideoFile], [segmentStartUs] and [segments]. */
        val segmentLock = Any()
    }

    private class StillImage(val bytes: ByteArray, val width: Int, val height: Int, val mime: String, val extension: String)

    /** The outcome of the recording that just ended, kept so a late `stopRecording` can still be answered. */
    private class Finished(val id: String, val appId: String, val result: JSONObject)

    private val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
    private val lock = Any()
    private var activeKind: String? = null
    private var recording: Recording? = null
    private var lastResult: Finished? = null
    private var consent: Consent? = null
    private var listener: Listener? = null
    private var serviceWait: CompletableDeferred<Unit>? = null
    private var serviceToken: String? = null
    private var serviceError: Throwable? = null

    fun attachConsent(value: Consent) {
        consent = value
    }

    fun detachConsent() {
        consent = null
    }

    fun attachListener(value: Listener) {
        listener = value
    }

    fun detachListener() {
        listener = null
    }

    /**
     * What this device can actually do, so a page never has to guess from the Android
     * version or the vendor. `systemAudioUsages` is the honest scope of playback capture:
     * everything outside those three usages is unreachable, and DRM content always is.
     */
    fun availability(): JSONObject {
        val systemAudio = playbackCaptureSupported()
        val microphone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val modes = buildList {
            add(ScreenAudioMode.NONE)
            if (systemAudio) add(ScreenAudioMode.SYSTEM)
            if (microphone) add(ScreenAudioMode.MICROPHONE)
            if (systemAudio && microphone) add(ScreenAudioMode.BOTH)
        }
        return JSONObject()
            .put("supported", true)
            .put("systemAudio", systemAudio)
            .put("systemAudioUsages", JSONArray(listOf("media", "game", "unknown")))
            .put("microphone", microphone)
            .put("audioModes", JSONArray(modes.map { it.wire }))
            .put("maxDurationMs", ScreenCapturePlan.MAX_MAX_DURATION_MS)
            .put("maxBytes", ScreenCapturePlan.MAX_MAX_BYTES)
            // Rolling to the next file is `MediaRecorder.setNextOutputFile`, which is API 26 and
            // this app's floor is API 29, so the answer is the same on every device it runs on.
            .put("segmenting", true)
            .put("maxFrameRate", ScreenCapturePlan.MAX_FRAME_RATE)
            .put("scales", JSONArray(ScreenCapturePlan.SCALES))
            .put("videoBitRateMin", ScreenCapturePlan.MIN_VIDEO_BIT_RATE)
            .put("videoBitRateMax", ScreenCapturePlan.MAX_VIDEO_BIT_RATE)
            .put("audioBitRateMin", ScreenCapturePlan.MIN_AUDIO_BIT_RATE)
            .put("audioBitRateMax", ScreenCapturePlan.MAX_AUDIO_BIT_RATE)
            .put("stillFormats", JSONArray(ScreenCapturePlan.STILL_FORMATS))
            .put("stillMaxEdge", ScreenCapturePlan.MAX_STILL_MAX_EDGE)
    }

    suspend fun capture(params: JSONObject, appId: String, generation: String): JSONObject {
        val request = ScreenCapturePlan.still(value(params, "maxEdge"), value(params, "format"))
        val live = openProjection(KIND_CAPTURE) ?: return JSONObject().put("cancelled", true)
        try {
            val still = grabStill(live, request)
            val name = "screen-${timestamp()}.${still.extension}"
            val stored = withContext(Dispatchers.IO) {
                still.bytes.inputStream().use { files.import(appId, generation, it, name, still.mime) }
            }
            val result = JSONObject()
            stored.keys().forEach { key -> result.put(key, stored.get(key)) }
            return result
                .put("cancelled", false)
                .put("width", still.width)
                .put("height", still.height)
                .put("capturedAt", System.currentTimeMillis())
        } finally {
            closeProjection(live)
            stopForegroundService()
        }
    }

    suspend fun startRecording(params: JSONObject, appId: String, generation: String): JSONObject {
        val request = ScreenCapturePlan.recording(
            audio = value(params, "audio"),
            maxDurationMs = value(params, "maxDurationMs"),
            maxBytes = value(params, "maxBytes"),
            segment = value(params, "segment"),
            scale = value(params, "scale"),
            frameRate = value(params, "frameRate"),
            videoBitRate = value(params, "videoBitRate"),
            audioBitRate = value(params, "audioBitRate"),
            name = value(params, "name"),
            supportedModes = audioModes(),
        )
        val live = openProjection(KIND_RECORD) ?: return JSONObject().put("cancelled", true)
        val id = UUID.randomUUID().toString()
        val directory = recordingDirectory()
        // Segments are numbered from the first file, not from the first rollover, so the name of
        // a piece never depends on when the recording happened to end.
        val videoFile = File(directory, "$id.video.0.mp4")
        val audioFile = File(directory, "$id.audio.mp4")
        val muxFile = File(directory, "$id.final.mp4")
        var recorder: MediaRecorder? = null
        var audioCapture: ScreenAudioCapture? = null
        try {
            val metrics = displayMetrics()
            val size = resolveVideoSize(metrics.widthPixels, metrics.heightPixels, request.scale)
            // Worth a line: a device that reports its display the other way round is invisible
            // in every later reading, and the frame it produces is the evidence.
            Log.i(TAG, "screen recording frame ${size.first}x${size.second} for display " +
                "${metrics.widthPixels}x${metrics.heightPixels} scale=${request.scale} segment=${request.segment}")
            val device = buildRecorder(
                videoFile = videoFile,
                width = size.first,
                height = size.second,
                request = request,
                maxVideoBytes = ScreenCapturePlan.videoByteBudget(request.maxBytes),
                onInfo = { what -> onRecorderInfo(id, what) },
                onError = { what, extra -> onRecorderError(id, what, extra) },
            )
            recorder = device
            // The audio pipeline starts before the display so it is already capturing when
            // the first frame is composited, rather than a few hundred milliseconds late.
            audioCapture = if (request.audio == ScreenAudioMode.NONE) {
                null
            } else {
                ScreenAudioCapture.open(live.projection, request.audio, request.audioBitRate, audioFile)
            }
            val delivered = audioCapture?.mode ?: ScreenAudioMode.NONE
            live.virtualDisplay = live.projection.createVirtualDisplay(
                "haminn-screen-recording",
                size.first,
                size.second,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                device.surface,
                null,
                null,
            )
            device.start()
            val active = Recording(
                id = id,
                appId = appId,
                generation = generation,
                request = request,
                deliveredAudio = delivered,
                width = size.first,
                height = size.second,
                videoFiles = mutableListOf(videoFile),
                audioFile = audioFile,
                muxFile = muxFile,
                recorder = device,
                live = live,
                audioCapture = audioCapture,
                startedAt = SystemClock.elapsedRealtime(),
            )
            synchronized(lock) {
                recording = active
                lastResult = null
            }
            return JSONObject()
                .put("recordingId", id)
                .put("audio", delivered.wire)
                .put("width", size.first)
                .put("height", size.second)
                .put("segment", request.segment)
                .put("maxDurationMs", request.maxDurationMs)
                .put("maxBytes", request.maxBytes)
                .put("startedAt", System.currentTimeMillis())
        } catch (error: Throwable) {
            recorder?.let { device ->
                runCatching { device.reset() }
                runCatching { device.release() }
            }
            runCatching { audioCapture?.stop() }
            videoFile.delete()
            audioFile.delete()
            muxFile.delete()
            closeProjection(live)
            stopForegroundService()
            throw error as? HaminnException
                ?: HaminnException(ErrorCodes.INTERNAL, error.message ?: "无法开始录屏", true)
        }
    }

    suspend fun stopRecording(appId: String, recordingId: String?): JSONObject {
        val active = synchronized(lock) { recording }
        if (active == null) {
            // A recording that just ended on its own limit is still the answer to this call,
            // but only to the app that owns it: the cached outcome names a file.
            val recent = synchronized(lock) { lastResult }
            if (recent != null && recent.appId == appId && (recordingId.isNullOrBlank() || recent.id == recordingId)) {
                return recent.result
            }
            throw HaminnException(ErrorCodes.CONFLICT, "当前没有进行中的录屏")
        }
        if (active.appId != appId) throw HaminnException(ErrorCodes.CONFLICT, "录屏不属于当前应用")
        if (!recordingId.isNullOrBlank() && active.id != recordingId) {
            throw HaminnException(ErrorCodes.CONFLICT, "录屏 ID 与当前会话不匹配")
        }
        return finish(active.id, REASON_PAGE) ?: throw HaminnException(ErrorCodes.CONFLICT, "录屏已经结束")
    }

    suspend fun cancelRecording(appId: String, recordingId: String?): JSONObject {
        val active = synchronized(lock) {
            val current = recording
            if (current == null || current.appId != appId || (!recordingId.isNullOrBlank() && current.id != recordingId)) {
                null
            } else {
                recording = null
                current
            }
        } ?: return JSONObject().put("cancelled", false)
        // A segment that rolled over moments ago may still be assembling. Cancelling means the
        // caller wants none of it, so the in-flight delivery is told to stop before anything it
        // holds reaches the file store.
        active.abandoned = true
        withContext(Dispatchers.IO) { discardRecording(active) }
        return JSONObject().put("cancelled", true)
    }

    /**
     * Called whenever the foreground happ changes. A recording belongs to the app that
     * started it, so a page that has been replaced ends the recording and still receives
     * the file in its own library.
     */
    fun onForegroundAppChanged(appId: String?) {
        val active = synchronized(lock) { recording } ?: return
        if (active.appId == appId) return
        scope.launch { runCatching { finish(active.id, REASON_HOST) } }
    }

    /** Stops the notification's "停止录制" action. The host, not the page, ended this one. */
    fun requestStopFromNotification(token: String) {
        val active = synchronized(lock) { recording } ?: return
        if (token.isNotEmpty() && active.live.token != token) return
        scope.launch { runCatching { finish(active.id, REASON_HOST) } }
    }

    /**
     * The last chance to keep what was recorded. Called from the activity's teardown, which
     * is not a coroutine, so the import runs on the calling thread: the process may not
     * survive long enough to hand the work to another one.
     */
    fun shutdown() {
        val active = synchronized(lock) {
            recording.also { recording = null }
        }
        if (active != null) {
            runCatching { finalizeRecording(active, REASON_HOST) }
            cleanupFiles(active)
        }
        stopForegroundService()
    }

    fun onForegroundStarted(token: String) {
        synchronized(lock) {
            if (serviceToken == token) serviceWait?.complete(Unit)
        }
    }

    fun onForegroundFailed(token: String, error: Throwable) {
        synchronized(lock) {
            if (serviceToken != token) return
            serviceError = error
            serviceWait?.complete(Unit)
        }
    }

    fun onServiceStopped(token: String?) {
        if (token == null) return
        synchronized(lock) {
            if (serviceToken != token || serviceWait == null || serviceError != null) return
            serviceError = IllegalStateException("投屏前台服务提前结束")
            serviceWait?.complete(Unit)
        }
    }

    private suspend fun openProjection(kind: String): LiveProjection? {
        synchronized(lock) {
            if (activeKind != null) throw HaminnException(ErrorCodes.CONFLICT, "已有投屏会话正在进行,请先结束它")
            activeKind = kind
        }
        val requester = consent
        if (requester == null) {
            releaseSlot()
            throw HaminnException(ErrorCodes.INTERNAL, "当前没有可用的页面界面来请求投屏同意", true)
        }
        val consentResult = try {
            requester.request()
        } catch (error: Throwable) {
            releaseSlot()
            throw error
        }
        if (consentResult == null) {
            releaseSlot()
            return null
        }
        val token = UUID.randomUUID().toString()
        try {
            startForegroundService(token)
            val projection = projectionManager.getMediaProjection(Activity.RESULT_OK, consentResult)
                ?: throw HaminnException(ErrorCodes.INTERNAL, "系统没有返回投屏会话", true)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    onProjectionRevoked(token)
                }
            }
            // A callback has to be registered before the first virtual display is created,
            // and it must be gone before we stop the projection ourselves, otherwise the
            // teardown would look like the user revoking the session.
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            return LiveProjection(token, projection, callback)
        } catch (error: Throwable) {
            stopForegroundService()
            releaseSlot()
            throw error as? HaminnException
                ?: HaminnException(ErrorCodes.INTERNAL, error.message ?: "无法建立投屏会话", true)
        }
    }

    private suspend fun startForegroundService(token: String) {
        val ready = CompletableDeferred<Unit>()
        synchronized(lock) {
            serviceWait = ready
            serviceToken = token
            serviceError = null
        }
        val intent = Intent(context, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)
            .putExtra(ScreenCaptureService.EXTRA_TOKEN, token)
        try {
            context.startForegroundService(intent)
        } catch (error: Throwable) {
            synchronized(lock) { clearServiceWaitLocked() }
            throw HaminnException(ErrorCodes.INTERNAL, "无法启动投屏前台服务：${error.message ?: "未知错误"}", true)
        }
        val completed = withTimeoutOrNull(FOREGROUND_READY_TIMEOUT_MS) { ready.await() }
        val failure = synchronized(lock) {
            val error = serviceError
            clearServiceWaitLocked()
            error
        }
        if (failure != null) {
            stopForegroundService()
            throw HaminnException(ErrorCodes.INTERNAL, "投屏前台服务启动失败：${failure.message ?: "未知错误"}", true)
        }
        if (completed == null) {
            stopForegroundService()
            throw HaminnException(ErrorCodes.INTERNAL, "投屏前台服务没有在预期时间内就绪", true)
        }
    }

    private fun clearServiceWaitLocked() {
        serviceWait = null
        serviceToken = null
        serviceError = null
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, ScreenCaptureService::class.java)) }
    }

    private fun releaseSlot() {
        synchronized(lock) { activeKind = null }
    }

    private fun onProjectionRevoked(token: String) {
        val active = synchronized(lock) { recording }
        if (active == null || active.live.token != token) return
        // The user stopped sharing from the status bar, or the system took the session away.
        // Whatever was captured so far is still delivered.
        scope.launch { runCatching { finish(active.id, REASON_REVOKED) } }
    }

    private fun onRecorderInfo(id: String, what: Int) {
        when (what) {
            // Fires once per output file, at 90% of the size limit. It is the only window in
            // which the next file may be queued; missing it ends the recording at the limit.
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> queueNextSegment(id)
            // The previous file has just been closed on its last byte: this is the boundary.
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> rollSegment(id)
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> requestStopFromLimit(id, REASON_DURATION)
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> requestStopFromLimit(id, REASON_BYTES)
        }
    }

    /**
     * Queues the file the recorder switches to when the current segment fills up, which is what
     * makes a segmented recording continuous: the encoder keeps running, so no frame is lost at
     * a boundary the way stopping and restarting it would lose them.
     *
     * Only one file may be queued at a time, and it has to be handed over inside the 90% window.
     * A failure is left to end the recording at the limit with reason `bytes` rather than being
     * papered over, because silently dropping the rest of the capture is the worse outcome.
     */
    private fun queueNextSegment(id: String) {
        val active = synchronized(lock) { recording } ?: return
        if (active.id != id || !active.request.segment) return
        val next = File(recordingDirectory(), "${active.id}.video.${synchronized(active.segmentLock) { active.videoFiles.size }}.mp4")
        synchronized(active.segmentLock) {
            if (active.pendingVideoFile != null) return
            active.pendingVideoFile = next
        }
        runCatching { active.recorder.setNextOutputFile(next) }
            .onFailure { error ->
                Log.w(TAG, "screen recording: cannot queue the next segment, stopping at the limit", error)
                synchronized(active.segmentLock) { active.pendingVideoFile = null }
            }
    }

    /**
     * Settles the segment the recorder just closed.
     *
     * The audio side is not restarted: the mixer writes one continuous stream, so the boundary
     * only has to be recorded as an audio presentation time. Cutting on the mixer's own clock
     * keeps the two tracks of a segment aligned without any wall-clock translation, and the
     * assembly runs off the callback thread because a mux of tens of megabytes would otherwise
     * stall the recording it is serving.
     */
    private fun rollSegment(id: String) {
        val active = synchronized(lock) { recording } ?: return
        if (active.id != id) return
        val boundaryUs = active.audioCapture?.positionUs() ?: 0L
        var closed: File? = null
        var index = 0
        var fromUs = Long.MIN_VALUE
        synchronized(active.segmentLock) {
            val next = active.pendingVideoFile
            if (next != null) {
                closed = active.videoFiles.lastOrNull()
                index = active.videoFiles.size - 1
                fromUs = active.segmentStartUs
                active.videoFiles += next
                active.pendingVideoFile = null
                active.segmentStartUs = boundaryUs
            }
        }
        val video = closed ?: return
        val toUs = boundaryUs
        active.segmentJob = scope.launch {
            deliverSegment(active, video, index, fromUs, toUs)
        }
    }

    /**
     * Assembles one closed segment into a stored file and tells the page about it.
     *
     * A store refusal is the wall a segmented recording eventually reaches, so it ends the
     * session with reason `bytes` instead of continuing to produce segments nobody can keep.
     */
    private suspend fun deliverSegment(active: Recording, video: File, index: Int, fromUs: Long, toUs: Long) {
        withContext(Dispatchers.IO) {
            if (active.abandoned) {
                runCatching { video.delete() }
                return@withContext
            }
            val summary = assembleSegment(active, video, index, fromUs, toUs, active.audioCapture != null)
            synchronized(active.segmentLock) { active.segments += summary }
            listener?.onEvent(active.appId, EVENT_SEGMENT, summary)
            if (active.storeMessage != null) {
                // Cleared first: this stop is requested by the segment task itself, and the
                // session's finalize waits for that task.
                synchronized(active.segmentLock) { active.segmentJob = null }
                requestStopFromLimit(active.id, REASON_BYTES)
            }
        }
    }

    private fun onRecorderError(id: String, what: Int, extra: Int) {
        val active = synchronized(lock) {
            val current = recording
            if (current == null || current.id != id) null else {
                recording = null
                current
            }
        } ?: return
        // Nothing that is on its way in from a rollover may still land in the file store: this
        // recording is being thrown away, and a segment delivered after the fact would be a
        // file the page never asked to keep.
        val delivered = synchronized(active.segmentLock) {
            active.abandoned = true
            active.segments.size
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { active.recorder.reset() }
                runCatching { active.recorder.release() }
                runCatching { active.audioCapture?.stop() }
                closeProjection(active.live)
                stopForegroundService()
                cleanupFiles(active)
            }
            // An encoder error means the picture cannot be trusted, so the segment being written
            // is dropped and the recording id stops being usable. Segments that were already
            // delivered are stored files and are not taken back.
            listener?.onEvent(active.appId, "screen.recording.error", JSONObject()
                .put("recordingId", active.id)
                .put("code", ErrorCodes.INTERNAL)
                .put("deliveredSegments", delivered)
                .put("message", "录屏编码器报错（$what/$extra）,当前分段已丢弃"))
        }
    }

    private fun requestStopFromLimit(id: String, reason: String) {
        scope.launch { runCatching { finish(id, reason) } }
    }

    private suspend fun finish(id: String, reason: String): JSONObject? {
        val active = synchronized(lock) {
            val current = recording
            if (current == null || current.id != id) null else {
                recording = null
                current
            }
        } ?: return null
        // A segment that rolled over moments ago may still be muxing. Its file belongs to this
        // recording's result, so the session waits for it instead of reporting a shorter list.
        active.segmentJob?.join()
        val outcome = withContext(Dispatchers.IO) { runCatching { finalizeRecording(active, reason) } }
        cleanupFiles(active)
        val result = outcome.getOrNull()
        if (result != null) synchronized(lock) { lastResult = Finished(active.id, active.appId, result) }
        if (reason == REASON_PAGE) {
            // The page asked for this stop, so it receives the failure as the response to
            // its own call rather than as an event.
            outcome.getOrThrow()
        }
        if (result == null) {
            val error = outcome.exceptionOrNull()
            listener?.onEvent(active.appId, "screen.recording.error", JSONObject()
                .put("recordingId", active.id)
                .put("code", (error as? HaminnException)?.code ?: ErrorCodes.INTERNAL)
                .put("message", error?.message ?: "录屏失败"))
            return null
        }
        // A stop the page itself asked for is answered by its own call, so only the stops
        // the page did not request are announced as events.
        if (reason != REASON_PAGE) {
            listener?.onEvent(active.appId, "screen.recording.ended", JSONObject()
                .put("recordingId", active.id)
                .put("reason", reason)
                .put("durationMs", result.optLong("durationMs"))
                .put("logicalFileId", result.opt("logicalFileId") ?: JSONObject.NULL)
                // A page that reloaded mid-recording never saw the per-segment events, so the
                // ending carries the whole list as well.
                .put("segments", result.opt("segments") ?: JSONArray()))
        }
        return result
    }

    private fun finalizeRecording(active: Recording, reason: String): JSONObject {
        val durationMs = SystemClock.elapsedRealtime() - active.startedAt
        val current = synchronized(active.segmentLock) { active.videoFiles.lastOrNull() }
        val currentIndex = synchronized(active.segmentLock) { active.videoFiles.size - 1 }
        val fromUs = synchronized(active.segmentLock) { active.segmentStartUs }
        val stopped = runCatching { active.recorder.stop() }.isSuccess
        runCatching { active.recorder.release() }
        val capture = active.audioCapture
        val audioOk = capture?.let { runCatching { it.stop() }.getOrDefault(false) } ?: false
        // Read after the stop so the last window reaches the end of the sound that was captured.
        val endUs = capture?.positionUs() ?: 0L
        closeProjection(active.live)
        stopForegroundService()
        if (capture != null && !audioOk) {
            // Only the segment being closed can be judged here; the ones already delivered were
            // assembled while the pipeline was still running, which is all that was knowable then.
            Log.w(TAG, "screen recording: the audio track is unusable, the last segment loses its sound " +
                "requested=${active.request.audio.wire} opened=${active.deliveredAudio.wire} " +
                "deliveredSegments=$currentIndex")
        }
        val delivered = synchronized(active.segmentLock) { active.segments.toList() }
        val lastFile = current?.takeIf { stopped && it.isFile && it.length() > 0L }
        if (lastFile == null) {
            // The last piece did not come out. The segments already delivered are stored files
            // that no later failure may take away, so they stay and are still reported.
            if (delivered.isEmpty()) throw HaminnException(ErrorCodes.INTERNAL, "录制没有产生可用画面", true)
            return JSONObject()
                .put("logicalFileId", JSONObject.NULL)
                .put("mime", MIME_VIDEO)
                .put("message", "最后一段没有产生可用画面,已交付之前的 ${delivered.size} 段")
                .put("durationMs", durationMs)
                .put("width", active.width)
                .put("height", active.height)
                .put("audio", ScreenAudioMode.NONE.wire)
                .put("stoppedBy", reason)
                .put("segments", JSONArray(delivered))
        }
        val summary = assembleSegment(active, lastFile, currentIndex, fromUs, endUs, audioOk)
        synchronized(active.segmentLock) { active.segments += summary }
        val result = JSONObject()
        summary.keys().forEach { key -> result.put(key, summary.get(key)) }
        val all = synchronized(active.segmentLock) { active.segments.toList() }
        if (active.storeMessage != null) {
            result.put("message", active.storeMessage)
        } else if (summary.opt("message") == null && summary.optString("audio") != active.request.audio.wire) {
            result.put("message", "音频轨没有写成功,已交付无声录制")
        }
        return result
            .put("durationMs", durationMs)
            .put("stoppedBy", reason)
            .put("segments", JSONArray(all))
    }

    /**
     * Turns one video file plus its slice of the audio stream into a stored file and returns the
     * summary that both the segment event and the final result carry.
     *
     * A segment that cannot be stored still yields a summary, so a page learns from the id being
     * null instead of from silence. The reason is kept on the recording because the file store is
     * the one failure a caller can act on.
     */
    private fun assembleSegment(
        active: Recording,
        video: File,
        index: Int,
        fromUs: Long,
        toUs: Long,
        audioOk: Boolean,
    ): JSONObject {
        val withAudio = audioOk && ScreenMediaMux.combine(video, active.audioFile, active.muxFile, fromUs, toUs)
        if (!withAudio && audioOk) {
            Log.w(TAG, "screen recording: segment $index lost its sound, delivering the picture alone")
        }
        val source = if (withAudio) active.muxFile else video
        val trackAudio = if (withAudio) active.deliveredAudio else ScreenAudioMode.NONE
        val summary = JSONObject()
            .put("recordingId", active.id)
            .put("index", index)
            .put("durationMs", segmentDurationMs(fromUs, toUs))
            .put("width", active.width)
            .put("height", active.height)
            .put("audio", trackAudio.wire)
        try {
            val stored = importSegment(active, source, index)
            if (stored != null) {
                stored.keys().forEach { key -> summary.put(key, stored.get(key)) }
            } else {
                summary.put("logicalFileId", JSONObject.NULL)
                summary.put("mime", MIME_VIDEO)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "screen recording: segment $index was not stored", error)
            active.storeMessage = error.message ?: "分段无法写入文件库"
            summary.put("logicalFileId", JSONObject.NULL)
            summary.put("mime", MIME_VIDEO)
            summary.put("message", active.storeMessage)
        }
        runCatching { video.delete() }
        runCatching { active.muxFile.delete() }
        return summary
    }

    private fun segmentDurationMs(fromUs: Long, toUs: Long): Long {
        val start = if (fromUs <= 0L) 0L else fromUs
        return (toUs - start).coerceAtLeast(0L) / 1000L
    }

    private fun discardRecording(active: Recording) {
        runCatching { active.recorder.stop() }
        runCatching { active.recorder.reset() }
        runCatching { active.recorder.release() }
        runCatching { active.audioCapture?.stop() }
        closeProjection(active.live)
        stopForegroundService()
        cleanupFiles(active)
    }

    private fun cleanupFiles(active: Recording) {
        synchronized(active.segmentLock) {
            active.videoFiles.forEach { file -> runCatching { file.delete() } }
            active.pendingVideoFile?.let { file -> runCatching { file.delete() } }
        }
        runCatching { active.audioFile.delete() }
        runCatching { active.muxFile.delete() }
    }

    /**
     * Imports one segment into the generation the recording was started in, unless the instance
     * has moved on since: a reset data generation must not receive a file it can never read.
     * Returns null when the instance is gone, and lets a store refusal travel to the caller.
     */
    private fun importSegment(active: Recording, source: File, index: Int): JSONObject? {
        val instance = registry.getInstance(active.appId) ?: return null
        val generation = instance.activeDataGeneration
        val name = if (active.request.segment) {
            ScreenCapturePlan.segmentName(active.request.name, index + 1)
        } else {
            active.request.name
        }
        return source.inputStream().use { stream ->
            files.import(active.appId, generation, stream, name, MIME_VIDEO)
        }
    }

    private suspend fun grabStill(live: LiveProjection, request: ScreenStillRequest): StillImage {
        val metrics = displayMetrics()
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            // Two images, because acquiring the latest of a single-image reader is illegal.
            2,
        )
        live.imageReader = reader
        live.virtualDisplay = live.projection.createVirtualDisplay(
            "haminn-screen-still",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        val image = awaitFirstImage(reader)
            ?: throw HaminnException(ErrorCodes.INTERNAL, "投屏没有在预期时间内产生画面", true)
        return try {
            withContext(Dispatchers.Default) {
                val bitmap = imageToBitmap(image)
                try {
                    encodeStill(bitmap, request)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            image.close()
        }
    }

    private suspend fun awaitFirstImage(reader: ImageReader): Image? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                reader.setOnImageAvailableListener(null, null)
                if (continuation.isActive) continuation.resume(null)
            }
            reader.setOnImageAvailableListener({ source ->
                source.setOnImageAvailableListener(null, null)
                handler.removeCallbacks(timeout)
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                if (continuation.isActive) continuation.resume(image) else image?.close()
            }, handler)
            handler.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS)
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                reader.setOnImageAvailableListener(null, null)
            }
        }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun encodeStill(source: Bitmap, request: ScreenStillRequest): StillImage {
        val longest = maxOf(source.width, source.height)
        val scale = if (longest > request.maxEdge) request.maxEdge.toDouble() / longest else 1.0
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        try {
            if (request.format == FORMAT_PNG) {
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                if (output.size() > MAX_STILL_BYTES) {
                    throw HaminnException(ErrorCodes.QUOTA, "PNG 截图超过 ${MAX_STILL_BYTES / (1024 * 1024)} MiB,请改用 jpeg 或降低 maxEdge")
                }
                return StillImage(output.toByteArray(), scaled.width, scaled.height, "image/png", "png")
            }
            for (quality in JPEG_QUALITIES) {
                val output = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                if (output.size() <= MAX_STILL_BYTES) {
                    return StillImage(output.toByteArray(), scaled.width, scaled.height, "image/jpeg", "jpg")
                }
            }
            throw HaminnException(ErrorCodes.QUOTA, "截图超过 ${MAX_STILL_BYTES / (1024 * 1024)} MiB,请降低 maxEdge")
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "当前没有可投屏的默认显示", true)
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    private fun resolveVideoSize(displayWidth: Int, displayHeight: Int, scale: Double): Pair<Int, Int> {
        val (alignWidth, alignHeight) = h264Alignment()
        val candidates = ScreenCapturePlan.sizeCandidates(displayWidth, displayHeight, scale, alignWidth, alignHeight)
        return candidates.firstOrNull { supportsVideoSize(it.first, it.second) }
            ?: throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备的 H.264 编码器不支持任何可用画面尺寸")
    }

    private fun h264Encoders(): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }

    private fun h264Alignment(): Pair<Int, Int> {
        val caps = h264Encoders()
            .firstNotNullOfOrNull { codec ->
                runCatching { codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
            }
            ?: return 2 to 2
        return caps.widthAlignment.coerceAtLeast(2) to caps.heightAlignment.coerceAtLeast(2)
    }

    private fun supportsVideoSize(width: Int, height: Int): Boolean =
        h264Encoders().any { codec ->
            val caps = runCatching { codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull()
                ?: return@any false
            runCatching { caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width) }.getOrDefault(false)
        }

    @Suppress("DEPRECATION")
    private fun buildRecorder(
        videoFile: File,
        width: Int,
        height: Int,
        request: ScreenRecordingRequest,
        maxVideoBytes: Long,
        onInfo: (Int) -> Unit,
        onError: (Int, Int) -> Unit,
    ): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(request.frameRate)
            recorder.setVideoEncodingBitRate(request.videoBitRate)
            // The cap has to leave room for the audio track, which this recorder is not
            // writing: the limit counts the whole file that will be assembled.
            recorder.setMaxFileSize(maxVideoBytes)
            recorder.setMaxDuration(request.maxDurationMs.toInt())
            recorder.setOrientationHint(0)
            recorder.setOutputFile(videoFile.absolutePath)
            recorder.setOnInfoListener { _, what, _ -> onInfo(what) }
            recorder.setOnErrorListener { _, what, extra -> onError(what, extra) }
            recorder.prepare()
        } catch (error: Throwable) {
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            throw error
        }
        return recorder
    }

    private fun closeProjection(live: LiveProjection) {
        runCatching { live.virtualDisplay?.release() }
        runCatching { live.imageReader?.close() }
        live.virtualDisplay = null
        live.imageReader = null
        runCatching { live.projection.unregisterCallback(live.callback) }
        runCatching { live.projection.stop() }
        releaseSlot()
    }

    /**
     * There is no public probe for playback-capture support: the framework only reveals the
     * truth once an AudioRecord is actually built against a live projection, and the property
     * key some vendor code reads is not part of the public SDK. Since API 29 the capture API
     * itself always exists, so availability reports that platform contract, and the finished
     * recording reports what this device really handed over in its `audio` field.
     */
    private fun playbackCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun audioModes(): List<ScreenAudioMode> {
        val systemAudio = playbackCaptureSupported()
        val microphone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        return buildList {
            add(ScreenAudioMode.NONE)
            if (systemAudio) add(ScreenAudioMode.SYSTEM)
            if (microphone) add(ScreenAudioMode.MICROPHONE)
            if (systemAudio && microphone) add(ScreenAudioMode.BOTH)
        }
    }

    private fun recordingDirectory(): File = File(context.cacheDir, RECORDING_DIRECTORY).apply { mkdirs() }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * The bridge envelope parses JSON null into a sentinel object, so presence has to be
     * decided here rather than inside the pure request plan.
     */
    private fun value(params: JSONObject, key: String): Any? =
        if (!params.has(key) || params.isNull(key)) null else params.opt(key)

    companion object {
        private const val TAG = "HaminnScreenCapture"
        private const val KIND_CAPTURE = "capture"
        private const val KIND_RECORD = "record"
        private const val REASON_PAGE = "page"
        private const val REASON_DURATION = "duration"
        private const val REASON_BYTES = "bytes"
        private const val REASON_REVOKED = "projection-revoked"
        private const val REASON_HOST = "host-stop"
        private const val EVENT_SEGMENT = "screen.recording.segment"
        private const val RECORDING_DIRECTORY = "screen-recording"
        private const val MIME_VIDEO = "video/mp4"
        private const val FORMAT_PNG = "png"
        private const val FIRST_FRAME_TIMEOUT_MS = 2_000L
        private const val FOREGROUND_READY_TIMEOUT_MS = 5_000L
        private const val MAX_STILL_BYTES = 4 * 1024 * 1024
        private val JPEG_QUALITIES = listOf(85, 70, 50)
    }
}
