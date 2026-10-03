package life.airen.haminn

import life.airen.haminn.capability.ScreenAudioMode
import life.airen.haminn.capability.ScreenCapturePlan
import life.airen.haminn.model.ErrorCodes
import life.airen.haminn.model.HaminnException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request arithmetic that decides what a screen capture or recording actually asks the
 * device for. Everything here runs without a device, which is exactly why the clamping and
 * the geometry fallback live in a pure module in the first place.
 */
class ScreenCapturePlanTest {
    private val allModes = listOf(ScreenAudioMode.NONE, ScreenAudioMode.SYSTEM, ScreenAudioMode.MICROPHONE, ScreenAudioMode.BOTH)

    private fun recording(
        audio: Any? = null,
        maxDurationMs: Any? = null,
        maxBytes: Any? = null,
        segment: Any? = null,
        scale: Any? = null,
        frameRate: Any? = null,
        videoBitRate: Any? = null,
        audioBitRate: Any? = null,
        name: Any? = null,
        supported: List<ScreenAudioMode> = allModes,
    ) = ScreenCapturePlan.recording(audio, maxDurationMs, maxBytes, segment, scale, frameRate, videoBitRate, audioBitRate, name, supported)

    private fun codeOf(block: () -> Unit): String {
        val error = assertThrows(HaminnException::class.java) { block() }
        return error.code
    }

    @Test fun aScreenshotDefaultsToJpegAtTheDocumentedEdge() {
        val request = ScreenCapturePlan.still(null, null)
        assertEquals(1600, request.maxEdge)
        assertEquals("jpeg", request.format)
        assertEquals(2048, ScreenCapturePlan.still(999_999, null).maxEdge)
        assertEquals(720, ScreenCapturePlan.still(1, null).maxEdge)
        assertEquals("png", ScreenCapturePlan.still(null, "png").format)
    }

