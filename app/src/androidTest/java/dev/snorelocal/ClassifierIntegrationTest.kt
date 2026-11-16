package dev.snorelocal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ClassifierIntegrationTest {
    @Test fun bundledModelRunsAndSyntheticNonSnoreDoesNotActivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Yamnet(context).use { model ->
            val silence = ShortArray(Yamnet.WINDOW)
            val tone = ShortArray(Yamnet.WINDOW) { (sin(2 * PI * 440 * it / Yamnet.RATE) * 8000).toInt().toShort() }
            // Generated signals have no third-party audio rights and are negative smoke tests only.
            for (signal in listOf(silence, tone)) {
                val score = model.score(signal)
                assertTrue("Valid Snoring score", score.isFinite() && score in 0f..1f)
                assertTrue("Synthetic non-snore must not reach the default start threshold; got $score", score < .35f)
            }
        }
    }
    @Test fun licensedSnoreFixtureActivatesSegmentation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stream = runCatching { instrumentation.context.assets.open("snoring.pcm") }.getOrNull()
        org.junit.Assume.assumeTrue("CC0 snoring.pcm fixture has not been imported; see docs/VALIDATION.md", stream != null)
        val bytes = checkNotNull(stream).use { it.readBytes() }
        check(bytes.size % 2 == 0 && bytes.size >= Yamnet.WINDOW * 2) { "Fixture must be mono 16 kHz signed PCM16 LE" }
        val pcm = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining()).also { pcm.get(it) }
        var episodes = 0
        var peak = 0f
        val engine = EpisodeEngine(Yamnet.RATE * 5, Yamnet.RATE * 6L, Yamnet.RATE * 300L, .5f,
            object : EpisodeEngine.Sink {
                override fun open(startSample: Long) { episodes++ }
                override fun write(pcm: ShortArray, offset: Int, count: Int) = Unit
                override fun close(reason: String) = Unit
            })
        val gate = SoundGate(.5f)
        val ring = PcmRing(Yamnet.WINDOW)
        val window = ShortArray(Yamnet.WINDOW)
        var nextWindow = Yamnet.WINDOW
        var offset = 0
        Yamnet(instrumentation.targetContext).use { model ->
            while (offset < samples.size) {
                val n = minOf(1600, samples.size - offset, nextWindow - offset)
                val block = samples.copyOfRange(offset, offset + n)
                ring.add(block, 0, n)
                offset += n
                var score: Float? = null
                if (offset == nextWindow) {
                    ring.latest(window)
                    score = if (gate.allow(window, engine.isActive)) model.score(window) else 0f
                    peak = maxOf(peak, score)
                    nextWindow += Yamnet.HOP
                }
                engine.accept(block, n, score)
            }
        }
        engine.finish("stop")
        android.util.Log.i("ClassifierFixture", "CC0 fixture peak Snoring score=$peak, episodes=$episodes")
        assertTrue("CC0 fixture should activate at default sensitivity; peak=$peak", episodes > 0)
    }
    @Test fun streamingAacRoundTripAndSessionMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as SnoreApp).repository
        check(!repository.status.value.active) { "Stop monitoring before running integration tests" }
        val session = repository.begin()
        val origin = System.currentTimeMillis()
        val sink = AacEpisodeSink(repository, session) { origin }
        try {
            sink.open(16000)
            val pcm = ShortArray(1600) { (sin(2 * PI * 440 * it / Yamnet.RATE) * 8000).toInt().toShort() }
            repeat(20) { sink.write(pcm, 0, pcm.size) }
            sink.close("stop")
            val clip = repository.episodes().first { it.session == session }
            assertEquals(origin + 1000, clip.start)
            assertEquals(32000L, clip.samples)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(repository.file(clip.id).absolutePath)
                val format = extractor.getTrackFormat(0)
                assertEquals("audio/mp4a-latm", format.getString(android.media.MediaFormat.KEY_MIME))
                assertEquals(16000, format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(1, format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
                extractor.selectTrack(0)
                val encoded = java.nio.ByteBuffer.allocate(16_384)
                assertTrue("AAC container must contain encoded audio", extractor.readSampleData(encoded, 0) > 0)
                assertTrue("First AAC sample timestamp must be non-negative", extractor.sampleTime >= 0)
            } finally { extractor.release() }
        } finally {
            sink.abort()
            repository.episodes().filter { it.session == session }.forEach { repository.delete(it.id) }
            repository.finish(session, null)
        }
    }
}
