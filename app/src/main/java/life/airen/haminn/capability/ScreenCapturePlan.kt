package life.airen.haminn.capability

import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import kotlin.math.roundToInt

/**
 * Which audio sources a screen recording mixes into its single AAC track.
 *
 * Android records what an application plays through `AudioPlaybackCaptureConfiguration`,
 * which only ever yields USAGE_MEDIA, USAGE_GAME and USAGE_UNKNOWN. This enum therefore
 * describes what the caller asked for, not what the device will actually deliver.
 */
enum class ScreenAudioMode(val wire: String) {
    NONE("none"),
    SYSTEM("system"),
    MICROPHONE("microphone"),
    BOTH("both");

    val wantsSystem: Boolean get() = this == SYSTEM || this == BOTH
    val wantsMicrophone: Boolean get() = this == MICROPHONE || this == BOTH

    companion object {
        fun parse(value: String): ScreenAudioMode =
            entries.firstOrNull { it.wire == value }
                ?: throw HaminnException(
                    ErrorCodes.INVALID_ARGUMENT,
                    "音频来源必须是 none, system, microphone 或 both",
                )
    }
}

data class ScreenStillRequest(val maxEdge: Int, val format: String)

data class ScreenRecordingRequest(
    val audio: ScreenAudioMode,
    val maxDurationMs: Long,
    val maxBytes: Long,
    /**
     * When true, reaching [maxBytes] rolls the recording into the next file instead of
     * ending it. The overall session is still bounded by [maxDurationMs], and every segment
     * becomes its own stored file, so a long capture is deliverable in pieces that each fit
     * the file store.
     */
    val segment: Boolean,
    val scale: Double,
    val frameRate: Int,
    val videoBitRate: Int,
    val audioBitRate: Int,
    val name: String,
)

/**
 * Request validation and size arithmetic for `screen.*`.
 *
 * Everything here is pure Kotlin over already-extracted JSON values, so the clamping
 * rules, the encoder-alignment snapping and the byte budget can be unit tested on the
 * JVM. The device-only capture stack in [ScreenCaptureController] is deliberately left
 * thin, so that as much of the decision making as possible is reachable without a device.
 */
object ScreenCapturePlan {
    const val DEFAULT_MAX_DURATION_MS = 180_000L
    const val MIN_MAX_DURATION_MS = 1_000L
    const val MAX_MAX_DURATION_MS = 1_800_000L

    /**
     * [maxBytes] is the cap of **one delivered file**, not of a whole session. A recording
     * dies at the file store's single-object limit, so the ceiling tracks that limit minus
     * the room the combined container and the audio track need.
     *
     * The default stays at 48 MiB on purpose: a single 256 MiB file costs the whole
     * per-application file budget (`FileStore.MAX_APP_FILE_BYTES`), so a caller that wants a
     * long recording should keep the default segment size and ask for `segment` instead of
     * pushing this number up.
     */
    const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024
    const val MIN_MAX_BYTES = 8L * 1024 * 1024
    const val MAX_MAX_BYTES = 256L * 1024 * 1024

    const val DEFAULT_FRAME_RATE = 30
    const val MIN_FRAME_RATE = 15
    const val MAX_FRAME_RATE = 60

    const val DEFAULT_VIDEO_BIT_RATE = 2_000_000
    const val MIN_VIDEO_BIT_RATE = 500_000
    const val MAX_VIDEO_BIT_RATE = 8_000_000

    const val DEFAULT_AUDIO_BIT_RATE = 128_000
    const val MIN_AUDIO_BIT_RATE = 64_000
    const val MAX_AUDIO_BIT_RATE = 192_000

    val SCALES = listOf(1.0, 0.75, 0.5)
    const val DEFAULT_SCALE = 0.5

    const val DEFAULT_STILL_MAX_EDGE = 1600
    const val MIN_STILL_MAX_EDGE = 720
    const val MAX_STILL_MAX_EDGE = 2048
    const val DEFAULT_STILL_FORMAT = "jpeg"
    val STILL_FORMATS = listOf("jpeg", "png")

