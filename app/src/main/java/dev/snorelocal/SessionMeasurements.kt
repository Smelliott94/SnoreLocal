package dev.snorelocal

import java.util.UUID
import kotlin.math.sqrt

/** Small immutable checkpoint passed to SQLite on the existing processing worker. */
data class MeasurementCheckpoint(
    val events: List<DetectedEpisode>,
    val minutes: List<MinuteStats>,
    val lastCapturedAt: Long,
)

/** Streaming measurements: a few mutable minute buckets, never an overnight PCM accumulation. */
class SessionMeasurements(
    private val session: String,
    private val zoneId: String,
    private val anchorMs: () -> Long,
) : EpisodeEngine.Observer {
    private class Minute(val start: Long) {
        var monitored = 0L
        var snoring = 0L
        var energy = 0.0
        var peak = 0.0
        val levels = ByteArray(60)
    }
    private val minutes = sortedMapOf<Long, Minute>()
    private val finished = mutableListOf<DetectedEpisode>()
    private var event: DetectedEpisode? = null
    private var unionEnd = 0L
    private var origin: Long? = null
    private var processed = 0L
    private var levelStart = 0L
    private var levelSamples = 0
    private var levelEnergy = 0.0
    val currentEpisodeId: String? get() = event?.id
    private fun origin(): Long = origin ?: anchorMs().also { origin = it }
    private fun time(sample: Long) = origin() + sample * 1000 / RATE
    private fun minuteAt(sample: Long): Minute {
        val start = Math.floorDiv(time(sample), 60_000) * 60_000
        return minutes.getOrPut(start) { Minute(start) }
    }
    private fun nextSecondSample(sample: Long): Long {
        val nextMs = (Math.floorDiv(time(sample), 1000) + 1) * 1000
        return ((nextMs - origin()) * RATE + 999) / 1000
    }

    fun accept(pcm: ShortArray, offset: Int, count: Int, startSample: Long) {
        require(offset >= 0 && count >= 0 && offset + count <= pcm.size)
        require(startSample == processed) { "Measurement sample clock is discontinuous" }
        if (count == 0) return
        var at = offset
        while (at < offset + count) {
            if (levelSamples == 0) levelStart = processed
            val n = minOf(
                offset + count - at,
                LEVEL_SAMPLES - levelSamples,
                (nextSecondSample(processed) - processed).toInt(),
            )
            check(n > 0)
            var energy = 0.0
            for (i in at until at + n) {
                val value = pcm[i] / 32768.0
                energy += value * value
            }
            minuteAt(processed).apply { monitored += n; this.energy += energy }
            levelSamples += n
            levelEnergy += energy
            processed += n
            at += n
            if (levelSamples == LEVEL_SAMPLES) flushLevel()
        }
    }

    private fun flushLevel() {
        if (levelSamples == 0) return
        val rms = sqrt(levelEnergy / levelSamples)
        // Zero is reserved for an unmeasured second; measured silence is encoded as one.
        val level = (Statistics.relativeLevel(rms) * 254).toInt().coerceIn(0, 254) + 1
        var sample = levelStart
        val end = levelStart + levelSamples
        while (sample < end) {
            val minute = minuteAt(sample)
            val second = ((time(sample) - minute.start) / 1000).toInt().coerceIn(0, 59)
            minute.peak = maxOf(minute.peak, rms)
            if ((minute.levels[second].toInt() and 255) < level) minute.levels[second] = level.toByte()
            sample = minOf(end, nextSecondSample(sample))
        }
        levelSamples = 0
        levelEnergy = 0.0
    }

    override fun onEpisodeStarted(startSample: Long) {
        check(event == null) { "Measurement episode already active" }
        unionEnd = startSample
        event = DetectedEpisode(UUID.randomUUID().toString(), session, time(startSample), null, 0, zoneId)
    }

    override fun onDetection(startSample: Long, endSample: Long) {
        val current = checkNotNull(event)
        require(startSample >= 0 && endSample >= startSample && endSample <= processed)
        var sample = maxOf(startSample, unionEnd)
        val newlyDetected = (endSample - sample).coerceAtLeast(0)
        while (sample < endSample) {
            val minute = minuteAt(sample)
            val nextMinute = ((minute.start + 60_000 - origin()) * RATE + 999) / 1000
            val end = minOf(endSample, nextMinute)
            minute.snoring += end - sample
            sample = end
        }
        unionEnd = maxOf(unionEnd, endSample)
        event = current.copy(snoreSamples = current.snoreSamples + newlyDetected)
    }

    override fun onEpisodeFinished(endSample: Long, reason: String) {
        val current = event ?: return
        finished += current.copy(end = time(endSample), partial = reason == "interrupted")
        event = null
    }

    fun checkpoint(final: Boolean = false): MeasurementCheckpoint {
        if (final) flushLevel()
        val events = finished.toList() + listOfNotNull(event)
        finished.clear()
        val buckets = minutes.values.map {
            MinuteStats(session, it.start, it.monitored, it.snoring, it.energy, it.peak, it.levels.copyOf())
        }
        val last = time(processed)
        // Retain the classifier's look-back and an unfinished 100 ms loudness bucket.
        minutes.entries.removeAll { it.key + 60_000 < last - 2000 }
        return MeasurementCheckpoint(events, buckets, last)
    }

    private companion object {
        const val RATE = 16_000L
        const val LEVEL_SAMPLES = 1600
    }
}
