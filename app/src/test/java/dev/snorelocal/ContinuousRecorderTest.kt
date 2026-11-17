package dev.snorelocal

import org.junit.Assert.*
import org.junit.Test

class ContinuousRecorderTest {
    @Test fun recordsQuietAudioFromFirstSampleAndSplitsWithoutLoss() {
        val sink = CoreChecks.MemorySink()
        val recorder = ContinuousRecorder(10, sink)
        recorder.accept(CoreChecks.sequence(0, 7), 7)
        recorder.accept(CoreChecks.sequence(7, 18), 18)
        recorder.finish("stop")
        assertEquals(25L, recorder.position())
        assertEquals(3, sink.clips.size)
        var expected = 0
        for (clip in sink.clips) {
            assertEquals(expected.toLong(), clip.start)
            assertTrue(clip.count <= 10)
            for (sample in clip.audio) assertEquals(expected++, sample.toInt())
        }
        assertEquals(25, expected)
        recorder.finish("stop")
        assertEquals(3, sink.clips.size)
    }

    @Test fun emptyCaptureAndExactBoundaryStopDoNotCreateEmptyClips() {
        val sink = CoreChecks.MemorySink()
        val recorder = ContinuousRecorder(10, sink)
        recorder.accept(shortArrayOf(), 0)
        recorder.finish("stop")
        assertTrue(sink.clips.isEmpty())
        recorder.accept(ShortArray(10), 10)
        recorder.finish("stop")
        assertEquals(1, sink.clips.size)
        assertEquals(10, sink.clips.first().count)
    }
}
