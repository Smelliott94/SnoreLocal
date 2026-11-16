package dev.snorelocal;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ContinuousRecorderTest {
    @Test public void recordsQuietAudioFromFirstSampleAndSplitsWithoutLoss() {
        CoreChecks.MemorySink sink = new CoreChecks.MemorySink();
        ContinuousRecorder recorder = new ContinuousRecorder(10, sink);
        recorder.accept(CoreChecks.sequence(0, 7), 7);
        recorder.accept(CoreChecks.sequence(7, 18), 18);
        recorder.finish("stop");
        assertEquals(25, recorder.position());
        assertEquals(3, sink.clips.size());
        int expected = 0;
        for (CoreChecks.Clip clip : sink.clips) {
            assertEquals(expected, clip.start);
            assertTrue(clip.count <= 10);
            for (short sample : clip.audio) assertEquals(expected++, sample);
        }
        assertEquals(25, expected);
        recorder.finish("stop");
        assertEquals(3, sink.clips.size());
    }
    @Test public void emptyCaptureAndExactBoundaryStopDoNotCreateEmptyClips() {
        CoreChecks.MemorySink sink = new CoreChecks.MemorySink();
        ContinuousRecorder recorder = new ContinuousRecorder(10, sink);
        recorder.accept(new short[0], 0);
        recorder.finish("stop");
        assertTrue(sink.clips.isEmpty());
        recorder.accept(new short[10], 10);
        recorder.finish("stop");
        assertEquals(1, sink.clips.size());
        assertEquals(10, sink.clips.get(0).count);
    }
}
