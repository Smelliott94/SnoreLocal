package dev.snorelocal;

/** Explicit microphone-test mode: every captured sample is written, without classifier scores. */
public final class ContinuousRecorder {
    private final EpisodeEngine.Sink sink;
    private final long maxSamples;
    private long position, clipSamples;
    private boolean opened;
    public ContinuousRecorder(long maxSamples, EpisodeEngine.Sink sink) {
        if (maxSamples <= 0) throw new IllegalArgumentException("maxSamples");
        this.maxSamples = maxSamples;
        this.sink = sink;
    }
    public long position() { return position; }
    public void accept(short[] pcm, int count) {
        if (count < 0 || count > pcm.length) throw new IllegalArgumentException("sample count");
        int offset = 0;
        while (offset < count) {
            if (!opened) { sink.open(position); opened = true; clipSamples = 0; }
            int n = (int)Math.min(count - offset, maxSamples - clipSamples);
            sink.write(pcm, offset, n);
            position += n; clipSamples += n; offset += n;
            if (clipSamples == maxSamples) { sink.close("split"); opened = false; }
        }
    }
    public void finish(String reason) {
        if (opened) sink.close(reason);
        opened = false;
    }
}
