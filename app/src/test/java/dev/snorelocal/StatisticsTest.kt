package dev.snorelocal

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatisticsTest {
    private val date = LocalDate.of(2026, 11, 10)
    private val utc = "UTC"
    private val rate = 16_000L

    private fun time(day: LocalDate = date, hour: Int = 22, minute: Int = 0, zone: String = utc): Long =
        day.atTime(hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    private fun session(
        id: String = "session", day: LocalDate = date, mode: String = "snoring",
        status: String = "completed", measured: Boolean = true, cleared: Boolean = false,
    ) = Night(id, time(day), time(day, 23), status, null, mode, utc, measured, cleared)

    private fun minute(
        id: String = "session", start: Long = time(), monitoredSeconds: Long = 60,
        snoreSeconds: Long = 10, rms: Double = .01,
    ) = MinuteStats(id, start, monitoredSeconds * rate, snoreSeconds * rate,
        rms * rms * monitoredSeconds * rate, rms, ByteArray(60) { (1 + (Statistics.relativeLevel(rms) * 254).toInt()).toByte() })

    private fun event(id: String = "event", session: String = "session", start: Long = time(), seconds: Long = 10, partial: Boolean = false) =
        DetectedEpisode(id, session, start, start + 60_000, seconds * rate, utc, partial)

    @Test fun splitFilesAndAudioDeletionDoNotChangeLogicalEpisodeCount() {
        val episode = event(seconds = 20)
        val clips = listOf(
            Episode("part1", "session", time(), 300 * rate, 100, episode.id),
            Episode("part2", "session", time() + 300_000, 20 * rate, 100, episode.id),
        )
        val snapshot = StatisticsSnapshot(listOf(episode), listOf(minute(snoreSeconds = 20)), listOf(session()), clips)
        val before = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date)
        val after = Statistics.summarize(snapshot.copy(clips = emptyList()), StatsPeriod.NIGHT, date)
        assertEquals(1, before.episodeCount)
        assertEquals(20_000L, before.snoreMs)
        assertEquals(20_000L, before.longestEpisodeMs)
        assertEquals(before.episodeCount, after.episodeCount)
        assertEquals(before.snoreMs, after.snoreMs)
        assertEquals(before.monitoredMs, after.monitoredMs)
    }

    @Test fun microphoneTestsAndClearedHistoryNeverContributeSnoringStatistics() {
        val snapshot = StatisticsSnapshot(
            events = listOf(event(), event("mic-event", "mic"), event("cleared-event", "cleared")),
            minutes = listOf(minute(), minute("mic", snoreSeconds = 60), minute("cleared", snoreSeconds = 60)),
            sessions = listOf(session(), session("mic", mode = "all_audio"), session("cleared", cleared = true)),
        )
        val result = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date)
        assertEquals(1, result.episodeCount)
        assertEquals(60_000L, result.monitoredMs)
        assertEquals(10_000L, result.snoreMs)
    }

    @Test fun monitoredSilenceIsZeroButUnmonitoredDataHasNoRatesOrAverages() {
        val empty = Statistics.summarize(StatisticsSnapshot(), StatsPeriod.NIGHT, date)
        assertEquals(0, empty.trackedNights)
        assertNull(empty.snorePercent)
        assertNull(empty.episodesPerHour)
        assertNull(empty.averageEpisodes)
        assertNull(empty.averageSnoreMs)
        assertTrue(empty.points.all { it.monitoredMs == 0L })

        val silent = Statistics.summarize(StatisticsSnapshot(
            minutes = listOf(minute(snoreSeconds = 0, rms = 0.0)), sessions = listOf(session()),
        ), StatsPeriod.NIGHT, date)
        assertEquals(1, silent.trackedNights)
        assertEquals(0, silent.episodeCount)
        assertEquals(0L, silent.snoreMs)
        assertEquals(0.0, silent.snorePercent!!, 0.0)
        assertEquals(0.0, silent.episodesPerHour!!, 0.0)
        assertEquals(0L, silent.averageSnoreMs)
        assertTrue(silent.points.any { it.monitoredMs > 0 })
    }

    @Test fun totalsAreWeightedByMeasuredSamplesAndAveragesUseTrackedNights() {
        val next = date.plusDays(1)
        val snapshot = StatisticsSnapshot(
            events = listOf(event(), event("second", "second", time(next), 30)),
            minutes = listOf(
                minute(monitoredSeconds = 60, snoreSeconds = 15, rms = .01),
                minute("second", time(next), monitoredSeconds = 60, snoreSeconds = 15, rms = .1),
                minute("second", time(next) + 60_000, monitoredSeconds = 60, snoreSeconds = 15, rms = .1),
            ),
            sessions = listOf(session(), session("second", next)),
        )
        val result = Statistics.summarize(snapshot, StatsPeriod.WEEK, date)
        assertEquals(180_000L, result.monitoredMs)
        assertEquals(45_000L, result.snoreMs)
        assertEquals(25.0, result.snorePercent!!, .000001)
        assertEquals(40.0, result.episodesPerHour!!, .000001)
        assertEquals(2, result.trackedNights)
        assertEquals(1.0, result.averageEpisodes!!, .000001)
        assertEquals(22_500L, result.averageSnoreMs)
        val expectedRms = Math.sqrt((.01 * .01 * 60 + .1 * .1 * 120) / 180)
        assertEquals(expectedRms, result.meanRms!!, .0000001)
    }

    @Test fun partialAndLegacyCoverageAreVisibleWithoutFabricatingMeasurements() {
        val snapshot = StatisticsSnapshot(
            events = listOf(event(partial = true)),
            minutes = listOf(minute()),
            sessions = listOf(session(status = "interrupted"), session("old", measured = false)),
            clips = listOf(Episode("old-clip", "old", time(), 999 * rate, 100)),
        )
        val result = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date)
        assertTrue(result.partial)
        assertTrue(result.legacy)
        assertEquals(1, result.episodeCount)
        assertEquals(10_000L, result.snoreMs)
        assertEquals(60_000L, result.monitoredMs)
    }

    @Test fun noonNightBoundaryAndCalendarPeriodsAreDeterministic() {
        val zone = "Europe/Berlin"
        assertEquals(date.minusDays(1), Statistics.nightDate(time(date, 11, 59, zone), zone))
        assertEquals(date, Statistics.nightDate(time(date, 12, 0, zone), zone))
        assertEquals(date, Statistics.nightDate(time(date.plusDays(1), 0, 0, zone), zone))
        val week = Statistics.summarize(StatisticsSnapshot(), StatsPeriod.WEEK, date)
        assertEquals(LocalDate.of(2026, 11, 9), week.startDate)
        assertEquals(LocalDate.of(2026, 11, 15), week.endDate)
        assertEquals(7, week.points.size)
        val month = Statistics.summarize(StatisticsSnapshot(), StatsPeriod.MONTH, LocalDate.of(2028, 2, 29))
        assertEquals(LocalDate.of(2028, 2, 1), month.startDate)
        assertEquals(LocalDate.of(2028, 2, 29), month.endDate)
        assertEquals(29, month.points.size)
    }

    @Test fun episodeAcrossNoonCountsAtOnsetWhileItsDurationSplitsAcrossNights() {
        val before = time(date, 11, 59)
        val after = time(date, 12, 0)
        val night = Night("crossing", before, after + 60_000, "completed", null, "snoring", utc, true, false)
        val crossing = DetectedEpisode("crossing-event", night.id, before + 50_000, after + 10_000, 20 * rate, utc)
        val snapshot = StatisticsSnapshot(
            events = listOf(crossing),
            minutes = listOf(minute(night.id, before), minute(night.id, after)),
            sessions = listOf(night),
        )
        val first = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date.minusDays(1))
        val second = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date)
        assertEquals(1, first.episodeCount)
        assertEquals(0, second.episodeCount)
        assertEquals(10_000L, first.snoreMs)
        assertEquals(10_000L, second.snoreMs)
        assertEquals(60_000L, first.monitoredMs)
        assertEquals(60_000L, second.monitoredMs)
    }

    @Test fun daylightSavingNightsHaveTheActualNumberOfFiveMinuteBuckets() {
        val zone = "Europe/Berlin"
        for ((night, expectedPoints) in listOf(LocalDate.of(2026, 3, 28) to 276, LocalDate.of(2026, 10, 24) to 300)) {
            val start = time(night, zone = zone)
            val session = Night("dst", start, start + 60_000, "completed", null, "snoring", zone, true, false)
            val measured = minute("dst", start)
            val result = Statistics.summarize(StatisticsSnapshot(minutes = listOf(measured), sessions = listOf(session)), StatsPeriod.NIGHT, night)
            assertEquals(expectedPoints, result.points.size)
            assertEquals(60_000L, result.monitoredMs)
            assertEquals(night, Statistics.nightDate(time(night.plusDays(1), 11, 59, zone), zone))
        }
    }

    @Test fun fixedSoundScaleHandlesSilenceAndDoesNotRescaleDifferentPeriods() {
        assertEquals(0f, Statistics.relativeLevel(0.0), 0f)
        assertEquals(0f, Statistics.relativeLevel(.0001), .00001f)
        assertEquals(.5f, Statistics.relativeLevel(Math.pow(10.0, -50.0 / 20)), .00001f)
        assertEquals(1f, Statistics.relativeLevel(.1), .00001f)
        assertEquals(1f, Statistics.relativeLevel(1.0), 0f)
        val snapshot = StatisticsSnapshot(minutes = listOf(minute(rms = .01)), sessions = listOf(session()))
        val night = Statistics.summarize(snapshot, StatsPeriod.NIGHT, date)
        val week = Statistics.summarize(snapshot, StatsPeriod.WEEK, date)
        assertEquals(night.points.maxOf { it.level }, week.points.maxOf { it.level }, .01f)
    }

    @Test fun episodeGraphDistinguishesMeasuredSilenceFromMissingSeconds() {
        val profile = ByteArray(60).apply {
            this[0] = 1
            this[1] = 128.toByte()
            this[2] = 255.toByte()
        }
        val bucket = minute().copy(levels = profile)
        val levels = Statistics.episodeLevels(event(), listOf(bucket), time(), time() + 4000)
        assertEquals(4, levels.size)
        assertEquals(0f, levels[0], 0f)
        assertEquals(.5f, levels[1], .00001f)
        assertEquals(1f, levels[2], 0f)
        assertTrue("Unmeasured seconds must not look like recorded silence", levels[3].isNaN())
    }

    @Test fun samplesAreSummedBeforeConvertingToMilliseconds() {
        val tiny = MinuteStats("session", time(), 8, 8, 0.0, 0.0)
        val result = Statistics.summarize(StatisticsSnapshot(
            minutes = listOf(tiny, tiny.copy(start = time() + 60_000)), sessions = listOf(session()),
        ), StatsPeriod.NIGHT, date)
        assertEquals(1L, result.monitoredMs)
        assertEquals(1L, result.snoreMs)
    }
}
