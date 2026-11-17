package dev.snorelocal

/** Sample-clock segmentation. No Android dependencies; all memory is bounded. */
class EpisodeEngine(
    preRollSamples: Int,
    private val quietSamples: Long,
    private val maxSamples: Long,
    private val sensitivity: Float,
    private val sink: Sink,
    private val observer: Observer? = null,
    private val detectionWindowSamples: Int = 15_600,
) {
    interface Sink {
        fun open(startSample: Long)
        fun write(pcm: ShortArray, offset: Int, count: Int)
        fun close(reason: String)
    }

    interface Observer {
        fun onEpisodeStarted(startSample: Long)
        fun onDetection(startSample: Long, endSample: Long)
        fun onEpisodeFinished(endSample: Long, reason: String)
    }

    private val history: PcmRing
    private var position = 0L
    private var lastDetection = 0L
    private var clipSize = 0L
    private var active = false
    private var opened = false
    private var smooth = 0.0

    init {
        require(detectionWindowSamples > 0) { "detectionWindowSamples" }
        require(quietSamples > 0 && maxSamples >= preRollSamples && preRollSamples > 0) { "limits" }
        require(sensitivity.isFinite() && sensitivity in 0f..1f) { "sensitivity" }
        history = PcmRing(preRollSamples)
    }

    val isActive: Boolean get() = active
    fun position(): Long = position

    /** score is null between model windows; zero means a gate-skipped window. */
    fun accept(pcm: ShortArray, count: Int, score: Float?) {
        require(count in 0..pcm.size) { "sample count" }
        require(score == null || (score.isFinite() && score in 0f..1f)) { "Invalid classifier score" }
        if (count == 0) return
        val begin = position
        position += count
        history.add(pcm, 0, count)
        // A score describes the window ending at position, never an earlier expired gap.
        if (active && position > lastDetection + quietSamples) {
            val keep = minOf(count.toLong(), lastDetection + quietSamples - begin).coerceAtLeast(0).toInt()
            append(pcm, 0, keep, begin)
            finish("quiet")
        }
        if (score != null) {
            smooth = .65 * score + .35 * smooth
            val start = .50 - .30 * sensitivity
            val stop = start * .55
            if (smooth >= if (active) stop else start) {
                lastDetection = position
                if (!active) {
                    active = true
                    observer?.onEpisodeStarted((position - detectionWindowSamples).coerceAtLeast(0))
                    observer?.onDetection((position - detectionWindowSamples).coerceAtLeast(0), position)
                    val pre = history.snapshot()
                    append(pre, 0, pre.size, position - pre.size)
                    return
                }
                observer?.onDetection((position - detectionWindowSamples).coerceAtLeast(0), position)
            }
        }
        if (active) {
            val keep = minOf(count.toLong(), lastDetection + quietSamples - begin).coerceAtLeast(0).toInt()
            append(pcm, 0, keep, begin)
            if (position >= lastDetection + quietSamples) finish("quiet")
        }
    }

    private fun append(pcm: ShortArray, offset: Int, count: Int, start: Long) {
        var at = offset
        var remaining = count
        var sample = start
        while (remaining > 0) {
            if (!opened) {
                sink.open(sample)
                opened = true
                clipSize = 0
            }
            val n = minOf(remaining.toLong(), maxSamples - clipSize).toInt()
            sink.write(pcm, at, n)
            clipSize += n
            at += n
            remaining -= n
            sample += n
            if (clipSize == maxSamples) {
                sink.close("split")
                opened = false
            }
        }
    }

    fun finish(reason: String) {
        if (opened) sink.close(reason)
        if (active) observer?.onEpisodeFinished(lastDetection, reason)
        opened = false
        active = false
        smooth = 0.0
    }
}
