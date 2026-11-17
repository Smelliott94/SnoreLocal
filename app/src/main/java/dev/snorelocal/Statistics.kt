package dev.snorelocal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.log10
import kotlin.math.sqrt

/** One classifier-defined instance, independent of its number of encoded audio files. */
data class DetectedEpisode(
    val id: String,
    val session: String,
    val start: Long,
    val end: Long?,
    val snoreSamples: Long,
    val zoneId: String,
    val partial: Boolean = false,
)

/** Compact minute totals; levels use 0 for missing seconds and 1..255 for measured fixed-scale levels. */
data class MinuteStats(
    val session: String,
    val start: Long,
    val monitoredSamples: Long,
    val snoreSamples: Long,
    val energy: Double,
    val peakRms: Double,
    val levels: ByteArray = ByteArray(60),
)

data class StatisticsSnapshot(
    val events: List<DetectedEpisode> = emptyList(),
    val minutes: List<MinuteStats> = emptyList(),
    val sessions: List<Night> = emptyList(),
    val clips: List<Episode> = emptyList(),
)

enum class StatsPeriod { NIGHT, WEEK, MONTH }

data class GraphPoint(
    val label: String,
    val level: Float,
    val snoreMs: Long,
    val monitoredMs: Long,
)

data class PeriodStatistics(
    val startDate: LocalDate,
    /** Inclusive date of the final night in this period. */
    val endDate: LocalDate,
    val episodeCount: Int,
    val snoreMs: Long,
    val monitoredMs: Long,
    val trackedNights: Int,
    val longestEpisodeMs: Long,
    val partial: Boolean,
    val legacy: Boolean,
    val points: List<GraphPoint>,
    val energy: Double = 0.0,
    val sampleCount: Long = 0L,
) {
    val snorePercent: Double? get() = if (monitoredMs > 0) 100.0 * snoreMs / monitoredMs else null
    val episodesPerHour: Double? get() = if (monitoredMs > 0) episodeCount * 3_600_000.0 / monitoredMs else null
    val averageEpisodes: Double? get() = if (monitoredMs > 0 && trackedNights > 0) episodeCount.toDouble() / trackedNights else null
    val averageSnoreMs: Long? get() = if (monitoredMs > 0 && trackedNights > 0) snoreMs / trackedNights else null
    /** Energy is aggregated in linear space; decibel or per-minute averages are never averaged. */
    val meanRms: Double? get() = if (sampleCount > 0) sqrt((energy / sampleCount).coerceAtLeast(0.0)) else null
}

