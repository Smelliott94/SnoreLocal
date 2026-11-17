package dev.snorelocal

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class SnoreApp : Application() {
    lateinit var repository: Repository
        private set
    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        runCatching { repository.recover() }.onFailure {
            repository.report(false, "Could not open local recordings: ${it.message}. Free storage and reopen the app.", true)
        }
    }
}

data class Episode(val id: String, val session: String, val start: Long, val samples: Long, val bytes: Long, val logicalEpisodeId: String? = null) {
    val durationMs get() = samples * 1000 / Yamnet.RATE
}
data class Night(val id: String, val start: Long, val end: Long?, val status: String, val error: String?, val mode: String = "snoring", val zoneId: String = java.time.ZoneId.systemDefault().id, val measured: Boolean = false, val statsCleared: Boolean = false)
data class MonitorStatus(val active: Boolean = false, val message: String = "Ready for tonight", val error: Boolean = false)
data class Settings(val sensitivity: Float = .5f, val gapSeconds: Int = 6, val retentionDays: Int = 14, val limitMb: Int = 512, val recordEverything: Boolean = false)

private class Database(context: Context) : SQLiteOpenHelper(context, "snore.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, status TEXT NOT NULL, error TEXT, mode TEXT NOT NULL DEFAULT 'snoring', zone_id TEXT NOT NULL, measured INTEGER NOT NULL DEFAULT 0, stats_cleared INTEGER NOT NULL DEFAULT 0, last_sample INTEGER)")
        db.execSQL("CREATE TABLE episodes(id TEXT PRIMARY KEY, session TEXT NOT NULL, start INTEGER NOT NULL, samples INTEGER NOT NULL, bytes INTEGER NOT NULL, logical_episode TEXT)")
        createStatistics(db)
    }
    private fun createStatistics(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE detected_episodes(id TEXT PRIMARY KEY, session TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER, snore_samples INTEGER NOT NULL, zone_id TEXT NOT NULL, partial INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX detected_session_start ON detected_episodes(session,start)")
        db.execSQL("CREATE TABLE minute_stats(session TEXT NOT NULL, start INTEGER NOT NULL, monitored_samples INTEGER NOT NULL, snore_samples INTEGER NOT NULL, energy REAL NOT NULL, peak_rms REAL NOT NULL, levels BLOB NOT NULL, PRIMARY KEY(session,start))")
        db.execSQL("CREATE INDEX episodes_logical ON episodes(logical_episode)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'snoring'")
        if (oldVersion < 3) {
            val zone = java.time.ZoneId.systemDefault().id.replace("'", "''")
            db.execSQL("ALTER TABLE sessions ADD COLUMN zone_id TEXT NOT NULL DEFAULT '$zone'")
            db.execSQL("ALTER TABLE sessions ADD COLUMN measured INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN stats_cleared INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN last_sample INTEGER")
            db.execSQL("ALTER TABLE episodes ADD COLUMN logical_episode TEXT")
            createStatistics(db)
        }
    }
}

