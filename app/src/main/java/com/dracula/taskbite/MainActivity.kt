package com.dracula.taskbite

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    var lastInteraction by mutableLongStateOf(System.currentTimeMillis())
    var foreground by mutableStateOf(false)
    var pendingCount = 0
    var sessionProgress = false
    var taskScreenVisible = false
    private var sessionStart = 0L
    private var skipAbandon = false
    fun externalAction() { skipAbandon = true }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TaskBiteTheme { TaskBiteRoot(this) } }
    }
    override fun onStart() {
        super.onStart()
        (application as TaskBiteApp).foreground = true
        foreground = true
        sessionStart = System.currentTimeMillis()
        lastInteraction = sessionStart
        sessionProgress = false
        skipAbandon = false
        Reminders.cancelAbandon(this)
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Reminders.schedule(this@MainActivity, (application as TaskBiteApp).repository) }
    }
    override fun onStop() {
        super.onStop()
        (application as TaskBiteApp).foreground = false
        foreground = false
        if (!isChangingConfigurations && !skipAbandon && taskScreenVisible && pendingCount > 0 &&
            !sessionProgress && System.currentTimeMillis() - sessionStart >= 10_000) Reminders.abandon(this)
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = System.currentTimeMillis()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskBiteRoot(activity: MainActivity) {
    val app = activity.application as TaskBiteApp
    val repository = app.repository
    val prefs = app.preferences
    val tasks by repository.dao.watchTasks().collectAsState(initial = emptyList())
    val events by repository.dao.watchEvents().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val voice = remember { Voice(activity) }
    var onboarded by rememberSaveable { mutableStateOf(prefs.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var spokenTitle by rememberSaveable { mutableStateOf("") }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var bite by rememberSaveable { mutableStateOf("Big plans. Small steps. Mild judgment.") }
    var savingDraft by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf<String?>(null) }
    var restoreCount by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = tasks.filter { !it.archived }
    SideEffect {
        activity.pendingCount = active.count { it.completedAt == null }
        activity.taskScreenVisible = onboarded && tab == 0 && !editorOpen
    }
    LaunchedEffect(activity.foreground) {
        if (activity.foreground) while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    DisposableEffect(activity, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) voice.stop() }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); voice.close() }
    }
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    fun roast(kind: RoastKind) {
        val line = Roasts.pick(kind)
        bite = line
        if (prefs.actionVoice && !prefs.isQuiet() && activity.foreground) scope.launch { voice.speak(line) }
    }
    fun mutate(id: String? = null, kind: RoastKind? = null, message: String? = null, block: suspend () -> Boolean) {
        scope.launch {
            try {
                if (block()) {
                    if (id != null) Reminders.clear(activity, id)
                    if (kind != null) roast(kind)
                    if (message != null) notice(message)
                    Reminders.schedule(activity, repository)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Couldn't save that change. Please try again.") }
        }
    }
    LaunchedEffect(activity.lastInteraction, activity.foreground, tab, editorOpen, onboarded) {
        if (activity.foreground && onboarded && tab == 0 && !editorOpen) {
            delay(5 * 60 * 1000L)
            val pending = repository.dao.pending()
            if (pending.isNotEmpty() && !activity.sessionProgress) {
                roast(if (pending.any { it.minutes < 5 }) RoastKind.TINY else RoastKind.IDLE)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice("Notifications are off. You can enable them in Settings.")
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val recognition = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!heard.isNullOrBlank()) {
                spokenTitle = heard.take(200)
                editingId = null
                editorOpen = true
            } else notice("Didn't catch that. Try again or type your task.")
        }
    }
    fun dictate() {
        voice.stop()
        activity.externalAction()
        try {
            recognition.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "What's the mission?")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: ActivityNotFoundException) { notice("No speech recognizer installed. Type your task instead.") }
    }
    fun calendar(task: Task) {
        activity.externalAction()
        try {
            activity.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, task.title)
                putExtra(CalendarContract.Events.DESCRIPTION, task.notes)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, task.dueAt)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, task.dueAt + task.minutes * 60_000L)
            })
        } catch (_: ActivityNotFoundException) { notice("No calendar app found. Your task is still saved here.") }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            try {
                withContext(Dispatchers.IO) {
                    val json = Backup.export(repository)
                    require(json.toByteArray().size <= Backup.MAX_BYTES) { "Backup too large" }
                    activity.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                        ?: error("Cannot write document")
                }
                notice("Backup saved. Tasks and history, safely packed.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Backup couldn't be saved. Choose another location and retry.") }
            finally { backupBusy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            try {
                val text = withContext(Dispatchers.IO) {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { Backup.readLimited(it) }
                        ?: error("Cannot read document")
                    require(bytes.size <= Backup.MAX_BYTES)
                    bytes.toString(Charsets.UTF_8).let { it to Backup.decode(it).tasks.size }
                }
                restoreText = text.first
                restoreCount = text.second
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Invalid backup. Choose a TaskBite JSON backup under 10 MB.") }
            finally { backupBusy = false }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (onboarded) NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
            val labels = listOf("Today", "Calendar", "History", "Sidekick")
            val icons = listOf(Icons.Rounded.CheckCircleOutline, Icons.Rounded.CalendarMonth, Icons.Rounded.History, Icons.Rounded.Tune)
            labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Icon(icons[index], contentDescription = label) }, label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, indicatorColor = Panel)) }
        } },
        floatingActionButton = { if (onboarded && tab in 0..1 && !editorOpen) {
            ExtendedFloatingActionButton(onClick = { editingId = null; spokenTitle = ""; editorOpen = true },
                icon = { Icon(Icons.Rounded.Add, "Add task") }, text = { Text("New task") }, containerColor = Lime, contentColor = Ink)
        } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!onboarded) Onboarding(onContinue = { speak ->
                prefs.actionVoice = speak
                prefs.onboarded = true
                onboarded = true
                requestNotifications()
            }) else when (tab) {
                0 -> TodayScreen(active, bite, now, onVoice = ::dictate,
                    onAdd = { editingId = null; spokenTitle = ""; editorOpen = true },
                    onComplete = { task ->
                        activity.sessionProgress = true
                        mutate(task.id, if (task.completedAt == null) RoastKind.COMPLETED else null,
                            if (task.completedAt != null) "Task reopened." else null) {
                            repository.complete(task.id, task.completedAt == null)
                        }
                    },
                    onStart = { task -> activity.sessionProgress = true; mutate(task.id, RoastKind.STARTED) { repository.start(task.id) } },
                    onSnooze = { task -> mutate(task.id, RoastKind.SNOOZE) { repository.snooze(task.id) } },
                    onEdit = { task -> editingId = task.id; editorOpen = true },
                    onArchive = { task -> mutate(task.id, message = "Archived. Restore it from History anytime.") { repository.archive(task.id, true) } },
                    onCalendar = ::calendar)
                1 -> CalendarScreen(active, LocalDate.parse(selectedDate), now,
                    onDate = { selectedDate = it.toString() },
                    onEdit = { editingId = it.id; editorOpen = true }, onCalendar = ::calendar)
                2 -> HistoryScreen(tasks, events, onRestore = { task ->
                    mutate(task.id, message = "Task restored.") { repository.archive(task.id, false) }
                }, onReopen = { task -> mutate(task.id, message = "Task reopened.") { repository.complete(task.id, false) } })
                3 -> SettingsScreen(prefs, backupBusy,
                    onPermission = { requestNotifications() },
                    onSystemSettings = {
                        activity.externalAction()
                        activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
                    },
                    onPreview = { scope.launch {
                        val line = Roasts.pick(RoastKind.COMPLETED); bite = line
                        if (!voice.speak(line)) notice("Voice unavailable. Check your TTS engine, sound mode, and volume.")
                    } },
                    onExport = { activity.externalAction(); export.launch("TaskBite-${LocalDate.now()}.json") },
                    onImport = { activity.externalAction(); import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    onVoiceSettings = {
                        activity.externalAction()
                        try { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        catch (_: ActivityNotFoundException) { notice("Open your phone's text-to-speech settings to install a voice.") }
                    },
                    onAlarms = {
                        activity.externalAction()
                        if (Build.VERSION.SDK_INT >= 31) try {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:${activity.packageName}")))
                        } catch (_: ActivityNotFoundException) { notice("Open Alarms & reminders in your phone settings.") }
                    },
                    onScheduleChanged = { scope.launch { Reminders.schedule(activity, repository) } },
                    onSilence = { voice.stop(); activity.stopService(Intent(activity, SpokenReminderService::class.java)) })
            }
        }
    }
    if (editorOpen && (editingId == null || tasks.any { it.id == editingId })) {
        val existing = tasks.find { it.id == editingId }
        TaskEditor(existing, spokenTitle, if (tab == 1) LocalDate.parse(selectedDate) else LocalDate.now(),
            saving = savingDraft,
            onDismiss = { if (!savingDraft) editorOpen = false },
            onSave = { draft ->
                if (!savingDraft) {
                savingDraft = true
                scope.launch {
                    try {
                        if (repository.save(draft)) {
                            editorOpen = false
                            spokenTitle = ""
                            if (existing == null) roast(RoastKind.CREATED) else notice("Task updated.")
                            Reminders.clear(activity, draft.id)
                            Reminders.schedule(activity, repository)
                        } else notice("This task was archived. Restore it from History first.")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Couldn't save your task. Your draft is still here.") }
                    finally { savingDraft = false }
                }
                }
            })
    }
    if (restoreText != null) AlertDialog(onDismissRequest = { if (!backupBusy) restoreText = null },
        title = { Text("Restore your backup?") },
        text = { Text("Merge $restoreCount tasks and their history. Newer tasks on this phone stay unchanged. Nothing is deleted.") },
        confirmButton = { TextButton(enabled = !backupBusy, onClick = {
            val text = restoreText ?: return@TextButton
            backupBusy = true
            scope.launch {
                try {
                    val count = withContext(Dispatchers.IO) { Backup.restore(repository, text) }
                    restoreText = null
                    notice("Restored $count tasks. History merged.")
                    Reminders.schedule(activity, repository)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice("Restore failed. Existing tasks haven't been removed.") }
                finally { backupBusy = false }
            }
        }) { Text(if (backupBusy) "Restoring…" else "Restore") } },
        dismissButton = { TextButton(enabled = !backupBusy, onClick = { restoreText = null }) { Text("Cancel") } })
}