/** Local, deterministic aggregation. No Android APIs, audio decoding or additional inference. */
object Statistics {
    private const val SAMPLE_RATE = 16_000L
    private const val MINUTE_MS = 60_000L
    private const val FIVE_MINUTES_MS = 5 * MINUTE_MS
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** Noon belongs to the new night; local calendar arithmetic also handles DST changes. */
    fun nightDate(epochMs: Long, zoneId: String): LocalDate {
        val local = Instant.ofEpochMilli(epochMs).atZone(zone(zoneId))
        return if (local.toLocalTime() < LocalTime.NOON) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    /** One fixed microphone-input scale for every graph. It is not calibrated acoustic loudness. */
    fun relativeLevel(rms: Double): Float {
        if (!rms.isFinite() || rms <= 0.0) return 0f
        return ((20.0 * log10(rms) + 80.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }

    fun summarize(snapshot: StatisticsSnapshot, period: StatsPeriod, date: LocalDate): PeriodStatistics {
        val first = when (period) {
            StatsPeriod.NIGHT -> date
            StatsPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsPeriod.MONTH -> date.withDayOfMonth(1)
        }
        val last = when (period) {
            StatsPeriod.NIGHT -> first
            StatsPeriod.WEEK -> first.plusDays(6)
            StatsPeriod.MONTH -> first.withDayOfMonth(first.lengthOfMonth())
        }
        fun included(value: LocalDate) = !value.isBefore(first) && !value.isAfter(last)
        val sessions = snapshot.sessions.associateBy { it.id }
        val eligible = sessions.values.filter { it.mode == "snoring" && it.measured && !it.statsCleared }.associateBy { it.id }
        val minutes = snapshot.minutes.distinctBy { it.session to it.start }.filter {
            val session = eligible[it.session]
            session != null && included(nightDate(it.start, session.zoneId))
        }
        val events = snapshot.events.distinctBy { it.id }.filter {
            eligible.containsKey(it.session) && included(nightDate(it.start, it.zoneId))
        }
        val coverage = minutes.filter { it.monitoredSamples > 0 }
        val tracked = coverage.map { nightDate(it.start, eligible.getValue(it.session).zoneId) }.toSet()
        val totals = Totals().apply { minutes.forEach(::add) }
        val relevantIds = (coverage.map { it.session } + events.map { it.session }).toSet()
        val partial = events.any { it.partial || it.end == null } || eligible.values.any {
            (it.id in relevantIds || sessionIntersects(it, first, last)) && (it.status != "completed" || it.end == null)
        }
        val legacy = sessions.values.any {
            it.mode == "snoring" && !it.measured && !it.statsCleared && sessionIntersects(it, first, last)
        }
        val points = if (period == StatsPeriod.NIGHT) {
            nightPoints(first, minutes, eligible, sessions.values.filter { it.mode == "snoring" && !it.statsCleared })
        } else {
            val grouped = minutes.groupBy { nightDate(it.start, eligible.getValue(it.session).zoneId) }
            generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
                val dayTotals = Totals().apply { grouped[day].orEmpty().forEach(::add) }
                dayTotals.point(day.toString())
            }.toList()
        }
        return PeriodStatistics(
            startDate = first,
            endDate = last,
            episodeCount = events.size,
            snoreMs = samplesToMs(totals.snore),
            monitoredMs = samplesToMs(totals.monitored),
            trackedNights = tracked.size,
            longestEpisodeMs = events.maxOfOrNull { samplesToMs(it.snoreSamples.coerceAtLeast(0)) } ?: 0,
            partial = partial,
            legacy = legacy,
            points = points,
            energy = totals.energy,
            sampleCount = totals.monitored,
        )
    }

    /** One level per second, including recording context when its bounds are supplied. */
    fun episodeLevels(
        event: DetectedEpisode,
        minutes: List<MinuteStats>,
        recordingStart: Long? = null,
        recordingEnd: Long? = null,
    ): List<Float> {
        val selected = minutes.filter { it.session == event.session }.associateBy { Math.floorDiv(it.start, MINUTE_MS) }
        val start = recordingStart ?: event.start
        val end = recordingEnd ?: event.end ?: selected.values.maxOfOrNull { it.start + MINUTE_MS } ?: start
        if (end <= start) return emptyList()
        val firstSecond = Math.floorDiv(start, 1000L)
        val lastSecond = Math.floorDiv(end - 1, 1000L)
        // Services stop after twelve hours. Bound corrupt metadata without allocating unbounded history.
        val count = (lastSecond - firstSecond + 1).coerceIn(0L, 12L * 60 * 60 + 120).toInt()
        return List(count) { offset ->
            val second = firstSecond + offset
            val bucket = selected[Math.floorDiv(second, 60L)]
            val index = Math.floorMod(second, 60L).toInt()
            val level = bucket?.levels?.getOrNull(index)?.toInt()?.and(255) ?: 0
            if (level == 0) Float.NaN else (level - 1) / 254f
        }
    }

    private fun nightPoints(date: LocalDate, minutes: List<MinuteStats>, eligible: Map<String, Night>, sessions: List<Night>): List<GraphPoint> {
        val relevant = minutes.firstOrNull()?.session?.let(eligible::get)
            ?: sessions.firstOrNull { sessionIntersects(it, date, date) }
        val displayZone = zone(relevant?.zoneId ?: ZoneId.systemDefault().id)
        val noon = date.atTime(LocalTime.NOON).atZone(displayZone).toInstant().toEpochMilli()
        val nextNoon = date.plusDays(1).atTime(LocalTime.NOON).atZone(displayZone).toInstant().toEpochMilli()
        var pointCount = ((nextNoon - noon + FIVE_MINUTES_MS - 1) / FIVE_MINUTES_MS).toInt()
        val indexed = minutes.map { minute ->
            val ownZone = zone(eligible.getValue(minute.session).zoneId)
            val ownNoon = date.atTime(LocalTime.NOON).atZone(ownZone).toInstant().toEpochMilli()
            val ownNextNoon = date.plusDays(1).atTime(LocalTime.NOON).atZone(ownZone).toInstant().toEpochMilli()
            pointCount = maxOf(pointCount, ((ownNextNoon - ownNoon + FIVE_MINUTES_MS - 1) / FIVE_MINUTES_MS).toInt())
            Math.floorDiv(minute.start - ownNoon, FIVE_MINUTES_MS).toInt() to minute
        }
        val totals = List(pointCount) { Totals() }
        indexed.forEach { (index, minute) -> if (index in totals.indices) totals[index].add(minute) }
        return totals.mapIndexed { index, value ->
            value.point(Instant.ofEpochMilli(noon + index * FIVE_MINUTES_MS).atZone(displayZone).format(timeFormat))
        }
    }

    private fun sessionIntersects(session: Night, first: LocalDate, last: LocalDate): Boolean {
        val begin = nightDate(session.start, session.zoneId)
        val end = nightDate(maxOf(session.start, (session.end ?: session.start) - 1), session.zoneId)
        return !begin.isAfter(last) && !end.isBefore(first)
    }

    private class Totals {
        var monitored = 0L
        var snore = 0L
        var energy = 0.0
        private var peak = 0.0
        fun add(minute: MinuteStats) {
            val count = minute.monitoredSamples.coerceAtLeast(0)
            monitored += count
            snore += minute.snoreSamples.coerceIn(0L, count)
            energy += minute.energy.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            peak = maxOf(peak, minute.peakRms.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
        }
        fun point(label: String) = GraphPoint(label, relativeLevel(peak), samplesToMs(snore), samplesToMs(monitored))
    }

    private fun samplesToMs(samples: Long): Long = samples / SAMPLE_RATE * 1000L + samples % SAMPLE_RATE * 1000L / SAMPLE_RATE
    private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())
}