class Repository(private val context: Context) {
    private val database = Database(context)
    private val db get() = database.writableDatabase
    val directory = File(context.filesDir, "episodes")
    private fun ensureDirectory() { check(directory.isDirectory || directory.mkdirs()) { "Cannot create private recording directory" } }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var cachedBytes: Long? = null
    private val mutableStatus = MutableStateFlow(MonitorStatus())
    val status = mutableStatus.asStateFlow()
    fun report(active: Boolean, message: String, error: Boolean = false) { mutableStatus.value = MonitorStatus(active, message, error) }
    fun settings() = Settings(prefs.getFloat("sensitivity", .5f), prefs.getInt("gap", 6), prefs.getInt("days", 14), prefs.getInt("mb", 512), prefs.getBoolean("recordEverything", false))
    fun save(settings: Settings) {
        prefs.edit().putFloat("sensitivity", settings.sensitivity.coerceIn(0f, 1f))
            .putInt("gap", settings.gapSeconds.coerceIn(2, 15)).putInt("days", settings.retentionDays.coerceIn(1, 90))
            .putInt("mb", settings.limitMb.coerceIn(64, 4096)).putBoolean("recordEverything", settings.recordEverything).apply()
    }
    @Synchronized fun recover() {
        ensureDirectory()
        cachedBytes = null
        val running = nights().any { it.status == "monitoring" }
        db.execSQL("UPDATE sessions SET status='interrupted', end=COALESCE(last_sample,?), error='Monitoring ended unexpectedly; recovered measurements may be partial and unfinished audio was removed.' WHERE status='monitoring'", arrayOf(System.currentTimeMillis()))
        markInterruptedEvents()
        val known = episodes().associateBy { it.id }
        directory.listFiles()?.forEach { f ->
            if (f.extension == "part" || (f.extension == "m4a" && f.nameWithoutExtension !in known)) check(f.delete()) { "Cannot remove incomplete recording" }
        }
        known.values.forEach { if (!file(it.id).exists()) db.delete("episodes", "id=?", arrayOf(it.id)) }
        val last = nights().firstOrNull()
        if (running || last?.status == "interrupted") report(false, last?.error ?: "Previous session interrupted", true)
    }
    private fun markInterruptedEvents() {
        db.execSQL("UPDATE detected_episodes SET partial=1, end=COALESCE(end,(SELECT COALESCE(last_sample,end,start) FROM sessions WHERE sessions.id=detected_episodes.session)) WHERE session IN (SELECT id FROM sessions WHERE status='interrupted')")
    }
    @Synchronized fun begin(recordEverything: Boolean = false, zoneId: String = java.time.ZoneId.systemDefault().id): String {
        prune()
        checkStorage()
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("start", System.currentTimeMillis()); put("status", "monitoring"); put("mode", if (recordEverything) "all_audio" else "snoring")
            put("zone_id", zoneId); put("measured", if (recordEverything) 0 else 1)
        })
        return id
    }
    @Synchronized fun finish(id: String, error: String?) {
        db.update("sessions", ContentValues().apply {
            put("end", System.currentTimeMillis()); put("status", if (error == null) "completed" else "interrupted")
            if (error != null) put("error", error)
        }, "id=?", arrayOf(id))
        if (error != null) markInterruptedEvents()
    }
    fun file(id: String) = File(directory, "$id.m4a")
    @Synchronized fun add(episode: Episode) {
        cachedBytes = null
        db.insertOrThrow("episodes", null, ContentValues().apply {
            put("id", episode.id); put("session", episode.session); put("start", episode.start)
            put("samples", episode.samples); put("bytes", episode.bytes)
            put("logical_episode", episode.logicalEpisodeId)
        })
    }
    @Synchronized fun episodes(): List<Episode> = db.rawQuery("SELECT id,session,start,samples,bytes,logical_episode FROM episodes ORDER BY start DESC", null).use { c ->
        buildList {
            while (c.moveToNext()) add(Episode(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), if (c.isNull(5)) null else c.getString(5)))
        }
    }
    @Synchronized fun nights(): List<Night> = db.rawQuery("SELECT id,start,end,status,error,mode,zone_id,measured,stats_cleared FROM sessions ORDER BY start DESC", null).use { c ->
        buildList {
            while (c.moveToNext()) add(Night(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4), c.getString(5), c.getString(6), c.getInt(7) != 0, c.getInt(8) != 0))
        }
    }
    @Synchronized fun saveMeasurements(session: String, checkpoint: MeasurementCheckpoint) {
        db.beginTransaction()
        try {
            for (event in checkpoint.events) {
                db.insertWithOnConflict("detected_episodes", null, ContentValues().apply {
                    put("id", event.id); put("session", event.session); put("start", event.start)
                    if (event.end == null) putNull("end") else put("end", event.end)
                    put("snore_samples", event.snoreSamples); put("zone_id", event.zoneId)
                    put("partial", if (event.partial) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not save episode statistics" } }
            }
            for (minute in checkpoint.minutes) {
                db.insertWithOnConflict("minute_stats", null, ContentValues().apply {
                    put("session", minute.session); put("start", minute.start)
                    put("monitored_samples", minute.monitoredSamples); put("snore_samples", minute.snoreSamples)
                    put("energy", minute.energy); put("peak_rms", minute.peakRms); put("levels", minute.levels)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not save sound measurements" } }
            }
            db.update("sessions", ContentValues().apply { put("last_sample", checkpoint.lastCapturedAt) }, "id=?", arrayOf(session))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun statisticsSnapshot(): StatisticsSnapshot {
        db.beginTransaction()
        try {
            val events = db.rawQuery("SELECT id,session,start,end,snore_samples,zone_id,partial FROM detected_episodes ORDER BY start DESC", null).use { c ->
                buildList { while (c.moveToNext()) add(DetectedEpisode(c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getLong(3), c.getLong(4), c.getString(5), c.getInt(6) != 0)) }
            }
            val minutes = db.rawQuery("SELECT session,start,monitored_samples,snore_samples,energy,peak_rms,levels FROM minute_stats ORDER BY start", null).use { c ->
                buildList { while (c.moveToNext()) add(MinuteStats(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getDouble(4), c.getDouble(5), c.getBlob(6))) }
            }
            val snapshot = StatisticsSnapshot(events, minutes, nights(), episodes())
            db.setTransactionSuccessful()
            return snapshot
        } finally { db.endTransaction() }
    }

    @Synchronized fun clearStatistics() {
        check(!status.value.active) { "Stop monitoring before clearing statistics" }
        db.beginTransaction()
        try {
            db.delete("detected_episodes", null, null)
            db.delete("minute_stats", null, null)
            db.execSQL("UPDATE sessions SET stats_cleared=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun delete(id: String) {
        cachedBytes = null
        val file = file(id)
        check(!file.exists() || file.delete()) { "Could not delete recording" }
        db.delete("episodes", "id=?", arrayOf(id))
    }
    @Synchronized fun prune() {
        val config = settings()
        val cutoff = System.currentTimeMillis() - config.retentionDays * 86_400_000L
        episodes().filter { it.start < cutoff }.forEach { delete(it.id) }
        var total = episodes().sumOf { it.bytes }
        for (episode in episodes().asReversed()) {
            if (total <= config.limitMb * 1024L * 1024L) break
            delete(episode.id); total -= episode.bytes
        }
        db.execSQL("DELETE FROM sessions WHERE status != 'monitoring' AND start < ? AND id NOT IN (SELECT session FROM episodes) AND id NOT IN (SELECT session FROM detected_episodes) AND id NOT IN (SELECT session FROM minute_stats)", arrayOf(cutoff))
    }
    @Synchronized fun checkStorage(pendingBytes: Long = 0L) {
        ensureDirectory()
        check(directory.usableSpace >= 32L * 1024 * 1024) { "Storage is nearly full (less than 32 MiB free). Free space before monitoring." }
        val committed = cachedBytes ?: db.rawQuery("SELECT COALESCE(SUM(bytes),0) FROM episodes", null).use { c ->
            c.moveToFirst(); c.getLong(0)
        }.also { cachedBytes = it }
        val used = committed + pendingBytes
        check(used < settings().limitMb * 1024L * 1024) { "Recording storage limit reached. Delete clips or increase the limit." }
    }
}
