package life.airen.haminn.capability

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins the recording's video and audio intermediates into the single MP4 the caller
 * receives.
 *
 * `MediaRecorder` wrote the picture and `ScreenAudioCapture` wrote the sound, so the two
 * tracks are copied sample by sample into a new container rather than re-encoded. Both
 * tracks are shifted to start at zero, because they were captured by independent clocks.
 *
 * A segmented recording reuses that same shape once per segment: the audio stays one
 * continuous stream for the whole session and this component hands each video file the
 * slice of sound that belongs to it.
 */
internal object ScreenMediaMux {
    private const val TAG = "HaminnScreenCapture"
    private const val MIN_BUFFER_BYTES = 1 * 1024 * 1024
    private const val MAX_BUFFER_BYTES = 16 * 1024 * 1024

    /**
     * Copies [video] and [audio] into [target]. Returns false when the result could not be
     * produced, in which case [target] is removed and the caller keeps the video alone: a
     * recording that has lost its sound is still worth delivering.
     *
     * [audioFromUs] and [audioToUs] are presentation times on the audio stream and default to
     * the whole of it. They exist for the segmented case, where one continuous audio file
     * feeds every segment and each segment must take only its own window.
     */
    fun combine(
        video: File,
        audio: File?,
        target: File,
        audioFromUs: Long = Long.MIN_VALUE,
        audioToUs: Long = Long.MAX_VALUE,
    ): Boolean {
        if (audio == null || !audio.isFile || audio.length() == 0L) {
            Log.w(TAG, "screen mux: no usable audio track to combine (present=${audio?.isFile} bytes=${audio?.length()})")
            return videoOnly(video, target)
        }
        if (!video.isFile || video.length() == 0L) return false
        val outcome = runCatching {
            val videoExtractor = MediaExtractor()
            val audioExtractor = MediaExtractor()
            var muxer: MediaMuxer? = null
            try {
                videoExtractor.setDataSource(video.absolutePath)
                audioExtractor.setDataSource(audio.absolutePath)
                val videoTrack = findTrack(videoExtractor, "video/") ?: error("视频轨缺失")
                val audioTrack = findTrack(audioExtractor, "audio/") ?: error("音轨缺失")
                videoExtractor.selectTrack(videoTrack)
                audioExtractor.selectTrack(audioTrack)
                muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
                val audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
                muxer.start()
                // The source track and the output track are different numbers: each intermediate
                // holds a single track numbered 0, while the muxer numbers them in the order they
                // were added, so the audio is track 1 there. Passing one index for both jobs reads
                // past the end of the single-track audio file.
                copyTrack(videoExtractor, muxer, videoTrack, videoOut)
                val audioSamples = copyTrack(audioExtractor, muxer, audioTrack, audioOut, audioFromUs, audioToUs)
                // A track with no samples in its window cannot be muxed: the container would hold
                // an empty track, which no player can read. This segment loses its sound instead of
                // the whole recording failing.
                if (audioSamples == 0) error("音轨在这个时间窗内没有采样")
                muxer.stop()
            } finally {
                runCatching { videoExtractor.release() }
                runCatching { audioExtractor.release() }
                runCatching { muxer?.release() }
            }
        }
        outcome.exceptionOrNull()?.let { Log.w(TAG, "screen mux: combine failed, delivering video only", it) }
        val ok = outcome.isSuccess
        if (!ok) runCatching { target.delete() }
        return ok && target.isFile && target.length() > 0L
    }

    /** The picture alone, used both as a fallback and when no audio was requested. */
    fun videoOnly(video: File, target: File): Boolean {
        if (!video.isFile || video.length() == 0L) return false
        val ok = runCatching { video.copyTo(target, overwrite = true) }.isSuccess
        if (!ok) runCatching { target.delete() }
        return ok && target.isFile && target.length() > 0L
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return index
        }
        return null
    }

    /**
     * Copies the samples of [sourceTrack] from [extractor] into [targetTrack] of [muxer] and
     * reports how many were written.
     *
     * [fromUs] and [toUs] are presentation times on the source; the first sample inside the
     * window becomes the origin the written track is shifted to, so a slice always starts at
     * zero while the video keeps its own origin. The defaults take everything.
     *
     * The two track indices are not interchangeable, so both are passed explicitly rather
     * than assumed equal.
     */
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        sourceTrack: Int,
        targetTrack: Int,
        fromUs: Long = Long.MIN_VALUE,
        toUs: Long = Long.MAX_VALUE,
    ): Int {
        val format = extractor.getTrackFormat(sourceTrack)
        val declared = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        var buffer = ByteBuffer.allocate(declared.coerceIn(MIN_BUFFER_BYTES, MAX_BUFFER_BYTES))
        val info = MediaCodec.BufferInfo()
        var firstPresentationTimeUs = -1L
        var written = 0
        if (fromUs > 0L) {
            // Jump to the sync point at or before the window so a late segment does not scan the
            // whole stream from zero just to throw the first minutes away.
            runCatching { extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) }
        }
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) return written
            // A sample that fills the buffer may have been truncated, and a truncated frame
            // would corrupt the file instead of failing loudly. Re-read it with more room.
            if (size >= buffer.capacity() && buffer.capacity() < MAX_BUFFER_BYTES) {
                val grown = (buffer.capacity().toLong() * 2).coerceAtMost(MAX_BUFFER_BYTES.toLong()).toInt()
                buffer = ByteBuffer.allocate(grown)
                continue
            }
            val presentationTimeUs = extractor.sampleTime
            if (presentationTimeUs < fromUs) {
                extractor.advance()
                continue
            }
            if (presentationTimeUs >= toUs) return written
            if (firstPresentationTimeUs < 0L) firstPresentationTimeUs = presentationTimeUs
            info.offset = 0
            info.size = size
            info.presentationTimeUs = presentationTimeUs - firstPresentationTimeUs
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(targetTrack, buffer, info)
            written++
            extractor.advance()
        }
    }
}
