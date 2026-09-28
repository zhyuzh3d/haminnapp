package life.airen.haminn

import life.airen.haminn.capability.ScreenAudioMixer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mixing arithmetic of the recording audio track. Samples are interleaved 16-bit PCM,
 * so the same code path covers mono and stereo: the arithmetic counts samples, and a stereo
 * frame is simply two of them.
 */
class ScreenAudioMixerTest {
    private fun pcm(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun samplesOf(bytes: ByteArray, count: Int): IntArray =
        IntArray(count) { index ->
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt() and 0xFF
            ((high shl 8) or low).toShort().toInt()
        }

    @Test fun aChunkIsDueAtTheWallClockRateOfTheTrack() {
        assertEquals(48_000L, ScreenAudioMixer.pcmFramesFor(1_000_000_000L, 48_000))
        assertEquals(24_000L, ScreenAudioMixer.pcmFramesFor(500_000_000L, 48_000))
        assertEquals(0L, ScreenAudioMixer.pcmFramesFor(0L, 48_000))
        assertEquals(0L, ScreenAudioMixer.pcmFramesFor(-1L, 48_000))
        assertEquals(0L, ScreenAudioMixer.pcmFramesFor(1_000_000_000L, 0))
    }

    @Test fun twoSourcesAreSummedSampleBySample() {
        val a = pcm(1000, -1000, 0)
        val b = pcm(2000, -2000, 500)
        val out = ByteArray(6)
        val written = ScreenAudioMixer.mix(a, a.size, b, b.size, out)
        assertEquals(6, written)
        assertArrayEquals(intArrayOf(3000, -3000, 500), samplesOf(out, 3))
    }

    @Test fun aStereoFrameIsJustTwoInterleavedSamples() {
        val system = pcm(100, 200, -100, -200)
        val microphone = pcm(50, 60, -50, -60)
        val out = ByteArray(8)
        val written = ScreenAudioMixer.mix(system, system.size, microphone, microphone.size, out)
        assertEquals(8, written)
        assertArrayEquals(intArrayOf(150, 260, -150, -260), samplesOf(out, 4))
    }

    @Test fun aLoudMixClipsInsteadOfWrappingAround() {
        val a = pcm(30000, -30000, 32767, -32768)
        val b = pcm(30000, -30000, 1, -1)
        val out = ByteArray(8)
        ScreenAudioMixer.mix(a, a.size, b, b.size, out)
        // Without saturation 30000 + 30000 would wrap to -5536 and turn loud content into
        // noise, which is far worse than clipping it.
        assertArrayEquals(intArrayOf(32767, -32768, 32767, -32768), samplesOf(out, 4))
    }

    @Test fun aSourceThatStoppedContributingBecomesSilenceWithoutShorteningTheMix() {
        val microphone = pcm(111, 222, 333)
        val stopped = pcm(10)
        val out = ByteArray(6)
        val written = ScreenAudioMixer.mix(microphone, microphone.size, stopped, stopped.size, out)
        assertEquals(6, written)
        assertArrayEquals(intArrayOf(121, 222, 333), samplesOf(out, 3))

        val alone = ByteArray(6)
        assertEquals(6, ScreenAudioMixer.mix(microphone, microphone.size, null, 0, alone))
        assertArrayEquals(intArrayOf(111, 222, 333), samplesOf(alone, 3))
    }

    @Test fun aPartialSampleIsIgnoredRatherThanReadPastTheBuffer() {
        val a = pcm(100, 200)
        // Only the first three bytes of the second source are offered, so its second sample
        // is incomplete and must contribute silence instead of a stray decoded byte.
        val b = pcm(1, 0)
        val out = ByteArray(8)
        val written = ScreenAudioMixer.mix(a, a.size, b, 3, out)
        assertEquals(4, written)
        assertArrayEquals(intArrayOf(101, 200), samplesOf(out, 2))
    }

    @Test fun silencePaddingKeepsTheTrackOnTheWallClock() {
        val out = pcm(1, 2, 3, 4)
        val filled = ScreenAudioMixer.fillSilence(out, 4, 8)
        assertEquals(4, filled)
        assertArrayEquals(intArrayOf(1, 2, 0, 0), samplesOf(out, 4))

        val fromStart = ByteArray(8)
        assertEquals(8, ScreenAudioMixer.fillSilence(fromStart, 0, 8))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), samplesOf(fromStart, 4))

        // A range outside the buffer is clamped rather than allowed to throw mid-recording.
        assertEquals(0, ScreenAudioMixer.fillSilence(fromStart, 8, 16))
        assertEquals(0, ScreenAudioMixer.fillSilence(fromStart, 16, 24))
    }

    @Test fun theOutputIsNeverAllowedToGrowPastTheBufferItWasGiven() {
        val a = pcm(1, 2, 3, 4)
        val out = ByteArray(4)
        assertEquals(4, ScreenAudioMixer.mix(a, a.size, null, 0, out))
        assertEquals(0, ScreenAudioMixer.mix(ByteArray(0), 0, null, 0, out))
        assertEquals(8, ScreenAudioMixer.evenBytes(9))
        assertEquals(8, ScreenAudioMixer.evenBytes(8))
    }
}
