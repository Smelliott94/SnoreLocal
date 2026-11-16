package dev.snorelocal

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val repository get() = (application as SnoreApp).repository
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBAD0FF), background = Color(0xFF10131B), surface = Color(0xFF1A202C))) {
                Surface(modifier = Modifier.fillMaxSize()) { Home() }
            }
        }
    }
    private fun releasePlayer() {
        val previous = player
        player = null
        runCatching { previous?.release() }
        focus?.let { request -> runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) } }
        focus = null
    }
    override fun onStop() { releasePlayer(); super.onStop() }
    override fun onDestroy() { releasePlayer(); super.onDestroy() }
    private fun startMonitoring() {
        releasePlayer()
        try {
            check(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "Open the app to start microphone monitoring" }
            check(NotificationManagerCompat.from(this).areNotificationsEnabled()) { "Enable app notifications in Android settings to see monitoring and Stop" }
            val channel = getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("monitoring")
            check(channel == null || channel.importance != android.app.NotificationManager.IMPORTANCE_NONE) { "Enable the Sleep monitoring notification channel in Android settings" }
            repository.report(true, "Starting monitoring…")
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java).setAction(MonitorService.START))
        } catch (t: Exception) { repository.report(false, "Could not start: ${t.message}", true) }
    }
    @Composable private fun Home() {
        val status by repository.status.collectAsStateWithLifecycle()
        var settings by remember { mutableStateOf(repository.settings()) }
        var episodes by remember { mutableStateOf(emptyList<Episode>()) }
        var nights by remember { mutableStateOf(emptyList<Night>()) }
        var message by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableFloatStateOf(0f) }
        var duration by remember { mutableFloatStateOf(1f) }
        var seeking by remember { mutableStateOf(false) }
        var deleteTarget by remember { mutableStateOf<Episode?>(null) }
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startMonitoring()
            else repository.report(false, "Microphone and notification permissions are required. Grant them in Android app settings if previously denied.", true)
        }
        fun save(config: Settings) { settings = config; repository.save(config) }
        fun stopPlayback() { releasePlayer(); playing = false; selected = null }
        fun refresh() {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.episodes() to repository.nights() } }
                    .onSuccess { episodes = it.first; nights = it.second }
                    .onFailure { if (it is CancellationException) throw it; message = "Could not read recordings: ${it.message}" }
            }
        }
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { repository.episodes() to repository.nights() } }
                        .onSuccess { episodes = it.first; nights = it.second }
                        .onFailure { if (it is CancellationException) throw it; message = "Could not read recordings: ${it.message}" }
                    delay(2000)
                }
            }
        }
        LaunchedEffect(selected) {
            while (selected != null) {
                if (!seeking) player?.let { p -> runCatching {
                    position = p.currentPosition.toFloat(); duration = p.duration.toFloat().coerceAtLeast(1f); playing = p.isPlaying
                } }
                if (player == null) { selected = null; playing = false }
                delay(250)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("SNORE LOCAL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("A quieter morning.", style = MaterialTheme.typography.headlineLarge)
                Text("Private snore recordings. Everything stays on this device.", style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (status.active) "Listening tonight" else "Sleep monitoring", style = MaterialTheme.typography.titleLarge)
                        Text(status.message, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Record everything (mic test)", style = MaterialTheme.typography.titleMedium)
                                Text("Saves all audio until Stop, in five-minute clips. Snoring classification is bypassed.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = settings.recordEverything, enabled = !status.active,
                                onCheckedChange = { save(settings.copy(recordEverything = it)) })
                        }
                        Button(modifier = Modifier.fillMaxWidth(), onClick = {
                            message = null
                            if (status.active) startService(Intent(this@MainActivity, MonitorService::class.java).setAction(MonitorService.STOP))
                            else {
                                stopPlayback()
                                val needed = buildList {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
                                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                if (needed.isEmpty()) startMonitoring() else permissions.launch(needed.toTypedArray())
                            }
                        }) { Text(if (status.active) "Stop recording" else if (settings.recordEverything) "Start mic test" else "Start monitoring") }
                        Text("Turn the screen off after starting. A Stop control stays in your notifications. Sessions stop after 12 hours.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(18.dp)) {
                        Text("Detection sensitivity • ${(settings.sensitivity * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Slider(value = settings.sensitivity, onValueChange = { save(settings.copy(sensitivity = it)) }, enabled = !status.active && !settings.recordEverything)
                        Text("Higher sensitivity lowers the classifier threshold and sound gate. Settings apply to the next session.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Text("Merge gap and tail: ${settings.gapSeconds}s")
                        Slider(value = settings.gapSeconds.toFloat(), valueRange = 2f..15f, steps = 12,
                            onValueChange = { save(settings.copy(gapSeconds = it.toInt())) }, enabled = !status.active && !settings.recordEverything)
                        Text("Keep recordings: ${settings.retentionDays} days")
                        Slider(value = settings.retentionDays.toFloat(), valueRange = 1f..90f,
                            onValueChange = { save(settings.copy(retentionDays = it.toInt())) }, enabled = !status.active)
                        Text("Storage limit: ${settings.limitMb} MiB")
                        Slider(value = settings.limitMb.toFloat(), valueRange = 64f..4096f,
                            onValueChange = { save(settings.copy(limitMb = it.toInt())) }, enabled = !status.active)
                        Text("Old clips are removed when monitoring starts or you apply retention. Monitoring stops if storage fills.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !status.active, onClick = {
                            stopPlayback()
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { repository.prune() } }
                                    .onFailure { message = "Retention failed: ${it.message}" }
                                refresh()
                            }
                        }) { Text("Apply retention now") }
                    }
                }
            }
            message?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("Your nights", style = MaterialTheme.typography.headlineSmall)
                if (status.active) Text("Stop monitoring before playing or sharing recordings.", style = MaterialTheme.typography.bodySmall)
                if (nights.isEmpty()) Text("Snoring episodes will appear here after your first night.")
            }
            val grouped = nights.groupBy { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).minusHours(12).toLocalDate() }
            grouped.forEach { (date, sessions) ->
                item(key = "night-$date") { Text("Night of ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))}", style = MaterialTheme.typography.titleLarge) }
                sessions.forEach { night ->
                    item(key = "session-${night.id}") {
                        Text("${time(night.start)} • ${if (night.mode == "all_audio") "Mic test • " else ""}${night.status}" + (night.end?.let { " • ended ${time(it)}" } ?: ""), style = MaterialTheme.typography.labelLarge)
                        night.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (episodes.none { it.session == night.id }) Text("No saved episodes", style = MaterialTheme.typography.bodySmall)
                    }
                    episodes.filter { it.session == night.id }.forEach { episode ->
                        item(key = episode.id) {
                            Card {
                                Column(Modifier.padding(16.dp)) {
                                    Text("${time(episode.start)} • ${elapsed(episode.durationMs)}", style = MaterialTheme.typography.titleMedium)
                                    Text("AAC audio • ${episode.bytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                                    if (selected == episode.id) {
                                        Slider(value = position.coerceIn(0f, duration), valueRange = 0f..duration,
                                            onValueChange = { seeking = true; position = it },
                                            onValueChangeFinished = {
                                                runCatching { player?.seekTo(position.toInt()) }
                                                    .onFailure { message = "Seeking failed: ${it.message}"; stopPlayback() }
                                                seeking = false
                                            })
                                        Text("${elapsed(position.toLong())} / ${elapsed(duration.toLong())}")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(enabled = !status.active, onClick = {
                                            if (selected == episode.id && player != null) {
                                                runCatching {
                                                    if (playing) player?.pause() else player?.start()
                                                    playing = !playing
                                                }.onFailure { error -> stopPlayback(); message = "Playback failed: ${error.message}" }
                                            } else {
                                                stopPlayback()
                                                try {
                                                    val manager = getSystemService(AudioManager::class.java)
                                                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                                                    val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                                                        .setOnAudioFocusChangeListener { change ->
                                                            if (change != AudioManager.AUDIOFOCUS_GAIN) {
                                                                runCatching { player?.pause() }
                                                                playing = false
                                                            }
                                                        }.build()
                                                    check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio output is unavailable" }
                                                    focus = request
                                                    val p = MediaPlayer()
                                                    player = p // Own resources before any configuration call can throw.
                                                    p.apply {
                                                        setAudioAttributes(attrs)
                                                        p.setDataSource(repository.file(episode.id).absolutePath)
                                                        p.setOnPreparedListener { ready ->
                                                            if (player !== ready || repository.status.value.active ||
                                                                !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                                                if (player === ready) stopPlayback() else runCatching { ready.release() }
                                                            } else {
                                                                runCatching {
                                                                    duration = ready.duration.toFloat().coerceAtLeast(1f); position = 0f
                                                                    selected = episode.id; ready.start(); playing = true
                                                                }.onFailure { error -> stopPlayback(); message = "Playback failed: ${error.message}" }
                                                            }
                                                        }
                                                        p.setOnCompletionListener { playing = false }
                                                        p.setOnErrorListener { _, what, extra -> message = "Playback failed ($what/$extra)"; stopPlayback(); true }
                                                        p.prepareAsync()
                                                    }
                                                } catch (t: Exception) { stopPlayback(); message = "Playback failed: ${t.message}" }
                                            }
                                        }) { Text(if (selected == episode.id && playing) "Pause" else "Play") }
                                        TextButton(enabled = !status.active, onClick = {
                                            stopPlayback()
                                            runCatching {
                                                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", repository.file(episode.id))
                                                val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Snoring episode", uri) }
                                                startActivity(Intent.createChooser(send, "Share snoring episode"))
                                            }.onFailure { message = "Sharing failed: ${it.message}" }
                                        }) { Text("Share") }
                                        TextButton(enabled = !status.active, onClick = { deleteTarget = episode }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("YAMNet classifies sound, without identifying people. Detection can miss snores or include other sounds; this app is not a medical diagnostic tool.", style = MaterialTheme.typography.bodySmall) }
        }
        deleteTarget?.let { target ->
            AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete recording?") },
                text = { Text("The ${time(target.start)} episode will be permanently removed.") },
                confirmButton = { TextButton(onClick = {
                    stopPlayback(); deleteTarget = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.delete(target.id) } }.onFailure { message = "Delete failed: ${it.message}" }
                        refresh()
                    }
                }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
        }
    }
    private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    private fun elapsed(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
}
