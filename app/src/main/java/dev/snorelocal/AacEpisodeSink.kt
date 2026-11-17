package dev.snorelocal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteOrder
import java.util.UUID

/** Streaming AAC-LC in MPEG-4; only the detected episode reaches disk. */
class AacEpisodeSink(private val repository: Repository, private val session: String, private val logicalEpisodeId: () -> String? = { null }, private val onCommitted: () -> Unit = {}, private val anchorMs: () -> Long) : EpisodeEngine.Sink {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var started = false
    private var id = ""
    private var startSample = 0L
    private var currentLogicalEpisode: String? = null
    private var samples = 0L
    private var temporary: File? = null
    val pendingBytes: Long get() = temporary?.length() ?: 0L
    private val info = MediaCodec.BufferInfo()
    override fun open(startSample: Long) {
        repository.checkStorage()
        currentLogicalEpisode = logicalEpisodeId()
        this.startSample = startSample; samples = 0; track = -1; started = false
        id = UUID.randomUUID().toString()
        temporary = File(repository.directory, "$id.part")
        try {
            muxer = MediaMuxer(temporary!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            run {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Yamnet.RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
                }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
            }
        } catch (t: Throwable) { abort(); throw IllegalStateException("AAC encoder unavailable: ${t.message}", t) }
    }
    override fun write(pcm: ShortArray, offset: Int, count: Int) {
        val encoder = checkNotNull(codec)
        var at = offset
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (at < offset + count) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = checkNotNull(encoder.getInputBuffer(index)).apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                val n = minOf(buffer.remaining() / 2, offset + count - at)
                check(n > 0) { "AAC input buffer empty" }
                for (i in at until at + n) buffer.putShort(pcm[i])
                encoder.queueInputBuffer(index, 0, n * 2, samples * 1_000_000 / Yamnet.RATE, 0)
                samples += n; at += n
            }
            drain(false)
            check(SystemClock.elapsedRealtime() < deadline) { "AAC encoder stalled" }
        }
    }
    private fun drain(eos: Boolean): Boolean {
        val encoder = checkNotNull(codec)
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "AAC format changed twice" }
                    track = checkNotNull(muxer).addTrack(encoder.outputFormat)
                    checkNotNull(muxer).start(); started = true
                }
                index >= 0 -> {
                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        check(started) { "AAC output before format" }
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        checkNotNull(muxer).writeSampleData(track, buffer, info)
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (done) return true
                }
            }
        }
    }
    override fun close(reason: String) {
        val encoder = codec ?: return
        try {
            val deadline = SystemClock.elapsedRealtime() + 5000
            var queued = false
            var done = false
            while (!done) {
                if (!queued) {
                    val i = encoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        encoder.queueInputBuffer(i, 0, 0, samples * 1_000_000 / Yamnet.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    }
                }
                done = drain(true)
                check(SystemClock.elapsedRealtime() < deadline) { "AAC finalization timed out" }
            }
            check(started && samples > 0) { "Empty AAC episode" }
            checkNotNull(muxer).stop()
            release()
            val output = repository.file(id)
            check(checkNotNull(temporary).renameTo(output)) { "Cannot commit recording" }
            try {
                repository.add(Episode(id, session, anchorMs() + startSample * 1000 / Yamnet.RATE, samples, output.length(), currentLogicalEpisode))
            } catch (t: Throwable) {
                // A file without committed metadata must not remain invisible until next launch.
                output.delete()
                throw t
            }
            temporary = null
            onCommitted()
        } catch (t: Throwable) { abort(); throw t }
    }
    private fun release() {
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { muxer?.release() }; muxer = null
    }
    fun abort() { release(); temporary?.delete(); temporary = null }
}
