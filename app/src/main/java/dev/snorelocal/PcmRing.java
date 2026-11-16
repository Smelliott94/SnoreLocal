package dev.snorelocal;

/** Fixed allocation, chronological PCM history. Owned by the processing worker. */
public final class PcmRing {
    private final short[] data;
    private int next, size;
    public PcmRing(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        data = new short[capacity];
    }
    public void add(short[] source, int offset, int count) {
        for (int i = offset; i < offset + count; i++) {
            data[next] = source[i]; next = (next + 1) % data.length;
            if (size < data.length) size++;
        }
    }
    public int size() { return size; }
    public void latest(short[] target) {
        if (target.length > size) throw new IllegalArgumentException("Not enough history");
        int start = (next - target.length + data.length) % data.length;
        int first = Math.min(target.length, data.length - start);
        System.arraycopy(data, start, target, 0, first);
        System.arraycopy(data, 0, target, first, target.length - first);
    }
    public short[] snapshot() { short[] out = new short[size]; latest(out); return out; }
}