    const val DEFAULT_RECORDING_NAME = "screen-recording.mp4"
    const val MAX_NAME_CHARS = 120

    /**
     * A 128 kbps audio track is roughly a twelfth of a 2 Mbps video track. Reserving that
     * share keeps the combined MP4 inside the caller's byte budget, instead of discovering
     * the overflow when the import is already too late to recover from.
     */
    const val BYTE_BUDGET_DENOMINATOR = 1.08

    /**
     * Geometry fallback. `MediaRecorder` refuses a size the H.264 encoder cannot encode,
     * so an unsupported display is walked down by these steps until `prepare()` accepts
     * one. Shrinking by a quarter is enough to clear the macroblock alignment rules of the
     * encoders seen in the field.
     */
    const val SCALE_STEP = 0.75
    const val MAX_SCALE_STEPS = 3

    /** Conservative floor tried after the scaled steps, largest first. */
    val FIXED_FALLBACK_SIZES = listOf(1280 to 720, 960 to 540, 720 to 480, 640 to 360)

    fun still(maxEdge: Any?, format: Any?): ScreenStillRequest {
        val edge = longValue(maxEdge, "maxEdge", DEFAULT_STILL_MAX_EDGE.toLong(), MIN_STILL_MAX_EDGE.toLong(), MAX_STILL_MAX_EDGE.toLong()).toInt()
        val requested = stringValue(format, "format") ?: DEFAULT_STILL_FORMAT
        if (requested !in STILL_FORMATS) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "截图格式必须是 jpeg 或 png")
        }
        return ScreenStillRequest(edge, requested)
    }

    /**
     * The audio mode a request asks for. Separate from [recording] so a caller can decide
     * which permission gates to open before the whole request is validated.
     */
    fun requestedAudio(value: Any?): ScreenAudioMode =
        ScreenAudioMode.parse(stringValue(value, "audio") ?: ScreenAudioMode.BOTH.wire)

    /**
     * @param supportedModes audio modes this device can actually deliver, from
     *   `screen.availability`. Asking for a mode the device cannot provide is an
     *   unsupported request, not a silently downgraded one.
     */
    fun recording(
        audio: Any?,
        maxDurationMs: Any?,
        maxBytes: Any?,
        segment: Any?,
        scale: Any?,
        frameRate: Any?,
        videoBitRate: Any?,
        audioBitRate: Any?,
        name: Any?,
        supportedModes: List<ScreenAudioMode>,
    ): ScreenRecordingRequest {
        val requested = requestedAudio(audio)
        if (requested !in supportedModes) {
            throw HaminnException(ErrorCodes.UNSUPPORTED, "当前设备不支持音频来源 ${requested.wire}")
        }
        val chosenScale = doubleValue(scale, "scale") ?: DEFAULT_SCALE
        if (SCALES.none { it == chosenScale }) {
            throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "缩放只能是 ${SCALES.joinToString(", ")} 之一")
        }
        val requestedName = stringValue(name, "name")?.takeIf { it.isNotBlank() } ?: DEFAULT_RECORDING_NAME
        return ScreenRecordingRequest(
            audio = requested,
            maxDurationMs = longValue(maxDurationMs, "maxDurationMs", DEFAULT_MAX_DURATION_MS, MIN_MAX_DURATION_MS, MAX_MAX_DURATION_MS),
            maxBytes = longValue(maxBytes, "maxBytes", DEFAULT_MAX_BYTES, MIN_MAX_BYTES, MAX_MAX_BYTES),
            segment = booleanValue(segment, "segment") ?: false,
            scale = chosenScale,
            frameRate = longValue(frameRate, "frameRate", DEFAULT_FRAME_RATE.toLong(), MIN_FRAME_RATE.toLong(), MAX_FRAME_RATE.toLong()).toInt(),
            videoBitRate = longValue(videoBitRate, "videoBitRate", DEFAULT_VIDEO_BIT_RATE.toLong(), MIN_VIDEO_BIT_RATE.toLong(), MAX_VIDEO_BIT_RATE.toLong()).toInt(),
            audioBitRate = longValue(audioBitRate, "audioBitRate", DEFAULT_AUDIO_BIT_RATE.toLong(), MIN_AUDIO_BIT_RATE.toLong(), MAX_AUDIO_BIT_RATE.toLong()).toInt(),
            name = requestedName.take(MAX_NAME_CHARS),
        )
    }

    /**
     * The stored name of one segment of a session. A caller that asked for a single file
     * keeps the plain name it passed; a caller that asked for segments gets a suffix, so the
     * pieces of one recording are recognisable in the file list without relying on order.
     */
    fun segmentName(base: String, index: Int): String {
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return "$base-p$index"
        return base.substring(0, dot) + "-p" + index + base.substring(dot)
    }

    /** Rounds up to a multiple of [alignment]; an encoder rejects anything else. */
    fun align(value: Int, alignment: Int): Int {
        if (alignment <= 1) return value
        return ((value + alignment - 1) / alignment) * alignment
    }

    /** Scales the captured display, then snaps it to the encoder and never returns a zero size. */
    fun scaleSize(width: Int, height: Int, scale: Double, alignWidth: Int = 2, alignHeight: Int = 2): IntArray {
        val scaledWidth = align((width * scale).roundToInt(), alignWidth).coerceAtLeast(2)
        val scaledHeight = align((height * scale).roundToInt(), alignHeight).coerceAtLeast(2)
        return intArrayOf(scaledWidth, scaledHeight)
    }

    /** The video half of the byte budget, after reserving room for the audio track. */
    fun videoByteBudget(maxBytes: Long): Long = (maxBytes / BYTE_BUDGET_DENOMINATOR).toLong().coerceAtLeast(1L)

    /**
     * Geometry candidates for `MediaRecorder`, most faithful first: the requested scale,
     * then successively smaller scales, then the fixed floor. A candidate is never larger
     * than the requested one, so degrading a recording cannot silently upscale it.
     */
    fun sizeCandidates(width: Int, height: Int, scale: Double, alignWidth: Int = 2, alignHeight: Int = 2): List<Pair<Int, Int>> {
        val scaled = generateSequence(scale) { it * SCALE_STEP }
            .take(MAX_SCALE_STEPS)
            .map { step ->
                val size = scaleSize(width, height, step, alignWidth, alignHeight)
                size[0] to size[1]
            }
            .toList()
        val ceiling = scaled.firstOrNull()?.let { it.first.toLong() * it.second } ?: 0L
        val fixed = FIXED_FALLBACK_SIZES
            .map { align(it.first, alignWidth) to align(it.second, alignHeight) }
            .filter { it.first.toLong() * it.second <= ceiling }
        return (scaled + fixed).distinct()
    }

    private fun longValue(raw: Any?, key: String, default: Long, min: Long, max: Long): Long {
        if (raw == null) return default
        if (raw !is Number) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$key 必须是数字")
        return raw.toLong().coerceIn(min, max)
    }

    private fun doubleValue(raw: Any?, key: String): Double? {
        if (raw == null) return null
        if (raw !is Number) throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$key 必须是数字")
        return raw.toDouble()
    }

    /** Absent means "off"; anything other than a real boolean is a caller mistake. */
    private fun booleanValue(raw: Any?, key: String): Boolean? {
        if (raw == null) return null
        return raw as? Boolean ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$key 必须是布尔值")
    }

    private fun stringValue(raw: Any?, key: String): String? {
        if (raw == null) return null
        return raw as? String ?: throw HaminnException(ErrorCodes.INVALID_ARGUMENT, "$key 必须是字符串")
    }
}
