package dev.snorelocal

import org.junit.Assert.*
import org.junit.Test

class SessionMeasurementsTest {
    private val rate = 16_000
    private val origin = 1_800_000_000_000L // Exact epoch-minute boundary.

    @Test fun recordsExactCoverageAndEnergyWithFixedSoundLevels() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        val pcm = ShortArray(rate) { 16_384 }
        measurements.accept(pcm, 0, pcm.size, 0)
        val checkpoint = measurements.checkpoint(final = true)
        assertEquals(origin + 1000, checkpoint.lastCapturedAt)
        assertEquals(1, checkpoint.minutes.size)
        val minute = checkpoint.minutes.single()
        assertEquals(rate.toLong(), minute.monitoredSamples)
        assertEquals(0L, minute.snoreSamples)
        assertEquals(rate * .25, minute.energy, .000001)
        assertEquals(.5, minute.peakRms, .000001)
        assertEquals(255, minute.levels[0].toInt() and 255)
        assertTrue(minute.levels.drop(1).all { it == 0.toByte() })
        assertTrue(checkpoint.events.isEmpty())
    }

    @Test fun overlappingDetectionsCountOnceAndExcludePreRollAndQuietTail() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        val pcm = ShortArray(rate * 10)
        measurements.accept(pcm, 0, pcm.size, 0)
        measurements.onEpisodeStarted(4L * rate)
        val episodeId = measurements.currentEpisodeId
        assertNotNull(episodeId)
        measurements.onDetection(4L * rate, 6L * rate)
        measurements.onDetection(5L * rate, 7L * rate)
        measurements.onEpisodeFinished(10L * rate, "quiet")
        assertNull(measurements.currentEpisodeId)
        val checkpoint = measurements.checkpoint(final = true)
        val event = checkpoint.events.single()
        assertEquals(episodeId, event.id)
        assertEquals(origin + 4000, event.start)
        assertEquals(3L * rate, event.snoreSamples)
        assertEquals(10L * rate, checkpoint.minutes.sumOf { it.monitoredSamples })
        assertEquals(3L * rate, checkpoint.minutes.sumOf { it.snoreSamples })
        assertFalse(event.partial)
    }

    @Test fun minuteBoundarySplitsSamplesWithoutDuplicatingDuration() {
        val measurements = SessionMeasurements("session", "UTC") { origin + 59_500 }
        measurements.accept(ShortArray(rate * 2) { 1000 }, 0, rate * 2, 0)
        measurements.onEpisodeStarted(0)
        measurements.onDetection(0, 2L * rate)
        measurements.onEpisodeFinished(2L * rate, "stop")
        val minutes = measurements.checkpoint(final = true).minutes.sortedBy { it.start }
        assertEquals(2, minutes.size)
        assertEquals(8000L, minutes[0].monitoredSamples)
        assertEquals(24000L, minutes[1].monitoredSamples)
        assertEquals(8000L, minutes[0].snoreSamples)
        assertEquals(24000L, minutes[1].snoreSamples)
    }

    @Test fun interruptedEpisodeIsExplicitlyPartial() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        measurements.accept(ShortArray(rate), 0, rate, 0)
        measurements.onEpisodeStarted(0)
        measurements.onDetection(0, rate.toLong())
        measurements.onEpisodeFinished(rate.toLong(), "interrupted")
        assertTrue(measurements.checkpoint(final = true).events.single().partial)
    }

    @Test fun partialReadPartitionsProduceTheSameCoverageEnergyAndGraph() {
        val pcm = ShortArray(rate * 3) { i -> if (i / 800 % 2 == 0) 1000 else 16_384 }
        fun measure(chunkSize: Int): MinuteStats {
            val measurements = SessionMeasurements("session", "UTC") { origin }
            var offset = 0
            while (offset < pcm.size) {
                val n = minOf(chunkSize, pcm.size - offset)
                measurements.accept(pcm, offset, n, offset.toLong())
                offset += n
            }
            return measurements.checkpoint(final = true).minutes.single()
        }
        val whole = measure(pcm.size)
        for (chunk in listOf(1, 17, 799, 1600, 7000)) {
            val partial = measure(chunk)
            assertEquals(whole.monitoredSamples, partial.monitoredSamples)
            assertEquals(whole.energy, partial.energy, .000001)
            assertEquals(whole.peakRms, partial.peakRms, .000001)
            assertArrayEquals(whole.levels, partial.levels)
        }
    }

    @Test fun splitAudioFilesKeepOneMeasuredInstanceAndOneLogicalId() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        val fileEpisodeIds = mutableListOf<String?>()
        var writes = 0L
        val engine = EpisodeEngine(rate * 5, rate * 2L, rate * 5L, .5f,
            object : EpisodeEngine.Sink {
                override fun open(startSample: Long) { fileEpisodeIds += measurements.currentEpisodeId }
                override fun write(pcm: ShortArray, offset: Int, count: Int) { writes += count }
                override fun close(reason: String) = Unit
            }, measurements)
        val chunk = ShortArray(rate / 2)
        repeat(40) {
            measurements.accept(chunk, 0, chunk.size, engine.position())
            engine.accept(chunk, chunk.size, 1f)
        }
        engine.finish("stop")
        val checkpoint = measurements.checkpoint(final = true)
        assertEquals(4, fileEpisodeIds.size)
        assertEquals(1, fileEpisodeIds.distinct().size)
        assertNotNull(fileEpisodeIds.first())
        assertEquals(1, checkpoint.events.size)
        assertEquals(fileEpisodeIds.first(), checkpoint.events.single().id)
        assertEquals(20L * rate, writes)
        assertEquals(20L * rate, checkpoint.events.single().snoreSamples)
    }

    @Test fun checkpointsRemainBoundedAndUpsertIntoExactLongSessionCoverage() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        val persisted = mutableMapOf<Long, MinuteStats>()
        val block = ShortArray(1600)
        var samples = 0L
        repeat(6000) { index ->
            measurements.accept(block, 0, block.size, samples)
            samples += block.size
            if ((index + 1) % 300 == 0) {
                val checkpoint = measurements.checkpoint()
                assertTrue("Only recent mutable minute buckets should remain in memory", checkpoint.minutes.size <= 2)
                checkpoint.minutes.forEach { persisted[it.start] = it }
            }
        }
        measurements.checkpoint(final = true).minutes.forEach { persisted[it.start] = it }
        assertEquals(10, persisted.size)
        assertEquals(600L * rate, persisted.values.sumOf { it.monitoredSamples })
        assertEquals(0L, persisted.values.sumOf { it.snoreSamples })
    }

    @Test fun repeatedCheckpointsDoNotCreateAdditionalEpisodeIdsOrSamples() {
        val measurements = SessionMeasurements("session", "UTC") { origin }
        measurements.accept(ShortArray(rate), 0, rate, 0)
        measurements.onEpisodeStarted(0)
        measurements.onDetection(0, rate.toLong())
        val first = measurements.checkpoint()
        val second = measurements.checkpoint()
        assertEquals(first.events.single().id, second.events.single().id)
        assertEquals(first.events.single().snoreSamples, second.events.single().snoreSamples)
        assertEquals(first.minutes.single().monitoredSamples, second.minutes.single().monitoredSamples)
    }
}
