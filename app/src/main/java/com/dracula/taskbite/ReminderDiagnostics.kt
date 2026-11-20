package com.dracula.taskbite

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

data class ReminderHealth(val status: String, val voice: String, val chime: String, val restricted: Boolean, val allowed: Boolean, val alarms: Boolean) {
    companion object {
        fun read(context: Context): ReminderHealth {
            val p = Preferences(context)
            val allowed = Reminders.allowed(context)
            val alarms = SpokenAlarms.available(context)
            val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            val notifications = context.getSystemService(NotificationManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            val status = when {
                !allowed -> "Android notifications are blocked"
                !p.reminders -> "Task notifications are off"
                restricted -> "Android is restricting Bite in the background"
                p.quietEnabled && p.quietStart == p.quietEnd -> "Quiet hours pause reminders all day"
                p.isQuiet() -> "Quiet hours · resumes at %02d:00".format(p.quietEnd)
                !p.awayNudges -> "Deadline reminders on · away nudges off"
                else -> "Away nudges on · ${p.awayGapMinutes}-minute gap"
            }
            val voice = when {
                !p.backgroundVoice -> "Background voice is off. Enable Spoken task reactions in Sidekick."
                p.alertVolume == 0 -> "Voice is muted by Bite's volume slider."
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 -> "Phone media volume is zero."
                notifications.currentInterruptionFilter in listOf(NotificationManager.INTERRUPTION_FILTER_NONE,
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALARMS) -> "DND pauses automatic voice."
                audio.ringerMode != AudioManager.RINGER_MODE_NORMAL -> "Silent or vibrate mode pauses automatic voice."
                !alarms -> "Allow Alarms & reminders for background voice."
                else -> "Background voice enabled. Missing recordings stay text-only."
            }
            val chime = "System notification chime: off. Bite plays only its recorded voice."
            return ReminderHealth(status, voice, chime, restricted, allowed, alarms)
        }
    }
}

@Composable fun ReminderDiagnostics(onTest: () -> Unit, onSettings: () -> Unit, onBattery: () -> Unit,
    onAlarms: () -> Unit, onVoiceControls: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf(ReminderHealth.read(context)) }
    var report by remember { mutableStateOf(DeliveryStatus.report(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var test by remember { mutableStateOf(NotificationTest.state(context)) }
    var canceling by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                health = ReminderHealth.read(context)
                report = DeliveryStatus.report(context)
                now = System.currentTimeMillis()
                test = NotificationTest.state(context, now)
                delay(1_000)
            }
        }
    }
    if (test.phase != TestDeliveryPhase.IDLE) {
        val remaining = ((test.dueAt - now + 999) / 1_000).coerceAtLeast(0)
        val (title, detail) = when (test.phase) {
            TestDeliveryPhase.QUEUED -> if (remaining > 0) "Test scheduled" to "Press Home now. Ready in $remaining seconds."
                else "Waiting for Android" to "The 10-second wait is over. Android may take longer to deliver it."
            TestDeliveryPhase.SENDING -> "Sending test" to "Preparing your notification."
            TestDeliveryPhase.SENT -> "Test notification sent" to "Check your notification shade. Tap the card to try opening it, or swipe it away to try clearing it. Send another test to try both."
            TestDeliveryPhase.BLOCKED -> "Test blocked" to "Allow Bite notifications, then try again."
            TestDeliveryPhase.CANCELED -> "Test canceled" to "Your regular reminders are unchanged."
            TestDeliveryPhase.EXPIRED -> "Test didn't finish" to "Android didn't finish this test in time. Check battery settings, then try again."
            TestDeliveryPhase.IDLE -> "" to ""
        }
        Surface(color = PanelRaised, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = if (test.phase in setOf(TestDeliveryPhase.BLOCKED, TestDeliveryPhase.EXPIRED)) Coral else Lime,
                    style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (!test.pending && test.dueAt > 0) Text("Last test · " + java.time.Instant.ofEpochMilli(test.dueAt)
                    .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")),
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(onClick = { feedback = null; onTest() }, enabled = !test.pending && !canceling,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (test.pending) "Test in progress" else "Send test notification")
    }
    if (test.phase == TestDeliveryPhase.QUEUED) OutlinedButton(onClick = {
        canceling = true
        scope.launch {
            try {
                if (!NotificationTest.cancel(context.applicationContext as TaskBiteApp)) feedback = "The test is already sending. Check your notification shade."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = "Reopen Bite to refresh reminder scheduling." }
            finally { canceling = false; test = NotificationTest.state(context) }
        }
    }, enabled = !canceling, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel test") }
    feedback?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
    val prefs = Preferences(context)
    val voiceHint = when {
        !prefs.backgroundVoice -> "This test is notification-only. Turn on Spoken task reactions in Sidekick to hear a voice."
        prefs.alertVolume == 0 -> "Bite's voice volume is zero. Raise it in Sidekick to hear this test."
        context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC) == 0 -> "Your phone's media volume is zero. Press volume up to hear this test."
        prefs.teasingStyle == TeasingStyle.SWEET -> "Sweet teasing needs your recordings. Missing situations show text only."
        else -> "Uses your chosen recordings and media volume. Confirm the sound on your phone."
    }
    Text(voiceHint, color = Muted, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onVoiceControls, modifier = Modifier.heightIn(min = 48.dp)) { Text("Adjust voice & reminders") }
    Text("This test skips quiet hours and silent mode. DND media rules still apply. It changes no tasks or saved settings.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    if (!health.allowed) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enable Android notifications") }
    if (!health.alarms) TextButton(onClick = onAlarms, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow alarms & reminders") }
    if (health.restricted || test.phase == TestDeliveryPhase.EXPIRED) TextButton(onClick = onBattery,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow background battery use") }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (expanded) "Hide delivery details" else "Delivery details")
    }
    if (expanded) {
        Text("Your regular reminders", style = MaterialTheme.typography.titleMedium)
        Text(health.status, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(health.voice, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(health.chime, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (report.isEmpty()) Text("No delivery recorded yet. Run the test above.", color = Muted, style = MaterialTheme.typography.bodySmall)
        report.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Text("If delivery stops outside Bite, open App info → Battery and allow background use. On Samsung, also remove Bite from Sleeping or Deep sleeping apps. Force-stopping Bite pauses reminders until you reopen it.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("Reminder cards arrive silently. Recorded voices use Bite's volume slider and your phone's media volume. The test cannot override zero media volume or Android's DND media rules.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onBattery, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open app battery settings") }
        TextButton(onClick = onSettings) { Text("Android notification settings") }
    }
}
