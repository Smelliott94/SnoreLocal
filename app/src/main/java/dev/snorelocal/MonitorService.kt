package dev.snorelocal

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MonitorService : Service() {
    private val repository get() = (application as SnoreApp).repository
    private val running = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private val lastRead = AtomicLong(0)
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var beganElapsed = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = SystemClock.elapsedRealtime()
            when {
                now - beganElapsed > 12 * 60 * 60 * 1000L -> fail("12-hour monitoring limit reached; start a new session if needed.")
                now - lastRead.get() > 15_000 -> fail("Microphone or classifier stopped responding. Monitoring stopped.")
                else -> handler.postDelayed(this, 5000)
            }
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sleep monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ERROR_CHANNEL, "Monitoring errors", NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { requestStop(); if (worker == null) stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (worker != null) return START_NOT_STICKY
        try {
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is unavailable" }
            if (Build.VERSION.SDK_INT >= 33) check(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Allow notifications so monitoring status and Stop remain visible" }
            val foregroundType = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, 1, notification("Starting microphone…"), foregroundType)
            getSystemService(NotificationManager::class.java).cancel(2)
            running.set(true); failure.set(null)
            repository.report(true, "Preparing microphone…")
            beganElapsed = SystemClock.elapsedRealtime(); lastRead.set(beganElapsed)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:monitoring").apply {
                setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L + 60_000)
            }
            handler.postDelayed(watchdog, 5000)
            worker = Thread({ monitor() }, "snore-processing").apply { start() }
        } catch (t: Throwable) {
            running.set(false)
            val message = "Could not start monitoring: ${t.message ?: t.javaClass.simpleName}"
            repository.report(false, message, true)
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun requestStop() {
        running.set(false)
        if (worker != null) repository.report(true, "Stopping and saving episode…")
        runCatching { recorder?.stop() }
    }
    private fun fail(message: String) {
        failure.compareAndSet(null, message)
        requestStop()
        repository.report(true, "$message Saving available audio…", true)
    }
    // onStartCommand checks runtime grants before launching this worker. Revocation is caught below.
    @SuppressLint("MissingPermission")
    private fun monitor() {
        var session: String? = null
        var classifier: Yamnet? = null
        var sink: AacEpisodeSink? = null
        var engine: EpisodeEngine? = null
        var continuous: ContinuousRecorder? = null
        var capture: Thread? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        val queue = ArrayBlockingQueue<Block>(20)
        val pool = ArrayBlockingQueue<ShortArray>(24).apply { repeat(24) { add(ShortArray(BLOCK)) } }
        val captureDone = AtomicBoolean(false)
        try {
            val settings = repository.settings()
            session = repository.begin(settings.recordEverything)
            if (!settings.recordEverything) {
                classifier = try { Yamnet(this) } catch (t: Throwable) { throw IllegalStateException("Snoring classifier failed: ${t.message}", t) }
            }
            if (!running.get()) return
            check(!getSystemService(AudioManager::class.java).isMicrophoneMute) { "Microphone is muted in Android settings" }
            val minimum = AudioRecord.getMinBufferSize(Yamnet.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Device does not support mono 16 kHz PCM microphone capture" }
            val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(Yamnet.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, BLOCK * 8)).build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED && audio.sampleRate == Yamnet.RATE) { "Microphone initialization failed or sample rate unsupported" }
            if (Build.VERSION.SDK_INT >= 29) {
                callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if (running.get() && configs.any { it.clientAudioSessionId == audio.audioSessionId && it.isClientSilenced }) {
                            fail("Microphone access was silenced by Android or another app. Monitoring stopped.")
                        }
                    }
                }
                audio.registerAudioRecordingCallback(mainExecutor, callback)
            }
            val anchorMs = AtomicLong(System.currentTimeMillis())
            val anchorElapsed = SystemClock.elapsedRealtime()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start recording" }
            sink = AacEpisodeSink(repository, session) { anchorMs.get() }
            if (settings.recordEverything) continuous = ContinuousRecorder(Yamnet.RATE * 300L, sink)
            else engine = EpisodeEngine(Yamnet.RATE * 5, Yamnet.RATE * settings.gapSeconds.toLong(), Yamnet.RATE * 300L, settings.sensitivity, sink)
            val gate = SoundGate(settings.sensitivity)
            val windowRing = PcmRing(Yamnet.WINDOW)
            val window = ShortArray(Yamnet.WINDOW)
            var nextWindow = Yamnet.WINDOW.toLong()
            capture = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var total = 0L
                try {
                    while (running.get()) {
                        val data = pool.poll() ?: error("Audio processing fell behind (buffer pool exhausted)")
                        val n = audio.read(data, 0, data.size, AudioRecord.READ_BLOCKING)
                        if (!running.get()) { pool.offer(data); break }
                        check(n > 0) { "Microphone read failed (code $n)" }
                        if (total == 0L) {
                            val timestamp = AudioTimestamp()
                            if (audio.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                                // Timestamp framePosition is relative to this startRecording call.
                                anchorMs.set(System.currentTimeMillis() -
                                    (SystemClock.elapsedRealtimeNanos() - timestamp.nanoTime) / 1_000_000 -
                                    timestamp.framePosition * 1000 / Yamnet.RATE)
                            }
                        }
                        total += n; lastRead.set(SystemClock.elapsedRealtime())
                        val drift = lastRead.get() - anchorElapsed - total * 1000 / Yamnet.RATE
                        check(drift < 2000) { "Microphone capture was interrupted; audio timing is no longer reliable" }
                        check(queue.offer(Block(data, n))) { "Audio processing fell behind; stopped to avoid losing audio" }
                    }
                } catch (t: Throwable) { if (running.get()) fail("Audio capture failed: ${t.message}") }
                finally { captureDone.set(true) }
            }, "snore-capture").apply { start() }
            val monitoringMessage = if (settings.recordEverything) "Mic test • recording ALL audio" else "Monitoring for snoring • local processing"
            repository.report(true, "$monitoringMessage • screen may be turned off")
            getSystemService(NotificationManager::class.java).notify(1, notification(monitoringMessage))
            var lastStorageCheck = 0L
            while (!captureDone.get() || queue.isNotEmpty()) {
                val block = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                try {
                    if (continuous != null) {
                        continuous.accept(block.pcm, block.count)
                        val end = continuous.position()
                        if (end - lastStorageCheck >= Yamnet.RATE) {
                            repository.checkStorage(sink.pendingBytes)
                            check(!getSystemService(AudioManager::class.java).isMicrophoneMute) { "Microphone was muted" }
                            lastStorageCheck = end
                        }
                        continue
                    }
                    val detector = checkNotNull(engine)
                    // Split at exact model hop boundaries even when AudioRecord returns partial blocks.
                    var offset = 0
                    while (offset < block.count) {
                        val n = minOf(block.count - offset, (nextWindow - detector.position()).toInt())
                        val chunk = scratch
                        System.arraycopy(block.pcm, offset, chunk, 0, n)
                        windowRing.add(chunk, 0, n)
                        val end = detector.position() + n
                        var score: Float? = null
                        if (end == nextWindow) {
                            windowRing.latest(window)
                            score = if (gate.allow(window, detector.isActive)) {
                                try { checkNotNull(classifier).score(window) } catch (t: Throwable) { throw IllegalStateException("Snoring classifier failed: ${t.message}", t) }
                            } else 0f
                            nextWindow += Yamnet.HOP
                        }
                        detector.accept(chunk, n, score)
                        offset += n
                        if (end - lastStorageCheck >= Yamnet.RATE) { repository.checkStorage(sink.pendingBytes); check(!getSystemService(AudioManager::class.java).isMicrophoneMute) { "Microphone was muted" }; lastStorageCheck = end }
                    }
                } finally { pool.offer(block.pcm) }
            }
            val reason = if (failure.get() == null) "stop" else "interrupted"
            engine?.finish(reason)
            continuous?.finish(reason)
        } catch (t: Throwable) {
            failure.compareAndSet(null, "Monitoring stopped: ${t.message ?: t.javaClass.simpleName}")
            // Stop capture before potentially slow encoder finalization.
            running.set(false)
            runCatching { recorder?.stop() }
            // Preserve valid audio already written when possible; never commit an invalid encoder file.
            runCatching { engine?.finish("interrupted"); continuous?.finish("interrupted") }.onFailure { sink?.abort() }
        } finally {
            running.set(false)
            runCatching { recorder?.stop() }
            runCatching { capture?.join(2000) }
            if (Build.VERSION.SDK_INT >= 29 && callback != null) runCatching { recorder?.unregisterAudioRecordingCallback(callback) }
            runCatching { recorder?.release() }; recorder = null
            runCatching { classifier?.close() }
            sink?.abort()
            if (session != null) runCatching { repository.finish(session, failure.get()) }.onFailure { failure.compareAndSet(null, "Could not save session state: ${it.message}") }
            handler.post {
                handler.removeCallbacks(watchdog)
                releaseWakeLock()
                val error = failure.get()
                repository.report(false, error ?: "Monitoring stopped • recordings saved", error != null)
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (error != null) runCatching { getSystemService(NotificationManager::class.java).notify(2, notification(error, true)) }
                } finally {
                    worker = null
                    stopSelf()
                }
            }
        }
    }
    private val scratch = ShortArray(BLOCK)
    private data class Block(val pcm: ShortArray, val count: Int)
    private fun notification(message: String, error: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, if (error) ERROR_CHANNEL else CHANNEL)
            .setSmallIcon(R.drawable.ic_moon).setContentTitle(if (error) "Sleep monitoring stopped" else "Snore Local")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(!error).setOnlyAlertOnce(!error)
            .apply { if (!error) addAction(0, "Stop", stop) }.build()
    }
    private fun releaseWakeLock() {
        val lock = wake
        wake = null
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
    override fun onDestroy() {
        if (running.get()) fail("Monitoring service was stopped by Android")
        handler.removeCallbacks(watchdog)
        releaseWakeLock()
        super.onDestroy()
    }
    companion object {
        const val START = "dev.snorelocal.START"
        const val STOP = "dev.snorelocal.STOP"
        private const val CHANNEL = "monitoring"
        private const val ERROR_CHANNEL = "errors"
        private const val BLOCK = 1600
    }
}
