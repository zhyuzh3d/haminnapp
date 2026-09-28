package life.airen.haminn.capability

/**
 * The arithmetic behind the recording audio track.
 *
 * Samples are interleaved little-endian 16-bit PCM, so one pass of addition covers any
 * channel count: the arithmetic never has to know whether it is mixing mono or stereo.
 * Mixing two sources that are both at full scale would wrap around and turn loud content
 * into noise, so the sum saturates instead.
 */
object ScreenAudioMixer {
    const val MAX_SAMPLE = 32_767
    const val MIN_SAMPLE = -32_768

    /** Frames a source should have delivered this far into the recording. */
    fun pcmFramesFor(elapsedNanos: Long, sampleRate: Int): Long {
        if (elapsedNanos <= 0L || sampleRate <= 0) return 0L
        return elapsedNanos * sampleRate / 1_000_000_000L
    }

    /** A buffer can only be offered back in whole samples. */
    fun evenBytes(bytes: Int): Int = bytes - (bytes and 1)

    /**
     * Sums [a] and [b] into [out] with saturation.
     *
     * Reaching the end of either input is not an error: a source that stalled or failed
     * contributes silence from that point on, which is what keeps the track's duration
     * tied to the wall clock instead of to the slowest capture source. Returns the number
     * of bytes written, which is the longer aligned input, never more than `out.size`.
     */
    fun mix(a: ByteArray, aBytes: Int, b: ByteArray?, bBytes: Int, out: ByteArray): Int {
        val length = evenBytes(maxOf(aBytes, bBytes)).coerceAtMost(evenBytes(out.size))
        var index = 0
        while (index < length) {
            val sum = sampleAt(a, aBytes, index) + sampleAt(b, bBytes, index)
            val clamped = when {
                sum > MAX_SAMPLE -> MAX_SAMPLE
                sum < MIN_SAMPLE -> MIN_SAMPLE
                else -> sum
            }
            out[index] = (clamped and 0xFF).toByte()
            out[index + 1] = ((clamped shr 8) and 0xFF).toByte()
            index += 2
        }
        return length
    }

    /**
     * Zeroes `out[from, to)` and reports how many bytes that was. This is how a chunk that
     * no source filled keeps the recording's audio timeline on the wall clock instead of
     * shortening the track whenever a source stalls.
     */
    fun fillSilence(out: ByteArray, from: Int, to: Int): Int {
        val start = from.coerceIn(0, evenBytes(out.size))
        val end = evenBytes(to).coerceIn(start, evenBytes(out.size))
        java.util.Arrays.fill(out, start, end, 0.toByte())
        return end - start
    }

    private fun sampleAt(buffer: ByteArray?, available: Int, index: Int): Int {
        if (buffer == null || index + 1 >= available) return 0
        val low = buffer[index].toInt() and 0xFF
        val high = buffer[index + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort().toInt()
    }
}
