package life.airen.haminn.capability

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The recording audio track: one to two capture sources, mixed sample by sample into a
 * single AAC stream.
 *
 * `MediaRecorder` accepts only one audio source, so it cannot mix playback capture with
 * the microphone. The track is therefore built by hand and muxed with the video later.
 * The mix runs on a wall clock instead of on the slowest source, so a source that fails
 * or stalls reduces that source to silence rather than shortening the recording.
 */
internal class ScreenAudioCapture private constructor(
    val mode: ScreenAudioMode,
    val sampleRate: Int,
    val channels: Int,
    private val output: File,
    private val sources: List<Source>,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    private class Source(val record: AudioRecord, val capacity: Int) {
        private val ring = ByteArray(capacity)
        private var head = 0
        private var size = 0

        /** Appends what the reader got, discarding the oldest audio first if it overflows. */
        @Synchronized
        fun offer(data: ByteArray, length: Int) {
            if (length <= 0) return
            val keep = minOf(length, capacity)
            val start = length - keep
            val overflow = size + keep - capacity
            if (overflow > 0) {
                head = (head + overflow) % capacity
                size -= overflow
            }
            var tail = (head + size) % capacity
            var written = 0
            while (written < keep) {
                val run = minOf(keep - written, capacity - tail)
                System.arraycopy(data, start + written, ring, tail, run)
                tail = (tail + run) % capacity
                written += run
            }
            size += keep
        }

        @Synchronized
        fun poll(target: ByteArray, max: Int): Int {
            val take = minOf(size, max)
            var read = 0
            var index = head
            while (read < take) {
                val run = minOf(take - read, capacity - index)
                System.arraycopy(ring, index, target, read, run)
                index = (index + run) % capacity
                read += run
            }
            head = (head + take) % capacity
            size -= take
            return take
        }

        fun start() = record.startRecording()

        fun release() {
            runCatching { record.stop() }
            record.release()
        }
    }

    private val frameBytes = channels * 2
    private val chunkFrames = (sampleRate / CHUNKS_PER_SECOND).coerceAtLeast(1)
    private val chunkBytes = chunkFrames * frameBytes
    private val bufferA = ByteArray(chunkBytes)
    private val bufferB = ByteArray(chunkBytes)
    private val mixed = ByteArray(chunkBytes)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val running = AtomicBoolean(false)
    private val readerScratch = ByteArray(RING_BYTES / 4)

    @Volatile private var failed = false
    @Volatile private var muxerStarted = false
    /**
     * Frames handed to the encoder since the pipeline started. It is the seam a segmented
     * recording cuts on: the audio stays one continuous stream, and each segment takes the
     * samples that belong to its own time window, so a rollover never leaves a hole in the
     * sound the way restarting the codec would.
     */
    @Volatile private var framesProduced = 0L
    private var trackIndex = -1
    private var thread: Thread? = null

    /** How far into the audio stream the mixer has written, in microseconds. */
    fun positionUs(): Long = framesProduced * 1_000_000L / sampleRate

    fun start() {
        if (!running.compareAndSet(false, true)) return
        codec.start()
        sources.forEach { it.start() }
        thread = Thread({ mixLoop() }, "haminn-screen-audio").also { it.start() }
    }

    /**
     * Ends the pipeline and reports whether the produced file holds a usable audio track.
     * A false result means the caller should keep the video and report `audio: "none"`;
     * it never means the recording itself failed.
     */
    fun stop(): Boolean {
        if (running.compareAndSet(true, false)) {
            thread?.let { runCatching { it.join(STOP_JOIN_MS) } }
        }
        thread = null
        sources.forEach { runCatching { it.release() } }
        runCatching { finishEncoder() }.onFailure { Log.w(TAG, "screen audio: encoder teardown failed", it) }
        val usable = !failed && muxerStarted && output.isFile && output.length() > 0L
        // A track that is dropped is still a recording the caller gets, so the reason it was
        // dropped has to survive somewhere: otherwise the only trace is the caller's message.
        if (!usable) {
            Log.w(TAG, "screen audio: track unusable, failed=$failed muxerStarted=$muxerStarted " +
                "file=${output.isFile} bytes=${output.length()} mode=$mode")
        }
        return usable
    }

    private fun mixLoop() {
        val startNanos = System.nanoTime()
        try {
            while (running.get()) {
                val owed = ScreenAudioMixer.pcmFramesFor(System.nanoTime() - startNanos, sampleRate)
                var catchUp = 0
                while (running.get() && framesProduced + chunkFrames <= owed && catchUp < MAX_CATCH_UP_CHUNKS) {
                    readSources()
                    emitChunk(framesProduced)
                    framesProduced += chunkFrames
                    catchUp++
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            // The encoder or the muxer gave up. The video is unaffected, and the track is
            // reported as absent instead of discarding a recording that already exists.
            Log.w(TAG, "screen audio: mix loop stopped early", error)
            failed = true
        }
    }

    private fun readSources() {
        for (source in sources) {
            val read = runCatching {
                source.record.read(readerScratch, 0, readerScratch.size, AudioRecord.READ_NON_BLOCKING)
            }.getOrDefault(0)
            if (read > 0) source.offer(readerScratch, read)
        }
    }

    private fun emitChunk(framesProduced: Long) {
        val first = sources[0]
        val second = sources.getOrNull(1)
        val firstBytes = first.poll(bufferA, chunkBytes)
        val secondBytes = second?.poll(bufferB, chunkBytes) ?: 0
        val produced = ScreenAudioMixer.mix(bufferA, firstBytes, bufferB, secondBytes, mixed)
        ScreenAudioMixer.fillSilence(mixed, produced, chunkBytes)
        queueEncoded(chunkBytes, framesProduced)
    }

    private fun queueEncoded(bytes: Int, framesProduced: Long) {
        val index = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
        if (index < 0) return
        val ptsUs = framesProduced * 1_000_000L / sampleRate
        codec.getInputBuffer(index)?.let { buffer: ByteBuffer ->
            buffer.clear()
            buffer.put(mixed, 0, bytes)
        }
        codec.queueInputBuffer(index, 0, bytes, ptsUs, 0)
        drainEncoder(false)
    }

    /**
     * Moves encoded audio into the muxer. The codec only publishes its output format once,
     * and the muxer refuses samples before its track exists, so the format change is what
     * opens the track.
     */
    private fun drainEncoder(waitForEndOfStream: Boolean) {
        var budgetMs = if (waitForEndOfStream) EOS_DRAIN_BUDGET_MS else 0L
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (waitForEndOfStream) ENCODER_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEndOfStream) return
                    budgetMs -= ENCODER_TIMEOUT_US / 1_000L
                    if (budgetMs <= 0L) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig && trackIndex >= 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun finishEncoder() {
        runCatching {
            val index = codec.dequeueInputBuffer(ENCODER_TIMEOUT_US)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        runCatching { drainEncoder(true) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    companion object {
        private const val TAG = "HaminnScreenCapture"
        private const val CHUNKS_PER_SECOND = 50
        private const val POLL_INTERVAL_MS = 5L
        private const val MAX_CATCH_UP_CHUNKS = 8
        private const val STOP_JOIN_MS = 1_000L
        private const val ENCODER_TIMEOUT_US = 20_000L
        private const val EOS_DRAIN_BUDGET_MS = 500L
        private const val RING_BYTES = 128 * 1024

        /**
         * 48 kHz stereo is what playback capture is documented to deliver; the fallbacks
         * exist for devices whose mixer refuses that combination.
         */
        private val FORMATS = listOf(48_000 to 2, 44_100 to 2, 44_100 to 1)

        /**
         * Builds and starts the audio pipeline, or returns null when this device cannot
         * capture the requested sources at all.
         *
         * Order matters: the requested mode is tried first, and only then does `both`
         * degrade, preferring the microphone because it is the source a caller is more
         * likely to have meant.
         */
        fun open(
            projection: MediaProjection,
            requested: ScreenAudioMode,
            bitRate: Int,
            output: File,
        ): ScreenAudioCapture? {
            for (mode in ladder(requested)) {
                for ((sampleRate, channels) in FORMATS) {
                    val attempt = runCatching { build(projection, mode, sampleRate, channels, bitRate, output) }
                    attempt.getOrNull()?.let { capture ->
                        runCatching { capture.start() }
                            .onSuccess { return capture }
                        runCatching { capture.stop() }
                    }
                    runCatching { output.delete() }
                }
            }
            return null
        }

        private fun ladder(requested: ScreenAudioMode): List<ScreenAudioMode> = when (requested) {
            ScreenAudioMode.NONE -> emptyList()
            ScreenAudioMode.BOTH -> listOf(ScreenAudioMode.BOTH, ScreenAudioMode.MICROPHONE, ScreenAudioMode.SYSTEM)
            else -> listOf(requested)
        }

        private fun build(
            projection: MediaProjection,
            mode: ScreenAudioMode,
            sampleRate: Int,
            channels: Int,
            bitRate: Int,
            output: File,
        ): ScreenAudioCapture {
            val sources = mutableListOf<Source>()
            try {
                if (mode.wantsSystem) {
                    sources += source(sampleRate, channels, playbackConfig(projection))
                }
                if (mode.wantsMicrophone) {
                    sources += source(sampleRate, channels) { it.setAudioSource(MediaRecorder.AudioSource.MIC) }
                }
                if (sources.isEmpty()) throw IllegalStateException("没有可用的采集音源")
                val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val codec = try {
                    encoder(sampleRate, channels, bitRate)
                } catch (error: Throwable) {
                    muxer.release()
                    throw error
                }
                return ScreenAudioCapture(mode, sampleRate, channels, output, sources, codec, muxer)
            } catch (error: Throwable) {
                sources.forEach { runCatching { it.release() } }
                throw error
            }
        }

        private fun source(
            sampleRate: Int,
            channels: Int,
            configure: (AudioRecord.Builder) -> Unit,
        ): Source {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (minBytes <= 0) throw IllegalStateException("设备不支持 ${sampleRate}Hz ${channels}声道的采集格式")
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()
            val builder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBytes * 2)
            configure(builder)
            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("采集音源无法初始化")
            }
            return Source(record, maxOf(minBytes * 4, RING_BYTES))
        }

        private fun playbackConfig(projection: MediaProjection): (AudioRecord.Builder) -> Unit = { builder ->
            builder.setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    // Only these three usages can ever be captured; naming them keeps the
                    // limitation visible where it is enforced.
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build(),
            )
        }

        private fun encoder(sampleRate: Int, channels: Int, bitRate: Int): MediaCodec {
            val maxInput = (sampleRate / CHUNKS_PER_SECOND) * channels * 2
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (error: Throwable) {
                codec.release()
                throw error
            }
            return codec
        }
    }
}
