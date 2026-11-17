package dev.snorelocal

/** Fixed allocation, chronological PCM history. Owned by the processing worker. */
class PcmRing(capacity: Int) {
    private val data: ShortArray
    private var next = 0
    private var size = 0

    init {
        require(capacity > 0) { "capacity" }
        data = ShortArray(capacity)
    }

    fun add(source: ShortArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            data[next] = source[i]
            next = (next + 1) % data.size
            if (size < data.size) size++
        }
    }

    fun size(): Int = size

    fun latest(target: ShortArray) {
        require(target.size <= size) { "Not enough history" }
        val start = (next - target.size + data.size) % data.size
        val first = minOf(target.size, data.size - start)
        data.copyInto(target, destinationOffset = 0, startIndex = start, endIndex = start + first)
        data.copyInto(target, destinationOffset = first, startIndex = 0, endIndex = target.size - first)
    }

    fun snapshot(): ShortArray = ShortArray(size).also(::latest)
}
