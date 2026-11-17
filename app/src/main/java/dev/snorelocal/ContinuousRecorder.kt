package dev.snorelocal

/** Explicit microphone-test mode: every captured sample is written, without classifier scores. */
class ContinuousRecorder(private val maxSamples: Long, private val sink: EpisodeEngine.Sink) {
    private var position = 0L
    private var clipSamples = 0L
    private var opened = false

    init {
        require(maxSamples > 0) { "maxSamples" }
    }

    fun position(): Long = position

    fun accept(pcm: ShortArray, count: Int) {
        require(count in 0..pcm.size) { "sample count" }
        var offset = 0
        while (offset < count) {
            if (!opened) {
                sink.open(position)
                opened = true
                clipSamples = 0
            }
            val n = minOf((count - offset).toLong(), maxSamples - clipSamples).toInt()
            sink.write(pcm, offset, n)
            position += n
            clipSamples += n
            offset += n
            if (clipSamples == maxSamples) {
                sink.close("split")
                opened = false
            }
        }
    }

    fun finish(reason: String) {
        if (opened) sink.close(reason)
        opened = false
    }
}
