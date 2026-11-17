package dev.snorelocal

import java.util.Random

/** Dependency-free contract checks; also run by JUnit. */
object CoreChecks {
    class Clip(var start: Long) {
        var count = 0
        val audio = mutableListOf<Short>()
        var reason = ""
    }

    class MemorySink : EpisodeEngine.Sink {
        val clips = mutableListOf<Clip>()
        var current: Clip? = null
            private set

        override fun open(startSample: Long) {
            check(current == null, "sink already open")
            current = Clip(startSample)
        }

        override fun write(pcm: ShortArray, offset: Int, count: Int) {
            val clip = current ?: throw AssertionError("sink not open")
            for (i in offset until offset + count) clip.audio.add(pcm[i])
            clip.count += count
        }

        override fun close(reason: String) {
            val clip = current ?: throw AssertionError("sink not open")
            clip.reason = reason
            clips.add(clip)
            current = null
        }
    }

    fun check(ok: Boolean, label: String) {
        if (!ok) throw AssertionError(label)
    }

    fun equal(expected: Number, actual: Number, label: String) {
        check(expected.toLong() == actual.toLong(), "$label: expected $expected, got $actual")
    }

    fun sequence(start: Int, count: Int) = ShortArray(count) { (start + it).toShort() }

    private fun feed(engine: EpisodeEngine, count: Int, score: Float?) {
        engine.accept(sequence(engine.position().toInt(), count), count, score)
    }

    private fun scheduledStream(chunkSize: Int, randomSeed: Long): List<Clip> {
        val sink = MemorySink()
        val engine = EpisodeEngine(50, 60, 100, .5f, sink)
        val at = intArrayOf(70, 80, 90, 120, 130, 140, 300, 310, 320)
        val scores = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val random = Random(randomSeed)
        var event = 0
        while (engine.position() < 500) {
            val boundary = if (event < at.size) at[event] else 500
            val proposed = if (chunkSize > 0) chunkSize else random.nextInt(17) + 1
            val count = minOf(proposed, boundary - engine.position().toInt())
            val score = if (engine.position() + count == boundary.toLong() && event < at.size) scores[event++] else null
            feed(engine, count, score)
        }
        engine.finish("stop")
        return sink.clips
    }

    private fun sameClips(expected: List<Clip>, actual: List<Clip>, label: String) {
        equal(expected.size, actual.size, "$label clip count")
        expected.zip(actual).forEach { (a, b) ->
            equal(a.start, b.start, "$label start")
            equal(a.count, b.count, "$label duration")
            check(a.reason == b.reason, "$label close reason")
            check(a.audio == b.audio, "$label sample continuity")
        }
    }

