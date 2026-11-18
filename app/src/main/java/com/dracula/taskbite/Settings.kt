package com.dracula.taskbite

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun SettingsScreen(prefs: Preferences, busy: Boolean, onPermission: () -> Unit, onSystemSettings: () -> Unit,
    onPreview: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onVoiceSettings: () -> Unit, onAlarms: () -> Unit, onScheduleChanged: () -> Unit, onSilence: () -> Unit) {
    val context = LocalContext.current
    var exactAvailable by remember { mutableStateOf(SpokenAlarms.available(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exactAvailable = SpokenAlarms.available(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var actionVoice by remember { mutableStateOf(prefs.actionVoice) }
    var backgroundVoice by remember { mutableStateOf(prefs.backgroundVoice) }
    var reminders by remember { mutableStateOf(prefs.reminders) }
    var quiet by remember { mutableStateOf(prefs.quietEnabled) }
    var start by remember { mutableIntStateOf(prefs.quietStart) }
    var end by remember { mutableIntStateOf(prefs.quietEnd) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { PageHeader("Manage the attitude.", "SIDEKICK") }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BiteMascot()
                    Column(Modifier.weight(1f)) {
                        Text("Meet Bite.", style = MaterialTheme.typography.titleLarge)
                        Text("Sarcastic. On your side. Surprisingly invested in your errands.", color = Muted)
                    }
                }
            }
        }
        item { Eyebrow("VOICE & REMINDERS", Lime) }
        item { SettingSwitch("In-app voice", "Spoken reactions when you add, start, finish, or postpone a task.", actionVoice) {
            actionVoice = it; prefs.actionVoice = it; if (!it) onSilence()
        } }
        item { SettingSwitch("Task notifications", "Due-task reminders and gentle nudges when you leave without progress.", reminders) {
            reminders = it; prefs.reminders = it
            if (it) onPermission() else { androidx.core.app.NotificationManagerCompat.from(context).cancelAll(); onSilence() }
            onScheduleChanged()
        } }
        item { SettingSwitch("Speak pending tasks aloud", "Read the task name and a roast, even outside the app. People nearby may hear it. Uses your current audio output.", backgroundVoice) {
            backgroundVoice = it; prefs.backgroundVoice = it; if (!it) onSilence()
            if (it) onPermission()
            onScheduleChanged()
        } }
        if (backgroundVoice && !exactAvailable) item {
            Text("Allow Alarms & reminders for spoken due-task alerts. Until then, you'll receive notifications without background speech.", color = Coral)
            OutlinedButton(onClick = onAlarms) { Text("Allow spoken reminder alarms") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp)); Text(" Test voice") }
                OutlinedButton(onClick = onVoiceSettings, modifier = Modifier.weight(1f)) { Text("Voice settings") }
            }
            Text("Uses your phone's English text-to-speech voice. Respects silent mode, Do Not Disturb, and volume. Voice testing ignores quiet hours.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        item { HorizontalDivider(color = Panel) }
        item { Eyebrow("A LITTLE PEACE & QUIET", Lime) }
        item { SettingSwitch("Quiet hours", "Pause reminders and automatic voice during these hours.", quiet) { quiet = it; prefs.quietEnabled = it; if (it) onSilence(); onScheduleChanged() } }
        if (quiet) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, _ -> start = hour; prefs.quietStart = hour; onScheduleChanged() }, start, 0, true).show() }, modifier = Modifier.weight(1f)) { Text("From %02d:00".format(start)) }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, _ -> end = hour; prefs.quietEnd = hour; onScheduleChanged() }, end, 0, true).show() }, modifier = Modifier.weight(1f)) { Text("Until %02d:00".format(end)) }
            }
            Text(if (start == end) "Same start and end means quiet all day." else "Times use your phone's timezone, rounded to the hour.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item {
            Text("At most one nudge per 30 minutes, and one per task every four hours. Android can delay reminders in battery-saving mode or while the app is force-stopped. Spoken due reminders need alarm access. App-exit nudges are notification-only.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSystemSettings) { Text("Open notification settings") }
        }
        item { HorizontalDivider(color = Panel) }
        item { Eyebrow("YOUR TASKS. YOUR HISTORY.", Lime) }
        item {
            Text("Updates keep your progress.", style = MaterialTheme.typography.titleLarge)
            Text("Install updates over this app. Don't uninstall or clear app storage. Keep a backup for phone changes, resets, or lost devices.", color = Muted, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FileUpload, null); Text(" Export tasks & history") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FileDownload, null); Text(" Restore a backup") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Backups are readable JSON files; save them somewhere private. Restore merges your history and keeps newer task edits. Voice preferences stay on this phone.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item { HorizontalDivider(color = Panel) }
        item {
            Text("Made By Dracula ❤️", style = MaterialTheme.typography.titleMedium)
            Text("TaskBite ${BuildConfig.VERSION_NAME} · Small steps. Sharp commentary.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Tasks are stored locally. Voice dictation is handled by your chosen speech app and may use its online service. Calendar events are saved by your calendar app.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
