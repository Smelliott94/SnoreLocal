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

data class Episode(val id: String, val session: String, val start: Long, val samples: Long, val bytes: Long) {
    val durationMs get() = samples * 1000 / Yamnet.RATE
}
data class Night(val id: String, val start: Long, val end: Long?, val status: String, val error: String?, val mode: String = "snoring")
data class MonitorStatus(val active: Boolean = false, val message: String = "Ready for tonight", val error: Boolean = false)
data class Settings(val sensitivity: Float = .5f, val gapSeconds: Int = 6, val retentionDays: Int = 14, val limitMb: Int = 512, val recordEverything: Boolean = false)

private class Database(context: Context) : SQLiteOpenHelper(context, "snore.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, start INTEGER NOT NULL, end INTEGER, status TEXT NOT NULL, error TEXT, mode TEXT NOT NULL DEFAULT 'snoring')")
        db.execSQL("CREATE TABLE episodes(id TEXT PRIMARY KEY, session TEXT NOT NULL, start INTEGER NOT NULL, samples INTEGER NOT NULL, bytes INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'snoring'")
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
        db.execSQL("UPDATE sessions SET status='interrupted', end=?, error='Monitoring ended unexpectedly; unfinished clip removed.' WHERE status='monitoring'", arrayOf(System.currentTimeMillis()))
        val known = episodes().associateBy { it.id }
        directory.listFiles()?.forEach { f ->
            if (f.extension == "part" || (f.extension == "m4a" && f.nameWithoutExtension !in known)) check(f.delete()) { "Cannot remove incomplete recording" }
        }
        known.values.forEach { if (!file(it.id).exists()) db.delete("episodes", "id=?", arrayOf(it.id)) }
        val last = nights().firstOrNull()
        if (running || last?.status == "interrupted") report(false, last?.error ?: "Previous session interrupted", true)
    }
    @Synchronized fun begin(recordEverything: Boolean = false): String {
        prune()
        checkStorage()
        val id = UUID.randomUUID().toString()
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", id); put("start", System.currentTimeMillis()); put("status", "monitoring"); put("mode", if (recordEverything) "all_audio" else "snoring")
        })
        return id
    }
    @Synchronized fun finish(id: String, error: String?) {
        db.update("sessions", ContentValues().apply {
            put("end", System.currentTimeMillis()); put("status", if (error == null) "completed" else "interrupted")
            if (error != null) put("error", error)
        }, "id=?", arrayOf(id))
    }
    fun file(id: String) = File(directory, "$id.m4a")
    @Synchronized fun add(episode: Episode) {
        cachedBytes = null
        db.insertOrThrow("episodes", null, ContentValues().apply {
            put("id", episode.id); put("session", episode.session); put("start", episode.start)
            put("samples", episode.samples); put("bytes", episode.bytes)
        })
    }
    @Synchronized fun episodes(): List<Episode> = db.rawQuery("SELECT id,session,start,samples,bytes FROM episodes ORDER BY start DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Episode(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4))) }
    }
    @Synchronized fun nights(): List<Night> = db.rawQuery("SELECT id,start,end,status,error,mode FROM sessions ORDER BY start DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Night(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4), c.getString(5))) }
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
        db.execSQL("DELETE FROM sessions WHERE status != 'monitoring' AND start < ? AND id NOT IN (SELECT session FROM episodes)", arrayOf(cutoff))
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
