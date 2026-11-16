package dev.snorelocal;

/** Sample-clock segmentation. No Android dependencies; all memory is bounded. */
public final class EpisodeEngine {
    public interface Sink {
        void open(long startSample);
        void write(short[] pcm, int offset, int count);
        void close(String reason);
    }
    private final PcmRing history;
    private final Sink sink;
    private final long maxSamples, quietSamples;
    private long position, lastDetection, clipSize;
    private boolean active, opened;
    private double smooth;
    private float sensitivity;
    public EpisodeEngine(int preRollSamples, long quietSamples, long maxSamples, float sensitivity, Sink sink) {
        if (quietSamples <= 0 || maxSamples < preRollSamples || preRollSamples <= 0) throw new IllegalArgumentException("limits");
        if (!Float.isFinite(sensitivity) || sensitivity < 0 || sensitivity > 1) throw new IllegalArgumentException("sensitivity");
        history = new PcmRing(preRollSamples); this.quietSamples = quietSamples;
        this.maxSamples = maxSamples; this.sink = sink; this.sensitivity = sensitivity;
    }
    public boolean isActive() { return active; }
    public long position() { return position; }
    /** score is null between model windows; zero means a gate-skipped window. */
    public void accept(short[] pcm, int count, Float score) {
        if (count < 0 || count > pcm.length) throw new IllegalArgumentException("sample count");
        if (score != null && (!Float.isFinite(score) || score < 0 || score > 1)) throw new IllegalArgumentException("Invalid classifier score");
        if (count == 0) return;
        long begin = position; position += count;
        history.add(pcm, 0, count);
        // A score describes the window ending at position, never an earlier expired gap.
        if (active && position > lastDetection + quietSamples) {
            int keep = (int)Math.max(0, Math.min(count, lastDetection + quietSamples - begin));
            append(pcm, 0, keep, begin);
            finish("quiet");
        }
        if (score != null) {
            smooth = .65 * score + .35 * smooth;
            double start = .50 - .30 * sensitivity;
            double stop = start * .55;
            if (smooth >= (active ? stop : start)) {
                lastDetection = position;
                if (!active) {
                    active = true;
                    short[] pre = history.snapshot();
                    append(pre, 0, pre.length, position - pre.length);
                    return;
                }
            }
        }
        if (active) {
            int keep = (int)Math.max(0, Math.min(count, lastDetection + quietSamples - begin));
            append(pcm, 0, keep, begin);
            if (position >= lastDetection + quietSamples) finish("quiet");
        }
    }
    private void append(short[] pcm, int offset, int count, long start) {
        while (count > 0) {
            if (!opened) { sink.open(start); opened = true; clipSize = 0; }
            int n = (int)Math.min(count, maxSamples - clipSize);
            sink.write(pcm, offset, n);
            clipSize += n; offset += n; count -= n; start += n;
            if (clipSize == maxSamples) { sink.close("split"); opened = false; }
        }
    }
    public void finish(String reason) {
        if (opened) sink.close(reason);
        opened = false; active = false; smooth = 0;
    }
}