    fun runAll() {
        val ring = PcmRing(5)
        ring.add(sequence(0, 3), 0, 3)
        check(sequence(0, 3).contentEquals(ring.snapshot()), "partial ring order")
        ring.add(sequence(3, 9), 0, 9)
        equal(5, ring.size(), "bounded ring")
        check(sequence(7, 5).contentEquals(ring.snapshot()), "wrapped ring order")
        val latest = ShortArray(3)
        ring.latest(latest)
        check(sequence(9, 3).contentEquals(latest), "latest window")

        var sink = MemorySink()
        var engine = EpisodeEngine(50, 60, 300, .5f, sink) // 10 Hz scaled time
        feed(engine, 30, 0f); feed(engine, 30, 0f); feed(engine, 10, 1f)
        equal(20, checkNotNull(sink.current).start, "five second pre-roll real start")
        feed(engine, 10, 0f); feed(engine, 10, 0f)
        feed(engine, 50, null) // Smoothed score holds until sample 80; deadline 140.
        equal(1, sink.clips.size, "tail closes episode")
        val first = sink.clips.first()
        equal(120, first.count, "pre-roll plus trailing audio")
        equal(20, first.audio.first(), "first retained sample")
        equal(139, first.audio.last(), "last tail sample")
        equal(1_000_000L + 20L * 1000 / 10, 1_000_000L + first.start * 1000 / 10, "timestamp from sample clock")
        feed(engine, 10, 0f); engine.finish("stop")
        equal(1, sink.clips.size, "no recording during silence")

        sink = MemorySink(); engine = EpisodeEngine(50, 60, 300, .5f, sink)
        feed(engine, 50, 0f); feed(engine, 10, 1f); feed(engine, 10, 0f); feed(engine, 40, null)
        feed(engine, 10, 1f) // Renewed detection within quiet gap.
        feed(engine, 20, null); engine.finish("stop")
        equal(1, sink.clips.size, "nearby detections merged")
        equal(130, sink.clips.first().count, "Stop commits current episode immediately")
        check(sink.clips.first().reason == "stop", "stop reason")

        sink = MemorySink(); engine = EpisodeEngine(50, 60, 100, .5f, sink)
        repeat(37) { feed(engine, 10, 1f) }
        engine.finish("stop")
        equal(4, sink.clips.size, "sustained episode split count")
        var expected = 0
        for (clip in sink.clips) {
            equal(expected, clip.start, "split contiguous timestamp")
            check(clip.count <= 100, "maximum clip duration")
            for (sample in clip.audio) equal((expected++).toShort(), sample, "no missing/duplicate audio at split")
        }
        equal(370, expected, "all sustained episode audio retained")

        sink = MemorySink(); engine = EpisodeEngine(5, 6, 10, .5f, sink)
        feed(engine, 5, 1f); feed(engine, 5, 1f); engine.finish("stop")
        equal(1, sink.clips.size, "no empty clip on exact split Stop")
        sink = MemorySink(); engine = EpisodeEngine(5, 6, 10, .5f, sink)
        feed(engine, 5, 1f); feed(engine, 5, 0f); feed(engine, 10, 0f); engine.finish("stop")
        equal(2, sink.clips.size, "quiet deadline across split")
        equal(6, sink.clips[1].count, "exact tail trim inside block")

        sink = MemorySink(); engine = EpisodeEngine(5, 6, 10, .5f, sink)
        feed(engine, 3, 0f); feed(engine, 2, 1f); engine.finish("stop")
        equal(0, sink.clips.first().start, "startup short pre-roll never negative")
        var invalid = false
        try { feed(engine, 1, Float.NaN) } catch (_: IllegalArgumentException) { invalid = true }
        check(invalid, "classifier invalid output fails explicitly")

        val low = MemorySink()
        val high = MemorySink()
        val lowEngine = EpisodeEngine(5, 6, 10, 0f, low)
        val highEngine = EpisodeEngine(5, 6, 10, 1f, high)
        feed(lowEngine, 3, .4f); feed(highEngine, 3, .4f)
        check(!lowEngine.isActive && highEngine.isActive, "sensitivity changes actual classification threshold")
        lowEngine.finish("stop"); highEngine.finish("stop")

        // A coarse block cannot bridge an expired gap because its final score is high.
        sink = MemorySink(); engine = EpisodeEngine(50, 60, 300, .5f, sink)
        feed(engine, 50, 1f); feed(engine, 10, 0f); feed(engine, 10, 0f); feed(engine, 130, 1f)
        engine.finish("stop")
        equal(2, sink.clips.size, "expired merge deadline starts a separate episode")
        equal(0, sink.clips.first().start, "old episode original start")
        equal(120, sink.clips.first().count, "old episode ends at exact prior deadline")
        equal(150, sink.clips[1].start, "new episode uses fresh pre-roll")
        equal(50, sink.clips[1].count, "new episode pre-roll length")
        sink = MemorySink(); engine = EpisodeEngine(50, 60, 300, .5f, sink)
        feed(engine, 50, 1f); feed(engine, 10, 0f); feed(engine, 10, 0f); feed(engine, 50, 1f)
        engine.finish("stop")
        equal(1, sink.clips.size, "detection exactly at merge deadline is merged")
        equal(120, sink.clips.first().count, "equality boundary retains all audio")

        val singleSample = scheduledStream(1, 0)
        sameClips(singleSample, scheduledStream(16, 0), "partial model-boundary blocks")
        repeat(20) { seed -> sameClips(singleSample, scheduledStream(0, seed.toLong()), "random capture partition $seed") }

        sink = MemorySink(); engine = EpisodeEngine(50, 1000, 100, .5f, sink)
        feed(engine, 50, 1f); feed(engine, 450, 1f); engine.finish("stop")
        equal(5, sink.clips.size, "multiple splits inside one accepted block")
        expected = 0
        for (clip in sink.clips) {
            equal(expected, clip.start, "large block split timestamp")
            equal(100, clip.count, "large block duration cap")
            for (sample in clip.audio) equal(expected++, sample, "large block split continuity")
        }
        engine.finish("stop")
        equal(5, sink.clips.size, "repeated Stop is idempotent")

        for (score in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f)) {
            var rejected = false
            val invalidSink = MemorySink()
            val invalidEngine = EpisodeEngine(5, 6, 10, .5f, invalidSink)
            try { invalidEngine.accept(shortArrayOf(12345), 1, score) } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected, "invalid classifier score rejected: $score")
            equal(0, invalidEngine.position(), "invalid score leaves sample clock unchanged")
            feed(invalidEngine, 1, 1f); invalidEngine.finish("stop")
            equal(1, invalidSink.clips.first().count, "invalid score leaves history unchanged")
            equal(0, invalidSink.clips.first().audio.first(), "invalid score audio is not retained")
        }

        sink = MemorySink(); engine = EpisodeEngine(5, 6, 10, .5f, sink)
        engine.accept(shortArrayOf(), 0, 1f)
        equal(0, engine.position(), "empty read does not advance clock")
        check(!engine.isActive, "empty read cannot create episode")
        for (count in intArrayOf(-1, 2)) {
            var rejected = false
            try { engine.accept(ShortArray(1), count, 0f) } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected, "invalid sample count rejected")
            equal(0, engine.position(), "invalid sample count leaves clock unchanged")
        }
        for (sensitivity in floatArrayOf(Float.NaN, -.01f, 1.01f)) {
            var rejected = false
            try { EpisodeEngine(5, 6, 10, sensitivity, MemorySink()) } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected, "invalid sensitivity rejected")
        }

        var written = 0L
        var openCount = 0L
        val counting = object : EpisodeEngine.Sink {
            override fun open(startSample: Long) { openCount++ }
            override fun write(pcm: ShortArray, offset: Int, count: Int) { written += count }
            override fun close(reason: String) = Unit
        }
        engine = EpisodeEngine(80_000, 96_000, 4_800_000, .5f, counting)
        val reusable = ShortArray(1600)
        repeat(288_000) { engine.accept(reusable, reusable.size, 0f) } // Eight hours at 16 kHz.
        engine.finish("stop")
        equal(460_800_000, engine.position(), "eight hour sample clock")
        equal(0, written, "silence never written")
        equal(0, openCount, "silence never opens clip")
        println("Core checks passed: ring wrap, bounded overnight history, pre-roll, timestamps, tail, merging, split continuity, partial/random read partitions, repeated Stop, thresholds, invalid scores.")
    }

    @JvmStatic fun main(args: Array<String>) = runAll()
}
