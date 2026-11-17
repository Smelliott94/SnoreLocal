package dev.snorelocal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

private val QuietColour = Color(0xFF4CCAB7)
private val MediumColour = Color(0xFFFFCF69)
private val LoudColour = Color(0xFFFF6868)

/** The colour scale and bar height never depend on the loudest value in the current graph. */
internal fun soundLevelColour(level: Float): Color {
    val amount = level.coerceIn(0f, 1f)
    return if (amount <= .5f) lerp(QuietColour, MediumColour, amount * 2)
    else lerp(MediumColour, LoudColour, (amount - .5f) * 2)
}

@Composable
internal fun SoundLevelGraph(
    levels: List<Float>,
    modifier: Modifier = Modifier,
    measured: List<Boolean> = levels.map { it.isFinite() },
    caption: String = "Relative sound level",
) {
    val chunk = max(1, ceil(levels.size / 288.0).toInt())
    val bars = levels.indices.toList().chunked(chunk).map { indices ->
        val available = indices.filter { measured.getOrElse(it) { false } && levels[it].isFinite() }
        (available.maxOfOrNull { levels[it].coerceIn(0f, 1f) } ?: 0f) to available.isNotEmpty()
    }
    val present = bars.filter { it.second }
    val peak = present.maxOfOrNull { it.first }
    val description = if (peak == null) "$caption. No measured audio available."
    else "$caption. ${present.size} measured sections. Highest relative level: ${when {
        peak < .33f -> "quiet"
        peak < .67f -> "moderate"
        else -> "loud"
    }}. Teal is quieter, amber is moderate, and red is louder."
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 6.dp).semantics { contentDescription = description }) {
            val track = Color(0xFF536070)
            drawLine(track.copy(alpha = .4f), Offset(0f, size.height), Offset(size.width, size.height))
            if (bars.isNotEmpty()) {
                val slot = size.width / bars.size
                bars.forEachIndexed { index, (level, available) ->
                    val width = (slot * .78f).coerceAtLeast(.5f)
                    val height = if (available) max(2.dp.toPx(), size.height * level) else 2.dp.toPx()
                    drawRoundRect(
                        color = if (available) soundLevelColour(level) else track.copy(alpha = .5f),
                        topLeft = Offset(index * slot, size.height - height),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(minOf(width / 2, 2.dp.toPx())),
                    )
                }
            }
        }
        if (peak == null) Text("No measured audio", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun LazyListScope.recordingGroups(
    snapshot: StatisticsSnapshot,
    expandedNights: List<String>,
    onToggleNight: (String) -> Unit,
    expandedEpisodes: List<String>,
    onToggleEpisode: (String) -> Unit,
    clipContent: @Composable (Episode) -> Unit,
) {
    val sessions = snapshot.sessions.associateBy { it.id }
    val sessionsByDate = snapshot.sessions.groupBy { Statistics.nightDate(it.start, it.zoneId) }
    val eventsByDate = snapshot.events.groupBy { Statistics.nightDate(it.start, it.zoneId) }
    val minutesByDate = snapshot.minutes.filter { it.monitoredSamples > 0 }.groupBy { minute ->
        Statistics.nightDate(minute.start, sessions[minute.session]?.zoneId ?: ZoneId.systemDefault().id)
    }
    val dates = (sessionsByDate.keys + eventsByDate.keys + minutesByDate.keys).sortedDescending()
    if (dates.isEmpty()) item { Text("Your nights and snoring episodes will appear here after monitoring.") }
    val knownEventIds = snapshot.events.mapTo(hashSetOf()) { it.id }
    dates.forEach { date ->
        val key = date.toString()
        val expanded = key in expandedNights
        val events = eventsByDate[date].orEmpty().sortedByDescending { it.start }
        val relatedSessionIds = events.mapTo(hashSetOf()) { it.session }
        minutesByDate[date].orEmpty().forEach { relatedSessionIds.add(it.session) }
        val nightSessions = (sessionsByDate[date].orEmpty() + relatedSessionIds.mapNotNull { sessions[it] }).distinctBy { it.id }
        val micOnly = nightSessions.isNotEmpty() && nightSessions.all { it.mode == "all_audio" }
        item(key = "night-$key") {
            val summary = remember(snapshot, date) { Statistics.summarize(snapshot, StatsPeriod.NIGHT, date) }
            Card(onClick = { onToggleNight(key) }, modifier = Modifier.fillMaxWidth().semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Night of ${date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}", style = MaterialTheme.typography.titleMedium)
                        Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
                    }
                    when {
                        micOnly -> Text("Mic test recordings • excluded from snoring statistics")
                        summary.monitoredMs > 0 -> Text("${summary.episodeCount} episodes • ${formatDuration(summary.snoreMs)} estimated snoring")
                        nightSessions.any { it.statsCleared } -> Text("Statistics cleared • saved audio remains available")
                        nightSessions.any { it.measured } -> Text("No measured audio yet • ${if (nightSessions.any { it.status == "monitoring" }) "monitoring is in progress" else "no monitoring coverage was saved"}")
                        else -> Text("Detection statistics not available for older recordings")
                    }
                    if (summary.episodeCount == 0 && summary.snoreMs > 0) Text("Includes snoring from an episode that began on the previous night", style = MaterialTheme.typography.bodySmall)
                    if (summary.monitoredMs > 0) SoundLevelGraph(summary.points.map { it.level }, measured = summary.points.map { it.monitoredMs > 0 })
                    if (summary.partial) Text("Partial or ongoing monitoring", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Text(if (expanded) "Tap to collapse" else "Tap to see recordings", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (expanded) {
            nightSessions.forEach { session ->
                item(key = "night-$key-session-${session.id}") {
                    Column(Modifier.padding(horizontal = 10.dp)) {
                        Text("${formatTime(session.start, session.zoneId)} • ${if (session.mode == "all_audio") "Mic test • " else ""}${session.status}", style = MaterialTheme.typography.labelLarge)
                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            events.forEach { event ->
                val clips = snapshot.clips.filter { it.logicalEpisodeId == event.id }.sortedBy { it.start }
                val eventExpanded = event.id in expandedEpisodes
                val live = event.end == null && sessions[event.session]?.status == "monitoring"
                item(key = "event-${event.id}") {
                    Card(onClick = { onToggleEpisode(event.id) }, modifier = Modifier.fillMaxWidth().padding(start = 10.dp).semantics {
                        stateDescription = if (eventExpanded) "Expanded" else "Collapsed"
                    }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${if (eventExpanded) "−" else "+"} Episode at ${formatTime(event.start, event.zoneId)}", style = MaterialTheme.typography.titleMedium)
                            Text("Estimated snoring: ${formatDuration(event.snoreSamples * 1000 / Yamnet.RATE)}")
                            if (clips.isNotEmpty()) Text("Recorded audio: ${formatDuration(clips.sumOf { it.durationMs })} • ${clips.size} ${if (clips.size == 1) "clip" else "clips"}", style = MaterialTheme.typography.bodySmall)
                            else Text(if (live) "Recording in progress • audio available after the clip is saved" else "Audio deleted", style = MaterialTheme.typography.bodySmall)
                            SoundLevelGraph(Statistics.episodeLevels(event, snapshot.minutes, clips.minOfOrNull { it.start }, clips.maxOfOrNull { it.start + it.durationMs }))
                            if (event.partial || event.end == null) Text(if (live) "Episode still in progress" else "Partial episode • recording was interrupted", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (eventExpanded) clips.forEach { clip -> item(key = "clip-${clip.id}") { clipContent(clip) } }
            }
            val otherClips = snapshot.clips.filter { clip ->
                clip.logicalEpisodeId !in knownEventIds && sessions[clip.session]?.let { Statistics.nightDate(it.start, it.zoneId) == date } == true
            }.sortedByDescending { it.start }
            otherClips.forEach { clip ->
                val session = sessions[clip.session]
                val clipKey = "recording-${clip.id}"
                val clipExpanded = clipKey in expandedEpisodes
                item(key = clipKey) {
                    Card(onClick = { onToggleEpisode(clipKey) }, modifier = Modifier.fillMaxWidth().padding(start = 10.dp).semantics {
                        stateDescription = if (clipExpanded) "Expanded" else "Collapsed"
                    }) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${if (clipExpanded) "−" else "+"} ${if (session?.mode == "all_audio") "Mic test" else "Recording"} at ${formatTime(clip.start, session?.zoneId ?: ZoneId.systemDefault().id)}", style = MaterialTheme.typography.titleMedium)
                            Text("Recorded audio: ${formatDuration(clip.durationMs)}")
                            Text(if (session?.mode == "all_audio") "All audio • excluded from snoring statistics" else "Estimated snoring time and sound level: not available", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (clipExpanded) item(key = "clip-${clip.id}") { clipContent(clip) }
            }
            if (events.isEmpty() && otherClips.isEmpty()) item(key = "night-$key-empty") {
                val summary = remember(snapshot, date) { Statistics.summarize(snapshot, StatsPeriod.NIGHT, date) }
                Text(if (summary.snoreMs > 0) "The continuing episode is listed under the night when it began." else "No saved episodes", Modifier.padding(start = 10.dp))
            }
        }
    }
}

@Composable
internal fun StatisticsPanel(snapshot: StatisticsSnapshot, monitoring: Boolean, onClear: () -> Unit) {
    var periodName by rememberSaveable { mutableStateOf(StatsPeriod.NIGHT.name) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val period = StatsPeriod.valueOf(periodName)
    val sessionById = remember(snapshot.sessions) { snapshot.sessions.associateBy { it.id } }
    val latestMeasured = snapshot.minutes.filter { it.monitoredSamples > 0 && sessionById[it.session]?.mode != "all_audio" }
        .maxOfOrNull { minute -> Statistics.nightDate(minute.start, sessionById[minute.session]?.zoneId ?: ZoneId.systemDefault().id) }
    val today = Statistics.nightDate(System.currentTimeMillis(), ZoneId.systemDefault().id)
    val date = selectedDate?.let(LocalDate::parse) ?: latestMeasured ?: today
    val summary = remember(snapshot, period, date) { Statistics.summarize(snapshot, period, date) }
    fun shifted(amount: Long) = when (period) {
        StatsPeriod.NIGHT -> date.plusDays(amount)
        StatsPeriod.WEEK -> date.plusWeeks(amount)
        StatsPeriod.MONTH -> date.plusMonths(amount)
    }
    val next = shifted(1)
    val mostRecent = maxOf(today, latestMeasured ?: today)
    val canNext = Statistics.summarize(StatisticsSnapshot(), period, next).startDate <= mostRecent
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Snoring statistics", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsPeriod.entries.forEach { choice ->
                FilterChip(selected = choice == period, onClick = { selectedDate = date.toString(); periodName = choice.name }, label = { Text(choice.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selectedDate = shifted(-1).toString() }) { Text("Previous") }
            TextButton(enabled = canNext, onClick = { selectedDate = next.toString() }) { Text("Next") }
        }
        Text(when (period) {
            StatsPeriod.NIGHT -> "Night of ${summary.startDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
            StatsPeriod.WEEK -> "${summary.startDate.format(DateTimeFormatter.ofPattern("d MMM"))} – ${summary.endDate.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
            StatsPeriod.MONTH -> summary.startDate.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
        }, style = MaterialTheme.typography.titleLarge)
        if (summary.monitoredMs <= 0) {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No measured monitoring data", style = MaterialTheme.typography.titleMedium)
                Text(if (summary.legacy) "Older recordings remain playable. Detection measurements are available only for new monitoring sessions." else "Monitor a night with snoring detection enabled to see statistics here. Mic tests are excluded.")
            } }
        } else {
            MetricPair("Episodes", summary.episodeCount.toString(), "Estimated snoring", formatDuration(summary.snoreMs))
            MetricPair("Monitored time", formatDuration(summary.monitoredMs), "Of monitored time", summary.snorePercent?.let { "%.1f%%".format(it) } ?: "Not available")
            MetricPair("Episodes / hour", summary.episodesPerHour?.let { "%.1f".format(it) } ?: "Not available", "Longest episode", formatDuration(summary.longestEpisodeMs))
            if (period != StatsPeriod.NIGHT) {
                MetricPair("Tracked nights", summary.trackedNights.toString(), "Episodes / tracked night", summary.averageEpisodes?.let { "%.1f".format(it) } ?: "Not available")
                MetricPair("Snoring / tracked night", summary.averageSnoreMs?.let(::formatDuration) ?: "Not available", "Missing nights", "Not counted as zero")
            }
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SoundLevelGraph(summary.points.map { it.level }, measured = summary.points.map { it.monitoredMs > 0 })
                if (summary.points.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(graphLabel(summary.points.first().label, period), style = MaterialTheme.typography.labelSmall)
                    Text(graphLabel(summary.points.last().label, period), style = MaterialTheme.typography.labelSmall)
                }
                Text("Teal: quieter • Amber: moderate • Red: louder", style = MaterialTheme.typography.bodySmall)
                Text("Grey marks missing measurements. Levels depend on microphone gain and phone position; the scale stays consistent across views.", style = MaterialTheme.typography.bodySmall)
            } }
            if (summary.partial) Text("This period includes ongoing or interrupted monitoring. Statistics represent measured audio only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (summary.legacy) Text("Some older recordings have no detection measurements and are excluded from these totals.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Snoring time is an estimate from classifier-positive audio. It excludes recording pre-roll and quiet tails and is not a measurement of sleep duration.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !monitoring && (snapshot.events.isNotEmpty() || snapshot.minutes.isNotEmpty()), onClick = { confirmClear = true }) { Text("Clear statistics history") }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear statistics history?") },
        text = { Text("This removes all saved sound-level and snoring measurements. Your audio recordings remain available. New monitoring sessions will collect statistics again.") },
        confirmButton = { TextButton(enabled = !monitoring, onClick = { confirmClear = false; onClear() }) { Text("Clear statistics") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MetricPair(first: String, firstValue: String, second: String, secondValue: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Metric(first, firstValue, Modifier.weight(1f))
        Metric(second, secondValue, Modifier.weight(1f))
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge)
    } }
}

private fun graphLabel(label: String, period: StatsPeriod): String = if (period == StatsPeriod.NIGHT) label
else runCatching { LocalDate.parse(label).format(DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault(label)

internal fun formatDuration(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0) / 1000
    return if (total >= 3600) "%dh %02dm".format(total / 3600, total / 60 % 60) else "%dm %02ds".format(total / 60, total % 60)
}

internal fun formatTime(milliseconds: Long, zoneId: String): String = Instant.ofEpochMilli(milliseconds)
    .atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()))
    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
