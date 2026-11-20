package com.dracula.taskbite

import android.app.TimePickerDialog
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

@Composable fun SettingsScreen(prefs: Preferences, busy: Boolean, onPermission: () -> Unit, onSystemSettings: () -> Unit,
    onPreview: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onVoiceSettings: () -> Unit, onAlarms: () -> Unit, onScheduleChanged: () -> Unit, onSilence: () -> Unit, onPinWidget: () -> Unit, onTesting: () -> Unit = {}, previewFeedback: String? = null, onUpdates: () -> Unit = {}, onFullExport: () -> Unit = {}) {
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
    var notificationReactions by remember { mutableStateOf(prefs.notificationReactions) }
    var alertVolume by remember { mutableIntStateOf(prefs.alertVolume) }
    var reminders by remember { mutableStateOf(prefs.reminders) }
    var away by remember { mutableStateOf(prefs.awayNudges) }
    var awayGap by remember { mutableIntStateOf(prefs.awayGapMinutes) }
    var gapDialog by rememberSaveable { mutableStateOf(false) }
    var planning by remember { mutableStateOf(prefs.planningNudge) }
    var planningHour by remember { mutableIntStateOf(prefs.planningHour) }
    var planningMinute by remember { mutableIntStateOf(prefs.planningMinute) }
    var teasingStyle by remember { mutableStateOf(prefs.teasingStyle) }
    var quiet by remember { mutableStateOf(prefs.quietEnabled) }
    var start by remember { mutableIntStateOf(prefs.quietStart) }
    var end by remember { mutableIntStateOf(prefs.quietEnd) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Voice & settings", "") }
        item {
            OutlinedButton(onClick = onTesting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.PlayCircleOutline, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("Test & troubleshoot", Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
            }
        }
        item { ReminderOverviewCard(onPauseChanged = onScheduleChanged, onSilence = onSilence, onFix = { fix ->
            when (fix) {
                ReminderFix.ENABLE -> { reminders = true; prefs.reminders = true; onPermission(); onScheduleChanged() }
                ReminderFix.NOTIFICATIONS -> onSystemSettings()
                ReminderFix.ALARMS -> onAlarms()
                ReminderFix.RECORDINGS -> onVoiceSettings()
                ReminderFix.VOICE -> { backgroundVoice = true; prefs.backgroundVoice = true; onPermission(); onScheduleChanged() }
                ReminderFix.VOLUME -> { alertVolume = 55; prefs.alertVolume = 55 }
                ReminderFix.SOUND -> context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS))
                ReminderFix.NONE -> Unit
            }
        }) }
        item { SettingsCard("VOICE") {
            Text("Teasing style", style = MaterialTheme.typography.titleMedium)
            BiteChoiceRow(TeasingStyle.entries.map { it.label }, teasingStyle.ordinal, { index ->
                onSilence(); teasingStyle = TeasingStyle.entries[index]; prefs.teasingStyle = teasingStyle
            })
            if (teasingStyle == TeasingStyle.SWEET) FinePrint("Sweet recordings only. Missing situations stay text-only. Add your voice in Voice settings.")
            SettingSwitch("In-app voice", "React when you add, start, finish, or postpone a task.", actionVoice) {
                actionVoice = it; prefs.actionVoice = it; if (!it) onSilence()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Spoken alert volume", style = MaterialTheme.typography.titleMedium)
                Text(if (alertVolume == 0) "Muted" else "$alertVolume%", color = Lime)
            }
            Slider(value = alertVolume.toFloat(), onValueChange = { value ->
                val percent = value.roundToInt().coerceIn(0, 100)
                if (percent != alertVolume) { alertVolume = percent; prefs.alertVolume = percent; onSilence() }
            }, valueRange = 0f..100f, steps = 19, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Spoken alert volume"
                stateDescription = if (alertVolume == 0) "Muted" else "$alertVolume percent"
            })
            FinePrint("Uses your phone's media volume. 0% mutes every reaction. No separate notification chime.")
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPreview, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp)); Text(" Test voice")
                }
                OutlinedButton(onClick = onVoiceSettings, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)) { Text("Voice settings") }
            }
            previewFeedback?.let { FinePrint(it) }
            MoreDetails("When Bite talks") {
                FinePrint("Different recordings for adding, starting, finishing, and postponing. Overdue tasks also get a reaction while Today is open. After five idle minutes, Bite nudges you again—even if you already started another task.")
                FinePrint("Automatic voice respects silent mode, DND, and quiet hours. A play button previews a reaction with media volume. Choose a situation in Voice settings to hear its actual recording.")
                FinePrint("Outside Bite, deadline reminders, planning, and While I'm away can play matching recordings. A check-in already delivered outside isn't repeated when you return.")
            }
        } }
        item { SettingsCard("REMINDERS") {
            SettingSwitch("Task notifications", "Due-task reminders and gentle nudges.", reminders) {
                reminders = it; prefs.reminders = it
                if (it) onPermission() else {
                    NotificationReactions.clear(context)
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    manager.activeNotifications.filter { notice -> notice.notification.channelId == Reminders.CHANNEL }
                        .forEach { notice -> manager.cancel(notice.tag, notice.id) }
                    onSilence()
                }
                onScheduleChanged()
            }
            SettingSwitch("While I'm away", "Keep nudging when there's no task activity. Match the situation each time.", away) {
                away = it; prefs.awayNudges = it
                if (!it) { Reminders.clearAway(context); onSilence() } else onPermission()
                onScheduleChanged()
            }
            if (away) {
                OutlinedButton(onClick = { gapDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Nudge gap · $awayGap min")
                }
                FinePrint("Repeat every $awayGap minutes while inactive, outside quiet hours. Android may delay delivery.")
                MoreDetails("Away scenarios & limits") {
                    FinePrint("Empty list, one to three tasks, only low-priority tasks, overdue work, repeated postponements, tiny tasks, or leaving without progress: Bite checks again each time. A small list gets encouragement to start, not a demand to add more.")
                    FinePrint("No daily cap or fixed daytime window. The first nudge waits one gap after you leave or act on a task; later nudges use the same gap. Started work gets its estimate plus one gap before a check-in. Deadline reminders take priority.")
                    FinePrint("An empty list can get repeat nudges even with Plan my day off. Completing or clearing all your tasks stops them for today. Task actions reset inactivity. Bite cannot tell whether you're working elsewhere.")
                }
            }
            SettingSwitch("Spoken task reactions", "Play matching recordings for reminders and away nudges. People nearby may hear them.", backgroundVoice) {
                backgroundVoice = it; prefs.backgroundVoice = it; if (!it) onSilence()
                if (it) onPermission()
                onScheduleChanged()
            }
            if (backgroundVoice && !exactAvailable) {
                FinePrint("Allow Alarms & reminders for spoken alerts outside Bite.")
                OutlinedButton(onClick = onAlarms, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow spoken reminder alarms") }
            }
            SettingSwitch("Notification reactions", "React when you open or clear a reminder.", notificationReactions) {
                notificationReactions = it; prefs.notificationReactions = it
                if (!it) onSilence()
                onScheduleChanged()
            }
            if (notificationReactions) MoreDetails("Opening, clearing & leaving reminders") {
                FinePrint("Opening plays a short starting reaction. Swiping away uses Notification cleared recordings and briefly shows their exact words. Clear all reacts once; your tasks stay unchanged.")
                FinePrint("With While I'm away on, an untouched reminder—or opening it without task progress—gets a follow-up at your chosen gap. Bite cannot detect whether you merely read a notification.")
                FinePrint("Quiet hours, silent mode, DND, and your volume still apply to sound. Automatic removal never reacts. Missing Sweet recordings show a short text-only response.")
            }
            SettingSwitch("Plan my day", "One daily nudge when your list is empty. Skips days you've already worked on tasks.", planning) {
                planning = it; prefs.planningNudge = it
                if (!it) { Reminders.clearPlanning(context); onSilence() }
                onScheduleChanged()
            }
            if (planning) {
                OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, minute ->
                    planningHour = hour; planningMinute = minute
                    prefs.planningHour = hour; prefs.planningMinute = minute; onScheduleChanged()
                }, planningHour, planningMinute, true).show() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Planning nudge · %02d:%02d".format(planningHour, planningMinute))
                }
                FinePrint("This planning prompt is once daily. While I'm away controls repeated empty-list nudges separately. Quiet hours apply to both.")
            }
            SettingSwitch("Quiet hours", "Pause automatic voice and reminders.", quiet) {
                quiet = it; prefs.quietEnabled = it; if (it) onSilence(); onScheduleChanged()
            }
            if (quiet) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, _ -> start = hour; prefs.quietStart = hour; onScheduleChanged() }, start, 0, true).show() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("From %02d:00".format(start)) }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, _ -> end = hour; prefs.quietEnd = hour; onScheduleChanged() }, end, 0, true).show() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Until %02d:00".format(end)) }
            }
            if (quiet && start == end) FinePrint("Matching times mean quiet all day.")
            TextButton(onClick = onSystemSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open notification settings") }
        } }
        item { MoreDetails("Widgets & backups") {
            OutlinedButton(onClick = onPinWidget, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Widgets, null, Modifier.size(18.dp)); Text(" Add home-screen widget")
            }
            FinePrint("Check tasks off at home. Add and voice shortcuts open a draft. Android may delay widget refreshes.")
            Button(onClick = onFullExport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back up everything") }
            TextButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Export tasks & history") }
            OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore a backup") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FinePrint("Back up everything includes tasks, history, settings, and your recordings. Choose a private place to save it. Restore keeps newer task edits and saved deletions. The separate JSON export contains tasks and history only.")
        } }
        item { AppUpdateEntry(onUpdates) }
        item {
            Text("Made By Dracula ❤️ · ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

    }
    if (gapDialog) NudgeGapDialog(awayGap, onDismiss = { gapDialog = false }, onSave = {
        awayGap = it; prefs.awayGapMinutes = it; gapDialog = false; onScheduleChanged()
    })
}

@Composable private fun SettingsCard(label: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Eyebrow(label, Lime)
            content()
        }
    }
}

@Composable private fun FinePrint(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
}

/** Collapsible fine print so dense explanations stay out of the way until wanted. */
@Composable private fun MoreDetails(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            Text(" $title", color = Muted)
        }
        if (open) content()
    }
}

@Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title },
            thumbContent = if (checked) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
    }
}
