package dev.snorelocal

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses isolated database/files paths, never the user's real recordings or retention settings. */
@RunWith(AndroidJUnit4::class)
class StatisticsPersistenceTest {
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${root.name}-$name", mode)
    }

    @Test fun versionTwoMigrationPreservesRecordingsAndMarksMeasurementsUnavailable() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "stats-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(base, root)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("snore.db"), null).use { db ->
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, status TEXT NOT NULL, error TEXT, mode TEXT NOT NULL DEFAULT 'snoring')")
            db.execSQL("CREATE TABLE episodes(id TEXT PRIMARY KEY, session TEXT NOT NULL, start INTEGER NOT NULL, samples INTEGER NOT NULL, bytes INTEGER NOT NULL)")
            db.execSQL("INSERT INTO sessions VALUES('legacy',1000,2000,'completed',NULL,'snoring')")
            db.execSQL("INSERT INTO episodes VALUES('clip','legacy',1000,16000,123)")
            db.version = 2
        }
        val repository = Repository(context)
        val old = repository.nights().single()
        assertEquals("legacy", old.id)
        assertEquals("snoring", old.mode)
        assertFalse(old.measured)
        assertFalse(old.statsCleared)
        val clip = repository.episodes().single()
        assertEquals("clip", clip.id)
        assertEquals(16000L, clip.samples)
        assertNull(clip.logicalEpisodeId)
        val statistics = repository.statisticsSnapshot()
        assertTrue(statistics.events.isEmpty())
        assertTrue(statistics.minutes.isEmpty())
    }
    @Test fun deletingAndExpiringAudioPreservesStatisticsAndClearingStatisticsPreservesAudio() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "stats-retention-${UUID.randomUUID()}").apply { mkdirs() }
        val repository = Repository(IsolatedContext(base, root))
        val session = repository.begin()
        val now = System.currentTimeMillis()
        val event = DetectedEpisode("event", session, now, now + 1000, 16000, "UTC")
        val minute = MinuteStats(session, Math.floorDiv(now, 60_000) * 60_000, 16000, 16000, 1.0, .1)
        repository.saveMeasurements(session, MeasurementCheckpoint(listOf(event), listOf(minute), now + 1000))
        repository.finish(session, null)
        fun addClip(id: String, start: Long) {
            repository.file(id).writeBytes(byteArrayOf(1, 2, 3))
            repository.add(Episode(id, session, start, 16000, 3, event.id))
        }
        addClip("deleted", now)
        repository.delete("deleted")
        assertFalse(repository.file("deleted").exists())
        assertEquals(1, repository.statisticsSnapshot().events.size)
        addClip("expired", now - 30L * 86_400_000)
        repository.prune()
        assertFalse(repository.file("expired").exists())
        assertEquals(1, repository.statisticsSnapshot().events.size)
        assertEquals(1, repository.statisticsSnapshot().minutes.size)
        addClip("kept", now)
        repository.clearStatistics()
        val cleared = repository.statisticsSnapshot()
        assertTrue(cleared.events.isEmpty())
        assertTrue(cleared.minutes.isEmpty())
        assertEquals("kept", cleared.clips.single().id)
        assertTrue(repository.file("kept").exists())
        assertTrue(cleared.sessions.single().statsCleared)
    }

    @Test fun crashRecoveryMarksCheckpointedMeasurementsPartial() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "stats-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val repository = Repository(IsolatedContext(base, root))
        val session = repository.begin()
        val now = System.currentTimeMillis()
        repository.saveMeasurements(session, MeasurementCheckpoint(
            listOf(DetectedEpisode("unfinished", session, now, null, 8000, "UTC")),
            listOf(MinuteStats(session, Math.floorDiv(now, 60_000) * 60_000, 16000, 8000, 1.0, .1)),
            now + 1000,
        ))
        repository.recover()
        val recovered = repository.statisticsSnapshot()
        assertEquals("interrupted", recovered.sessions.single().status)
        assertTrue(recovered.events.single().partial)
        assertNotNull(recovered.events.single().end)
        assertEquals(8000L, recovered.events.single().snoreSamples)
    }

}