    @Test fun aScreenshotRejectsValuesThatAreNotNumbersOrKnownFormats() {
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { ScreenCapturePlan.still("1600", null) })
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { ScreenCapturePlan.still(null, "webp") })
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { ScreenCapturePlan.still(null, 1) })
    }

    @Test fun aRecordingDefaultsToBothAudioSourcesWithinTheBudgetsThatKeepItDeliverable() {
        val request = recording()
        assertEquals(ScreenAudioMode.BOTH, request.audio)
        assertEquals(180_000L, request.maxDurationMs)
        assertEquals(48L * 1024 * 1024, request.maxBytes)
        assertEquals(0.5, request.scale, 0.0)
        assertEquals(30, request.frameRate)
        assertEquals(2_000_000, request.videoBitRate)
        assertEquals(128_000, request.audioBitRate)
        assertEquals("screen-recording.mp4", request.name)
        // A caller that says nothing still gets exactly one file: segmentation is opt-in.
        assertFalse(request.segment)
    }

    @Test fun aRecordingClampsEveryNumberIntoTheRangeTheEncodersAndTheFileStoreAccept() {
        val low = recording(maxDurationMs = 1, maxBytes = 1, frameRate = 1, videoBitRate = 1, audioBitRate = 1)
        assertEquals(ScreenCapturePlan.MIN_MAX_DURATION_MS, low.maxDurationMs)
        assertEquals(ScreenCapturePlan.MIN_MAX_BYTES, low.maxBytes)
        assertEquals(ScreenCapturePlan.MIN_FRAME_RATE, low.frameRate)
        assertEquals(ScreenCapturePlan.MIN_VIDEO_BIT_RATE, low.videoBitRate)
        assertEquals(ScreenCapturePlan.MIN_AUDIO_BIT_RATE, low.audioBitRate)

        val high = recording(maxDurationMs = 99_999_999, maxBytes = Long.MAX_VALUE, frameRate = 999, videoBitRate = 99_999_999, audioBitRate = 999_999)
        assertEquals(ScreenCapturePlan.MAX_MAX_DURATION_MS, high.maxDurationMs)
        assertEquals(ScreenCapturePlan.MAX_FRAME_RATE, high.frameRate)
        // The file store has no byte quota; the recording API accepts its full Long range.
        assertEquals(ScreenCapturePlan.MAX_MAX_BYTES, high.maxBytes)
        assertEquals((high.maxBytes / ScreenCapturePlan.BYTE_BUDGET_DENOMINATOR).toLong(), ScreenCapturePlan.videoByteBudget(high.maxBytes))
        assertTrue(ScreenCapturePlan.videoByteBudget(high.maxBytes) < high.maxBytes)
        assertEquals(ScreenCapturePlan.MAX_VIDEO_BIT_RATE, high.videoBitRate)
        assertEquals(ScreenCapturePlan.MAX_AUDIO_BIT_RATE, high.audioBitRate)
    }

    @Test fun aRecordingOnlyRollsOverWhenTheCallerAsksForSegments() {
        assertFalse(recording().segment)
        assertTrue(recording(segment = true).segment)
        assertFalse(recording(segment = false).segment)
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { recording(segment = "true") })
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { recording(segment = 1) })
    }

    @Test fun segmentNamesKeepTheExtensionAndTheBaseName() {
        assertEquals("clip-p1.mp4", ScreenCapturePlan.segmentName("clip.mp4", 1))
        assertEquals("clip-p2.mp4", ScreenCapturePlan.segmentName("clip.mp4", 2))
        assertEquals("screen-recording-p11.mp4", ScreenCapturePlan.segmentName("screen-recording.mp4", 11))
        // Several dots: the suffix goes next to the extension, not in front of the first dot.
        assertEquals("my.capture-p3.mp4", ScreenCapturePlan.segmentName("my.capture.mp4", 3))
        // Nothing to split on: the suffix is simply appended, so the name is still unique.
        assertEquals("clip-p1", ScreenCapturePlan.segmentName("clip", 1))
        assertEquals(".hidden-p1", ScreenCapturePlan.segmentName(".hidden", 1))
    }

    @Test fun aRecordingRefusesASourceTheDeviceDoesNotHave() {
        val withoutMicrophone = listOf(ScreenAudioMode.NONE, ScreenAudioMode.SYSTEM)
        assertEquals(
            ErrorCodes.UNSUPPORTED,
            codeOf { recording(audio = "both", supported = withoutMicrophone) },
        )
        assertEquals(ScreenAudioMode.SYSTEM, recording(audio = "system", supported = withoutMicrophone).audio)
        assertEquals(
            ErrorCodes.INVALID_ARGUMENT,
            codeOf { recording(audio = "screen", supported = allModes) },
        )
        assertEquals(
            ErrorCodes.INVALID_ARGUMENT,
            codeOf { recording(audio = 1, supported = allModes) },
        )
    }

    @Test fun aRecordingOnlyAcceptsTheScalesItAdvertises() {
        assertEquals(1.0, recording(scale = 1.0).scale, 0.0)
        assertEquals(0.75, recording(scale = 0.75).scale, 0.0)
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { recording(scale = 0.6) })
        assertEquals(ErrorCodes.INVALID_ARGUMENT, codeOf { recording(scale = "0.5") })
    }

    @Test fun aRecordingNameSurvivesBlanksAndAbsurdLengths() {
        assertEquals("screen-recording.mp4", recording(name = "   ").name)
        assertEquals("my-clip.mp4", recording(name = "my-clip.mp4").name)
        assertEquals(ScreenCapturePlan.MAX_NAME_CHARS, recording(name = "x".repeat(500)).name.length)
    }

    @Test fun theVideoBudgetLeavesRoomForTheAudioTrack() {
        val budget = ScreenCapturePlan.videoByteBudget(48L * 1024 * 1024)
        assertTrue("video budget must be smaller than the whole budget", budget < 48L * 1024 * 1024)
        assertTrue("video budget must still be most of the budget", budget > 40L * 1024 * 1024)
        assertEquals(46_603_377L, budget)
        assertEquals(1L, ScreenCapturePlan.videoByteBudget(0L))
    }

    @Test fun geometryIsSnappedUpToWhatAnEncoderWillAccept() {
        assertEquals(540, ScreenCapturePlan.align(540, 2))
        assertEquals(406, ScreenCapturePlan.align(405, 2))
        assertEquals(544, ScreenCapturePlan.align(540, 16))
        assertEquals(540, ScreenCapturePlan.align(540, 1))
        // 1080 is not a multiple of the 16-pixel alignment some encoders demand.
        assertEquals(1088, ScreenCapturePlan.align(1080, 16))
        assertEquals(1024, ScreenCapturePlan.align(1024, 16))

        val half = ScreenCapturePlan.scaleSize(1080, 2400, 0.5)
        assertEquals(540, half[0])
        assertEquals(1200, half[1])
        val aligned = ScreenCapturePlan.scaleSize(1080, 2400, 0.5, 16, 16)
        assertEquals(544, aligned[0])
        assertEquals(1200, aligned[1])
        // A tiny capture still has to describe at least one visible pixel pair.
        val tiny = ScreenCapturePlan.scaleSize(2, 2, 0.1)
        assertEquals(2, tiny[0])
        assertEquals(2, tiny[1])
    }

    @Test fun geometryFallbackWalksDownFromTheRequestedScaleAndNeverUpscales() {
        val candidates = ScreenCapturePlan.sizeCandidates(1080, 2400, 0.5)
        assertEquals(
            listOf(540 to 1200, 406 to 900, 304 to 676, 960 to 540, 720 to 480, 640 to 360),
            candidates,
        )
        val primaryArea = 540L * 1200
        assertTrue("a degraded recording must never be larger than what was asked for",
            candidates.all { it.first.toLong() * it.second <= primaryArea })

        // A small display cannot be described by larger fixed sizes, and the primary size
        // must not be repeated when a fallback happens to equal it.
        val small = ScreenCapturePlan.sizeCandidates(640, 360, 1.0)
        assertEquals(listOf(640 to 360, 480 to 270, 360 to 204), small)
        assertEquals(small.size, small.distinct().size)
    }
}
