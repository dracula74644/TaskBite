package com.dracula.taskbite

import android.app.NotificationManager
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

enum class ReminderFix { NONE, ENABLE, NOTIFICATIONS, ALARMS, RECORDINGS, VOICE, VOLUME, SOUND }
data class ReminderOverview(val next: String, val detail: String, val voice: String,
    val fix: ReminderFix = ReminderFix.NONE, val action: String = "") {
    companion object {
        suspend fun read(app: TaskBiteApp, now: Long = System.currentTimeMillis()): ReminderOverview {
            val p = app.preferences
            val paused = p.teasingPaused(now)
            val allowed = Reminders.allowed(app)
            val next = Reminders.nextAt(app, now)
            val title = when {
                paused -> "Teasing paused until ${displayTime(p.teasingPausedUntil)}"
                !p.reminders -> "Task notifications are off"
                !allowed -> "Android notifications are blocked"
                p.quietEnabled && p.quietStart == p.quietEnd -> "Quiet hours last all day"
                next != null -> "Next reminder · ${displayTime(next)}"
                app.foreground && p.awayNudges -> "Away check-ins start after you leave"
                else -> "No reminder scheduled"
            }
            val detail = when {
                paused -> when {
                    !p.deadlinesDuringPause -> "Deadline notifications are paused too. Everything resumes automatically."
                    !p.reminders -> "Task notifications are off. Teasing resumes automatically after the break."
                    !allowed -> "Android is blocking deadline notifications. Teasing resumes automatically after the break."
                    else -> "Deadline notifications stay on, without teasing. Voice previews still work."
                }
                app.foreground && p.awayNudges -> "Away check-ins wait at least ${p.awayGapMinutes} minutes after leaving or task activity. Android may delay delivery."
                next != null -> "This is the earliest planned time. Android may deliver it later."
                else -> "Add a task or enable an empty-day prompt when you want a reminder."
            }
            val audio = app.getSystemService(AudioManager::class.java)
            val manager = app.getSystemService(NotificationManager::class.java)
            val library = VoiceLibrary(app)
            val missing = RoastKind.entries.count { library.available(it).isEmpty() }
            val (voice, fix, action) = when {
                !p.reminders -> Triple("Turn on task notifications for reminders outside Bite.", ReminderFix.ENABLE, "Enable task notifications")
                !allowed -> Triple("Allow Bite notifications in Android settings.", ReminderFix.NOTIFICATIONS, "Allow notifications")
                paused -> Triple("Automatic voice is paused.", ReminderFix.NONE, "")
                !p.backgroundVoice -> Triple("Background voice is off.", ReminderFix.VOICE, "Enable background voice")
                p.alertVolume == 0 -> Triple("Voice is muted in Bite.", ReminderFix.VOLUME, "Unmute voice")
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 -> Triple("Phone media volume is zero.", ReminderFix.SOUND, "Open sound settings")
                audio.ringerMode != AudioManager.RINGER_MODE_NORMAL -> Triple("Silent or vibrate mode pauses automatic voice.", ReminderFix.SOUND, "Open sound settings")
                manager.currentInterruptionFilter in setOf(NotificationManager.INTERRUPTION_FILTER_NONE,
                    NotificationManager.INTERRUPTION_FILTER_ALARMS, NotificationManager.INTERRUPTION_FILTER_PRIORITY) ->
                    Triple("Do Not Disturb pauses automatic voice.", ReminderFix.SOUND, "Open sound settings")
                !SpokenAlarms.available(app) -> Triple("Background voice needs Alarms & reminders access.", ReminderFix.ALARMS, "Allow spoken reminder alarms")
                missing > 0 -> Triple("${p.teasingStyle.label}: $missing/${RoastKind.entries.size} situations need a recording.", ReminderFix.RECORDINGS, "Add missing recordings")
                else -> Triple("Voice ready · ${p.alertVolume}% of media volume.", ReminderFix.NONE, "")
            }
            return ReminderOverview(title, detail, voice, fix, action)
        }
    }
}

@Composable fun ReminderOverviewCard(onFix: (ReminderFix) -> Unit, onPauseChanged: () -> Unit, onSilence: () -> Unit) {
    val app = LocalContext.current.applicationContext as TaskBiteApp
    val p = app.preferences
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var overview by remember { mutableStateOf<ReminderOverview?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var pauseMenu by rememberSaveable { mutableStateOf(false) }
    var paused by remember { mutableStateOf(p.teasingPaused()) }
    var deadlines by remember { mutableStateOf(p.deadlinesDuringPause) }
    LaunchedEffect(lifecycle, revision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                overview = withContext(Dispatchers.IO) { ReminderOverview.read(app) }
                paused = p.teasingPaused()
                delay(2_000)
            }
        }
    }
    fun pause(until: Long) {
        p.teasingPausedUntil = until
        paused = p.teasingPaused(); pauseMenu = false; revision++
        if (paused) { onSilence(); Reminders.clearAway(app); Reminders.clearPlanning(app) }
        onPauseChanged()
    }
    Card(colors = CardDefaults.cardColors(containerColor = PanelRaised), shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reminder status", style = MaterialTheme.typography.titleMedium)
            overview?.let { state ->
                Text(state.next, color = Lime)
                Text(state.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(state.voice, style = MaterialTheme.typography.bodyMedium)
                if (state.fix != ReminderFix.NONE) TextButton(onClick = { onFix(state.fix); revision++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(state.action) }
            }
            OutlinedButton(onClick = { if (paused) pause(0) else pauseMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (paused) "Resume teasing" else "Pause teasing")
            }
            if (paused) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Keep deadline reminders", Modifier.weight(1f))
                Switch(deadlines, { deadlines = it; p.deadlinesDuringPause = it; onPauseChanged(); revision++ },
                    modifier = Modifier.semantics { contentDescription = "Keep deadline reminders" })
            }
        }
    }
    if (pauseMenu) AlertDialog(onDismissRequest = { pauseMenu = false }, title = { Text("Take a break from teasing") },
        text = { Column {
            Text("Bite resumes automatically. Voice previews still work during your break.")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Keep deadline reminders", Modifier.weight(1f))
                Switch(deadlines, { deadlines = it; p.deadlinesDuringPause = it; onPauseChanged(); revision++ },
                    modifier = Modifier.semantics { contentDescription = "Keep deadline reminders" })
            }
        } },
        confirmButton = { TextButton(onClick = { pause(System.currentTimeMillis() + 60 * 60_000) }) { Text("For one hour") } },
        dismissButton = { TextButton(onClick = {
            val zone = ZoneId.systemDefault()
            val tomorrow = Instant.now().atZone(zone).toLocalDate().plusDays(1)
            pause(tomorrow.atTime(if (p.quietEnabled) p.quietEnd else 8, 0).atZone(zone).toInstant().toEpochMilli())
        }) { Text("Until tomorrow morning") } })
}
