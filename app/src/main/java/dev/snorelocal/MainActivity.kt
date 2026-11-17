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
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.time.ZoneId

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
        var snapshot by remember { mutableStateOf(StatisticsSnapshot()) }
        var message by remember { mutableStateOf<String?>(null) }
        var selected by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf(false) }
        var position by remember { mutableFloatStateOf(0f) }
        var duration by remember { mutableFloatStateOf(1f) }
        var seeking by remember { mutableStateOf(false) }
        var deleteTarget by remember { mutableStateOf<Episode?>(null) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var settingsExpanded by rememberSaveable { mutableStateOf(false) }
        var expandedNights by rememberSaveable { mutableStateOf(listOf<String>()) }
        var expandedEpisodes by rememberSaveable { mutableStateOf(listOf<String>()) }
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
                runCatching { withContext(Dispatchers.IO) { repository.statisticsSnapshot() } }
                    .onSuccess { snapshot = it }
                    .onFailure { if (it is CancellationException) throw it; message = "Could not read recordings: ${it.message}" }
            }
        }
        fun play(episode: Episode) {
            if (selected == episode.id && player != null) {
                runCatching {
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                }.onFailure { error -> stopPlayback(); message = "Playback failed: ${error.message}" }
                return
            }
            stopPlayback()
            try {
                val manager = getSystemService(AudioManager::class.java)
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { change ->
                        if (change != AudioManager.AUDIOFOCUS_GAIN) { runCatching { player?.pause() }; playing = false }
                    }.build()
                check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio output is unavailable" }
                focus = request
                val p = MediaPlayer()
                player = p
                p.setAudioAttributes(attrs)
                p.setDataSource(repository.file(episode.id).absolutePath)
                p.setOnPreparedListener { ready ->
                    if (player !== ready || repository.status.value.active || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
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
            } catch (t: Exception) { stopPlayback(); message = "Playback failed: ${t.message}" }
        }
        fun share(episode: Episode) {
            stopPlayback()
            runCatching {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", repository.file(episode.id))
                val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Snoring episode", uri) }
                startActivity(Intent.createChooser(send, "Share recording"))
            }.onFailure { message = "Sharing failed: ${it.message}" }
        }
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { repository.statisticsSnapshot() } }
                        .onSuccess { snapshot = it }
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
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Snore Local", style = MaterialTheme.typography.titleLarge)
                    Text(status.message, color = if (status.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Record everything (mic test)", style = MaterialTheme.typography.labelLarge)
                            Text("All audio; excluded from snoring statistics", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = settings.recordEverything, enabled = !status.active, onCheckedChange = { save(settings.copy(recordEverything = it)) })
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
                }
            }
            PrimaryTabRow(selectedTabIndex = page) {
                listOf("Recordings", "Statistics").forEachIndexed { index, title ->
                    Tab(selected = page == index, onClick = { stopPlayback(); page = index }, text = { Text(title) })
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "settings") {
                    Card(Modifier.fillMaxWidth()) {
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { settingsExpanded = !settingsExpanded }) {
                            Text(if (settingsExpanded) "− Monitoring and storage settings" else "+ Monitoring and storage settings")
                        }
                        if (settingsExpanded) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Detection sensitivity • ${(settings.sensitivity * 100).toInt()}%")
                            Slider(value = settings.sensitivity, onValueChange = { save(settings.copy(sensitivity = it)) }, enabled = !status.active && !settings.recordEverything)
                            Text("Higher sensitivity lowers the classifier threshold. Settings apply to the next session.", style = MaterialTheme.typography.bodySmall)
                            Text("Merge gap and tail: ${settings.gapSeconds}s")
                            Slider(value = settings.gapSeconds.toFloat(), valueRange = 2f..15f, steps = 12, onValueChange = { save(settings.copy(gapSeconds = it.toInt())) }, enabled = !status.active && !settings.recordEverything)
                            Text("Keep audio: ${settings.retentionDays} days")
                            Slider(value = settings.retentionDays.toFloat(), valueRange = 1f..90f, onValueChange = { save(settings.copy(retentionDays = it.toInt())) }, enabled = !status.active)
                            Text("Audio storage limit: ${settings.limitMb} MiB")
                            Slider(value = settings.limitMb.toFloat(), valueRange = 64f..4096f, onValueChange = { save(settings.copy(limitMb = it.toInt())) }, enabled = !status.active)
                            Text("Audio expires when monitoring starts or you apply retention. Statistics remain until you clear them. Sessions stop after 12 hours.", style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = !status.active, onClick = {
                                stopPlayback()
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { repository.prune() } }
                                        .onFailure { if (it is CancellationException) throw it; message = "Retention failed: ${it.message}" }
                                    refresh()
                                }
                            }) { Text("Apply audio retention now") }
                        }
                    }
                }
                message?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (page == 0) {
                    item(key = "recordings-heading") {
                        Text("Your nights", style = MaterialTheme.typography.headlineSmall)
                        if (status.active) Text("Stop monitoring before playing or sharing recordings.", style = MaterialTheme.typography.bodySmall)
                    }
                    recordingGroups(snapshot, expandedNights, { key ->
                        if (key in expandedNights) { stopPlayback(); expandedNights = expandedNights - key }
                        else expandedNights = expandedNights + key
                    }, expandedEpisodes, { key ->
                        if (key in expandedEpisodes) { stopPlayback(); expandedEpisodes = expandedEpisodes - key }
                        else expandedEpisodes = expandedEpisodes + key
                    }) { episode ->
                        ClipPlaybackCard(episode, status.active, selected == episode.id, playing, position, duration,
                            onSeek = { seeking = true; position = it },
                            onSeekFinished = {
                                runCatching { player?.seekTo(position.toInt()) }.onFailure { message = "Seeking failed: ${it.message}"; stopPlayback() }
                                seeking = false
                            },
                            onPlay = { play(episode) }, onShare = { share(episode) }, onDelete = { deleteTarget = episode })
                    }
                } else item(key = "statistics") {
                    StatisticsPanel(snapshot, status.active) {
                        stopPlayback()
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { repository.clearStatistics() } }
                                .onFailure { if (it is CancellationException) throw it; message = "Could not clear statistics: ${it.message}" }
                            refresh()
                        }
                    }
                }
                item(key = "privacy") { Text("Everything stays on this device. Detection can miss snores or include other sounds; this is not a medical diagnostic tool.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        deleteTarget?.let { target ->
            AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Delete audio recording?") },
                text = { Text("The ${formatTime(target.start, ZoneId.systemDefault().id)} audio clip will be permanently removed. Its snoring statistics will remain.") },
                confirmButton = { TextButton(enabled = !status.active, onClick = {
                    stopPlayback(); deleteTarget = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { repository.delete(target.id) } }
                            .onFailure { if (it is CancellationException) throw it; message = "Delete failed: ${it.message}" }
                        refresh()
                    }
                }) { Text("Delete audio") } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } })
        }
    }

    @Composable private fun ClipPlaybackCard(
        episode: Episode, monitoring: Boolean, selected: Boolean, playing: Boolean, position: Float, duration: Float,
        onSeek: (Float) -> Unit, onSeekFinished: () -> Unit, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
    ) {
        Card(Modifier.fillMaxWidth().padding(start = 20.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Audio clip • ${formatDuration(episode.durationMs)}", style = MaterialTheme.typography.titleMedium)
                Text("AAC audio • ${episode.bytes / 1024} KiB", style = MaterialTheme.typography.bodySmall)
                if (selected) {
                    Slider(value = position.coerceIn(0f, duration), valueRange = 0f..duration, onValueChange = onSeek, onValueChangeFinished = onSeekFinished)
                    Text("${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !monitoring, onClick = onPlay) { Text(if (selected && playing) "Pause" else "Play") }
                    TextButton(enabled = !monitoring, onClick = onShare) { Text("Share") }
                    TextButton(enabled = !monitoring, onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}
