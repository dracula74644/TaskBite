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
    var widgetRequest by mutableStateOf<WidgetRequest?>(null)
    var noticeRequest by mutableStateOf<String?>(null)
    var updateRequest by mutableStateOf(false)
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
        updateRequest = savedInstanceState?.getBoolean("pending_update") ?: (intent.action == AppUpdates.OPEN)
        noticeRequest = if (savedInstanceState == null) intent.getStringExtra(NotificationReactions.TOKEN)
            else savedInstanceState.getString("pending_notice")
        widgetRequest = if (savedInstanceState == null) WidgetNavigation.parse(intent) else {
            savedInstanceState.getString("pending_widget_action")?.let { action ->
                WidgetNavigation.parse(Intent().setAction(action)
                    .putExtra(WidgetNavigation.TASK_ID, savedInstanceState.getString("pending_widget_task")))
            }
        }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge()
        setContent { TaskBiteTheme { TaskBiteRoot(this) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateRequest = intent.action == AppUpdates.OPEN
        widgetRequest = WidgetNavigation.parse(intent)
        noticeRequest = intent.getStringExtra(NotificationReactions.TOKEN)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_notice", noticeRequest)
        outState.putBoolean("pending_update", updateRequest)
        widgetRequest?.let {
            outState.putString("pending_widget_action", it.action)
            outState.putString("pending_widget_task", it.taskId)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        (application as TaskBiteApp).foreground = true
        (application as TaskBiteApp).preferences.backgroundSince = 0
        foreground = true
        sessionStart = System.currentTimeMillis()
        lastInteraction = sessionStart
        sessionProgress = false
        skipAbandon = false
        Reminders.cancelAbandon(this)
        if (noticeRequest == null) Reminders.clearAway(this)
    }
    override fun onResume() {
        super.onResume()
        TaskWidgets.ensurePeriodic(this)
        TaskWidgets.requestRefresh(this)
        lifecycleScope.launch { Reminders.schedule(this@MainActivity, (application as TaskBiteApp).repository) }
    }
    override fun onStop() {
        super.onStop()
        val app = application as TaskBiteApp
        app.foreground = false
        foreground = false
        // External pickers/settings aren't abandonment, but the normal inactivity gap
        // must still run if the user goes Home from there instead of returning to Bite.
        if (!isChangingConfigurations) app.preferences.backgroundSince = System.currentTimeMillis()
        if (!isChangingConfigurations && !skipAbandon && taskScreenVisible && pendingCount > 0 &&
            !sessionProgress && app.preferences.lastTaskActivity < sessionStart && System.currentTimeMillis() - sessionStart >= 10_000) {
            (application as TaskBiteApp).preferences.abandonedAt = System.currentTimeMillis()
            Reminders.abandon(this)
        }
        if (!isChangingConfigurations) app.backgroundScope.launch { Reminders.schedule(app, app.repository) }
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
    var library by remember { mutableStateOf(VoiceLibrary(activity)) }
    var voiceSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var simulationOpen by rememberSaveable { mutableStateOf(false) }
    var testingOpen by rememberSaveable { mutableStateOf(false) }
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var onboarded by rememberSaveable { mutableStateOf(prefs.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorSession by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var spokenTitle by rememberSaveable { mutableStateOf("") }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var savedReaction by rememberSaveable { mutableStateOf<String?>(null) }
    val lastReaction = remember(savedReaction) { VoiceReaction.restore(savedReaction) }
    val bite = lastReaction?.caption ?: "One task at a time. I'll keep you company."
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var previewFeedback by remember { mutableStateOf<String?>(null) }
    var testAfterPermission by rememberSaveable { mutableStateOf(false) }
    var lastVoiceNotice by remember { mutableStateOf<String?>(null) }
    var reactionJob by remember { mutableStateOf<Job?>(null) }
    var savingDraft by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var restoreText by remember { mutableStateOf<String?>(null) }
    var restorePackage by remember { mutableStateOf<FullBackup.Package?>(null) }
    var restoreSettings by remember { mutableStateOf(true) }
    var settingsRevision by remember { mutableIntStateOf(0) }
    var restoreCount by remember { mutableIntStateOf(0) }
    var restoreDeletions by remember { mutableIntStateOf(0) }
    var restoreDeletionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<Task?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var undoToken by remember { mutableStateOf<TaskUndo?>(null) }
    var undoBusy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = tasks.filter { !it.archived }
    SideEffect {
        activity.pendingCount = active.count { it.completedAt == null }
        activity.taskScreenVisible = onboarded && tab == 0 && !editorOpen && !voiceSettingsOpen && !simulationOpen && !testingOpen && !updatesOpen
    }
    LaunchedEffect(activity.foreground) {
        if (activity.foreground) while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    DisposableEffect(activity, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { reactionJob?.cancel(); voice.stop() } }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer); voice.close() }
    }
    fun notice(message: String) { scope.launch { snackbar.showSnackbar(message) } }
    LaunchedEffect(undoToken) {
        val token = undoToken
        if (token != null) { delay(10_000); if (undoToken === token) undoToken = null }
    }
    fun roast(kind: RoastKind) {
        if (prefs.teasingPaused()) return
        val reaction = library.reaction(kind)
        savedReaction = reaction.saved()
        prefs.lastForegroundReaction = System.currentTimeMillis()
        voiceStatus = reaction.explanation
        reactionJob?.cancel()
        if (activity.foreground) reactionJob = scope.launch {
            val result = voice.react(reaction)
            voiceStatus = if (result == VoiceResult.NO_RECORDING) reaction.explanation else result.message ?: reaction.explanation
            val expectedSilence = result in setOf(VoiceResult.NO_RECORDING, VoiceResult.VOICE_DISABLED, VoiceResult.QUIET_HOURS,
                VoiceResult.APP_MUTED, VoiceResult.MEDIA_MUTED, VoiceResult.SILENT_MODE, VoiceResult.DND_BLOCKED)
            if (!expectedSilence && result.message != null && result.message != lastVoiceNotice) notice(result.message)
            lastVoiceNotice = result.message
        }
    }
    fun mutate(id: String? = null, kind: RoastKind? = null, message: String? = null, block: suspend () -> Boolean) {
        scope.launch {
            try {
                if (block()) {
                    if (kind != null) roast(kind)
                    if (message != null) notice(message)
                    try {
                        if (id != null) Reminders.clear(activity, id)
                        Reminders.schedule(activity, repository)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Change saved. Reopen the app to refresh reminders.") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: TaskChangeRejected) { notice(e.message ?: "This task has changed.") }
            catch (_: Exception) { notice("Couldn't save that change. Please try again.") }
        }
    }
    fun undoable(task: Task, action: TaskAction, kind: RoastKind, laterAt: Long? = null) {
        scope.launch {
            try {
                val token = repository.actWithUndo(task.id, action, laterAt) ?: return@launch
                undoToken = token
                roast(Roasts.forAction(kind, token.before))
                try { Reminders.clear(activity, task.id); Reminders.schedule(activity, repository) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice("Change saved. Reopen the app to refresh reminders.") }
            } catch (e: CancellationException) { throw e }
            catch (e: TaskChangeRejected) { notice(e.message ?: "This task has changed.") }
            catch (_: Exception) { notice("Couldn't save that change. Please try again.") }
        }
    }
    LaunchedEffect(activity.lastInteraction, activity.foreground, tab, editorOpen, voiceSettingsOpen, onboarded, simulationOpen, testingOpen, updatesOpen) {
        if (activity.foreground && onboarded && tab == 0 && !editorOpen && !voiceSettingsOpen && !simulationOpen && !testingOpen && !updatesOpen) {
            delay(InAppReactionPolicy.IDLE_DELAY)
            val pending = repository.dao.pending()
            if (InAppReactionPolicy.canNudge(System.currentTimeMillis(), prefs.lastForegroundReaction)) {
                InAppReactionPolicy.idleKind(pending, System.currentTimeMillis())?.let(::roast)
            }
        }
    }
    LaunchedEffect(activity.foreground, onboarded, tab, editorOpen, voiceSettingsOpen, simulationOpen, testingOpen, updatesOpen) {
        if (activity.foreground && onboarded && tab == 0 && !editorOpen && !voiceSettingsOpen && !simulationOpen && !testingOpen && !updatesOpen) {
            delay(1_500)
            while (true) {
                val time = System.currentTimeMillis()
                val plan = ReminderDelivery.claimPlanningInApp(app, time)
                if (plan != null) {
                    roast(plan)
                    Reminders.clearPlanning(activity)
                    try { Reminders.schedule(activity, repository) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Reopen the app to refresh reminders.") }
                } else if (voice.reactionBlockReason() == null && InAppReactionPolicy.canNudge(time, prefs.lastForegroundReaction)) {
                    val abandoned = prefs.abandonedAt
                    prefs.abandonedAt = 0
                    if (abandoned > prefs.handledAwayAbandon && time - abandoned in 0..86_400_000 &&
                        InAppReactionPolicy.canNudge(time, prefs.lastAwayNudge) && repository.dao.pending().isNotEmpty()) {
                        roast(RoastKind.ABANDON)
                    } else ReminderDelivery.claimInApp(app, time)?.let { kind ->
                        if (InAppReactionPolicy.canNudge(System.currentTimeMillis(), prefs.lastForegroundReaction)) roast(kind)
                    }
                }
                delay(30_000)
            }
        }
    }
    fun testNotification() { scope.launch {
        if (NotificationTest.schedule(app)) notice("Test queued. Press Home and wait about 10 seconds.")
        else notice("Enable Bite notifications and the Task reminders channel in phone settings.")
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice("Notifications are off. You can enable them in Settings.")
        else {
            scope.launch { Reminders.schedule(activity, repository) }
            if (testAfterPermission) testNotification()
        }
        testAfterPermission = false
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    fun openNotificationSettings() {
        activity.externalAction()
        activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
    }
    fun openBatterySettings() {
        activity.externalAction()
        activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${activity.packageName}")))
    }
    fun openAlarmSettings() {
        activity.externalAction()
        if (Build.VERSION.SDK_INT >= 31) try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:${activity.packageName}")))
        } catch (_: ActivityNotFoundException) { notice("Open Alarms & reminders in your phone settings.") }
    }
    fun requestNotificationTest() {
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            testAfterPermission = true; requestNotifications()
        } else if (!Reminders.allowed(activity)) openNotificationSettings()
        else testNotification()
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
        } catch (_: ActivityNotFoundException) {
            editingId = null
            spokenTitle = ""
            editorOpen = true
            notice("No speech recognizer installed. Type your task instead.")
        }
    }
    LaunchedEffect(activity.widgetRequest, onboarded, activity.foreground) {
        val request = activity.widgetRequest
        if (request != null && onboarded && activity.foreground) {
            simulationOpen = false
            testingOpen = false
            updatesOpen = false
            voiceSettingsOpen = false
            reactionJob?.cancel(); voice.stop()
            val editingTask = if (request.action == WidgetNavigation.EDIT) repository.dao.task(request.taskId ?: "") else null
            activity.widgetRequest = null // Consume once, before a permission/recognition activity opens.
            tab = 0
            when (request.action) {
                WidgetNavigation.ADD -> {
                    editorSession = request.token
                    editingId = null
                    spokenTitle = ""
                    editorOpen = true
                }
                WidgetNavigation.VOICE -> {
                    editorSession = request.token
                    editorOpen = false
                    dictate()
                }
                WidgetNavigation.EDIT -> {
                    // The list may have changed since the launcher last rendered the widget.
                    val task = editingTask
                    if (task != null && !task.archived) {
                        editorSession = request.token
                        editingId = task.id
                        editorOpen = true
                    } else notice("That task is no longer available. Refresh the widget.")
                }
            }
        }
    }
    LaunchedEffect(activity.noticeRequest, activity.widgetRequest, onboarded, activity.foreground) {
        val token = activity.noticeRequest
        if (token != null && activity.widgetRequest == null && onboarded && activity.foreground) {
            activity.noticeRequest = null
            val entry = NotificationReactions.find(app, token)
            simulationOpen = false; testingOpen = false; updatesOpen = false; voiceSettingsOpen = false; tab = 0
            reactionJob?.cancel(); voice.stop()
            activity.stopService(Intent(activity, SpokenReminderService::class.java))
            // Consumption is persisted before playback, so rotation cannot replay a tap.
            reactionJob = scope.launch {
                val reaction = NotificationReactions.opened(app, token) ?: return@launch
                savedReaction = reaction.saved()
                prefs.lastForegroundReaction = System.currentTimeMillis()
                val result = if (entry?.test != true && prefs.isQuiet()) VoiceResult.QUIET_HOURS
                    else voice.play(reaction, preview = entry?.test == true)
                voiceStatus = result.message ?: reaction.explanation
                DeliveryStatus.audio(app, if (result == VoiceResult.PLAYED) "Notification-open recording played"
                    else voiceStatus ?: result.name)
            }
        }
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
    val exportFull = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            try {
                withContext(Dispatchers.IO) {
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { FullBackup.export(app, repository, it) }
                        ?: error("Cannot write document")
                }
                notice("Complete backup saved, including settings and your recordings.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice("Backup couldn't be saved. Choose another location and retry.") }
            finally { backupBusy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            try {
                val preview = withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                        input.mark(4)
                        val zipped = input.read() == 0x50 && input.read() == 0x4b
                        input.reset()
                        if (zipped) FullBackup.read(app, input).let { it.tasks to it }
                        else Backup.readLimited(input).toString(Charsets.UTF_8).also { Backup.decode(it) } to null
                    } ?: error("Cannot read document")
                }
                val decoded = preview.second?.contents ?: Backup.decode(preview.first)
                restorePackage = preview.second; restoreSettings = true
                restoreText = preview.first
                restoreCount = decoded.tasks.size
                restoreDeletionIds = decoded.deletions.map { it.id }
                restoreDeletions = decoded.deletions.size
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(e.message?.take(180) ?: "Choose a valid Bite complete backup or task JSON export.") }
            finally { backupBusy = false }
        }
    }
    LaunchedEffect(activity.updateRequest, onboarded, editorOpen) {
        if (activity.updateRequest && onboarded) {
            if (editorOpen) notice("Finish or close your task draft, then Bite will show the update.")
            else {
                activity.updateRequest = false
                simulationOpen = false; testingOpen = false; voiceSettingsOpen = false
                reactionJob?.cancel(); voice.stop(); tab = 3; updatesOpen = true
            }
        }
    }
    if (simulationOpen) {
        DaySimulationScreen(onClose = { simulationOpen = false })
        return
    }
    if (updatesOpen) {
        UpdatesScreen(activity, onClose = { updatesOpen = false })
        return
    }
    if (testingOpen) {
        TestingScreen(onClose = { testingOpen = false },
            onSimulate = { reactionJob?.cancel(); voice.stop(); simulationOpen = true },
            onTest = ::requestNotificationTest, onSettings = ::openNotificationSettings,
            onBattery = ::openBatterySettings, onAlarms = ::openAlarmSettings, snackbar = snackbar)
        return
    }
    Scaffold(
        snackbarHost = { Column {
            undoToken?.let { token -> Snackbar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                containerColor = PanelRaised, contentColor = Cream,
                action = { TextButton(enabled = !undoBusy, onClick = {
                    if (!undoBusy) {
                        undoBusy = true
                        scope.launch {
                            try {
                                if (repository.undo(token)) {
                                    reactionJob?.cancel(); voice.stop(); savedReaction = null; voiceStatus = null
                                    token.affectedIds.forEach { Reminders.clear(activity, it) }
                                    Reminders.schedule(activity, repository)
                                } else notice("Can't undo: the task changed or the Undo window ended.")
                                if (undoToken === token) undoToken = null
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { notice("Undo couldn't finish. Check the task before trying again.") }
                            finally { undoBusy = false }
                        }
                    }
                }) { Text("Undo", color = Lime) } },
                dismissAction = { IconButton(onClick = { if (!undoBusy) undoToken = null }) { Icon(Icons.Rounded.Close, "Dismiss Undo") } },
            ) { Text(when (token.action) {
                TaskAction.COMPLETE -> "Task completed"
                TaskAction.SNOOZE -> "Moved to tomorrow"
                TaskAction.LATER -> "Moved to later today"
                TaskAction.DELETE -> "Task deleted"
            }) } }
            SnackbarHost(snackbar)
        } },
        bottomBar = { if (onboarded) Column {
            if (tab in 0..1 && !editorOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End) {
                ExtendedFloatingActionButton(
                    onClick = { editingId = null; spokenTitle = ""; editorOpen = true },
                    text = { Text("New task", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    shape = MaterialTheme.shapes.large, containerColor = Lime, contentColor = Ink,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
                )
            }
            NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                val labels = listOf("Today", "Calendar", "History", "Sidekick")
                val icons = listOf(Icons.Rounded.CheckCircleOutline, Icons.Rounded.CalendarMonth, Icons.Rounded.History, Icons.Rounded.Tune)
                labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(icons[index], contentDescription = label) },
                    label = if (androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.5f)
                        ({ Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) else null,
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime,
                        unselectedIconColor = Muted, unselectedTextColor = Muted, indicatorColor = PrimaryContainer)) }
            }
        } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!onboarded) Onboarding(onContinue = { speak ->
                prefs.actionVoice = speak
                prefs.onboarded = true
                onboarded = true
                TaskWidgets.requestRefresh(activity)
                if (activity.widgetRequest == null) requestNotifications()
            }) else when (tab) {
                0 -> TodayScreen(active, bite, now, voiceStatus = voiceStatus ?: lastReaction?.explanation, onReplay = lastReaction?.takeIf { it.clip != null }?.let { reaction -> {
                    reactionJob?.cancel()
                    reactionJob = scope.launch { voiceStatus = voice.play(reaction, preview = true).message ?: reaction.explanation }
                } }, onVoice = ::dictate,
                    onAdd = { editingId = null; spokenTitle = ""; editorOpen = true },
                    onComplete = { task ->
                        activity.sessionProgress = true
                        if (task.completedAt == null) undoable(task, TaskAction.COMPLETE, RoastKind.COMPLETED)
                        else mutate(task.id, message = "Task reopened.") { repository.complete(task.id, false) }
                    },
                    onStart = { task -> activity.sessionProgress = true; mutate(task.id, RoastKind.STARTED) { repository.start(task.id) } },
                    onSnooze = { task -> undoable(task, TaskAction.SNOOZE, RoastKind.SNOOZE) },
                    onLater = { task ->
                        val clock = LocalTime.now()
                        val initial = if (clock.hour == 23) LocalTime.of(23, 59) else clock.plusHours(1)
                        android.app.TimePickerDialog(activity, { _, hour, minute ->
                            try {
                                val at = LaterToday.at(LocalTime.of(hour, minute), System.currentTimeMillis())
                                undoable(task, TaskAction.LATER, RoastKind.SNOOZE, at)
                            } catch (e: IllegalArgumentException) { notice(e.message ?: "Choose a later time today.") }
                        }, initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(activity)).show()
                    },
                    onCheckItem = { task, item, checked -> mutate(task.id) { repository.checkItem(task.id, item, checked) } },
                    onEdit = { task -> editingId = task.id; editorOpen = true },
                    onArchive = { task -> mutate(task.id, message = "Archived. Restore it from History anytime.") { repository.archive(task.id, true) } },
                    onDelete = { task -> deleteTarget = task },
                    onCalendar = ::calendar)
                1 -> CalendarScreen(active, LocalDate.parse(selectedDate), now,
                    onDate = { selectedDate = it.toString() },
                    onEdit = { editingId = it.id; editorOpen = true }, onCalendar = ::calendar)
                2 -> HistoryScreen(tasks, events, onRestore = { task ->
                    mutate(task.id, message = "Task restored.") { repository.archive(task.id, false) }
                }, onReopen = { task -> mutate(task.id, message = "Task reopened.") { repository.complete(task.id, false) } },
                onDelete = { task -> deleteTarget = task })
                3 -> key(settingsRevision) { SettingsScreen(prefs, backupBusy,
                    previewFeedback = previewFeedback,
                    onUpdates = { reactionJob?.cancel(); voice.stop(); updatesOpen = true },
                    onTesting = { reactionJob?.cancel(); voice.stop(); testingOpen = true },
                    onPinWidget = {
                        val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                        if (manager.isRequestPinAppWidgetSupported) {
                            activity.externalAction()
                            val requested = manager.requestPinAppWidget(
                                android.content.ComponentName(activity, TaskBiteWidgetReceiver::class.java), null, null)
                            if (!requested) notice("Long-press your home screen, choose Widgets, then TaskBite.")
                        } else notice("Long-press your home screen, choose Widgets, then TaskBite.")
                    },
                    onPermission = { requestNotifications() },
                    onSystemSettings = ::openNotificationSettings,
                    onPreview = { reactionJob?.cancel(); reactionJob = scope.launch {
                        if (prefs.alertVolume == 0) previewFeedback = "Spoken alerts are muted. Raise the volume slider to test the voice."
                        else {
                            val reaction = library.reaction(RoastKind.COMPLETED)
                            savedReaction = reaction.saved()
                            previewFeedback = reaction.caption
                            val result = voice.play(reaction, preview = true)
                            voiceStatus = if (result == VoiceResult.NO_RECORDING) reaction.explanation else result.message ?: reaction.explanation
                            previewFeedback = listOfNotNull(reaction.caption, voiceStatus).joinToString("\n")
                        }
                    } },
                    onExport = { activity.externalAction(); export.launch("TaskBite-${LocalDate.now()}.json") },
                    onFullExport = { activity.externalAction(); exportFull.launch("TaskBite-${LocalDate.now()}.bitebackup") },
                    onImport = { activity.externalAction(); import.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream")) },
                    onVoiceSettings = {
                        reactionJob?.cancel()
                        voice.stop()
                        activity.stopService(Intent(activity, SpokenReminderService::class.java))
                        voiceSettingsOpen = true
                    },
                    onAlarms = ::openAlarmSettings,
                    onScheduleChanged = { scope.launch { Reminders.schedule(activity, repository) } },
                    onSilence = { reactionJob?.cancel(); voice.stop(); savedReaction = null; voiceStatus = null; previewFeedback = null
                        ReminderAudio.clear(activity)
                        activity.stopService(Intent(activity, SpokenReminderService::class.java)) }) }
            }
        }
    }
    if (editorOpen && (editingId == null || tasks.any { it.id == editingId })) {
        val existing = tasks.find { it.id == editingId }
        key(editorSession, editingId) {
        TaskEditor(existing, spokenTitle, if (tab == 1) LocalDate.parse(selectedDate) else LocalDate.now(),
            saving = savingDraft,
            onDismiss = { if (!savingDraft) editorOpen = false },
            onSave = { draft ->
                if (!savingDraft) {
                savingDraft = true
                scope.launch {
                    try {
                        if (repository.save(draft, expectedRevision = if (existing != null) draft.updatedAt else null)) {
                            editorOpen = false
                            spokenTitle = ""
                            if (existing == null) roast(RoastKind.CREATED) else notice("Task updated.")
                            Reminders.clear(activity, draft.id)
                            Reminders.schedule(activity, repository)
                        } else notice("This task was deleted or archived. Check History before editing it.")
                    } catch (e: CancellationException) { throw e }
                    catch (e: TaskChangeRejected) { notice(e.message ?: "Task changed. Close and reopen the editor.") }
                    catch (_: Exception) { notice("Couldn't save your task. Your draft is still here.") }
                    finally { savingDraft = false }
                }
                }
            },
            onDelete = { task -> deleteTarget = task })
        }
    }
    if (voiceSettingsOpen) VoiceSettings(onDismiss = { voiceSettingsOpen = false; savedReaction = null; voiceStatus = null })
    deleteTarget?.let { task -> AlertDialog(onDismissRequest = { if (!deleting) deleteTarget = null },
        title = { Text("Delete \"${task.title}\"?") },
        text = { Text("Removes the task and its history. Undo is available for 10 seconds. After that, older backups won't bring it back." +
            if (task.completedAt == null && task.repeatRule != RepeatRule.NONE.name) " Future occurrences stop too." else "") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            if (deleting) return@TextButton
            deleting = true
            scope.launch {
                try {
                    val token = repository.actWithUndo(task.id, TaskAction.DELETE)
                    val removed = token != null
                    if (token != null) undoToken = token
                    if (editingId == task.id) { editorOpen = false; editingId = null }
                    deleteTarget = null
                    try {
                        Reminders.clear(activity, task.id)
                        Reminders.schedule(activity, repository)
                        if (!removed) notice("That task was already removed.")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Task deleted. Reminder refresh failed; reopen the app to retry.") }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice("Couldn't delete the task. Please try again.") }
                finally { deleting = false }
            }
        }) { Text(if (deleting) "Deleting…" else "Delete task", color = Coral) } },
        dismissButton = { TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("Keep task") } })}
    if (restoreText != null) AlertDialog(onDismissRequest = { if (!backupBusy) { restoreText = null; restorePackage = null } },
        title = { Text("Restore your backup?") },
        text = { Column {
            Text("Merge $restoreCount tasks and their history, keeping newer edits. Previously deleted tasks stay deleted." +
                if (restoreDeletions > 0) " Apply $restoreDeletions saved deletion${if (restoreDeletions == 1) "" else "s"} — tasks you deleted stay deleted." else "")
            restorePackage?.let { bundle ->
                Text("Merge ${bundle.recordings.size} recordings. Existing recordings are kept; duplicates are skipped.")
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(restoreSettings, { restoreSettings = it }, enabled = !backupBusy)
                    Text("Replace settings with this backup")
                }
            }
        } },
        confirmButton = { TextButton(enabled = !backupBusy, onClick = {
            if (backupBusy) return@TextButton
            val text = restoreText ?: return@TextButton
            val deletedIds = restoreDeletionIds
            val bundle = restorePackage
            val includeSettings = restoreSettings
            backupBusy = true
            scope.launch {
                try {
                    reactionJob?.cancel(); voice.stop(); ReminderAudio.clear(activity)
                    activity.stopService(Intent(activity, SpokenReminderService::class.java))
                    val count = withContext(Dispatchers.IO) {
                        if (bundle == null) Backup.restore(repository, text) else FullBackup.restore(app, bundle, includeSettings)
                    }
                    restoreText = null; restorePackage = null; settingsRevision++
                    savedReaction = null; voiceStatus = null; library = VoiceLibrary(activity); AppUpdates.start(app)
                    try {
                        deletedIds.forEach { Reminders.clear(activity, it) }
                        Reminders.schedule(activity, repository)
                        notice("Restored $count tasks. History merged.")
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { notice("Backup restored. Reminder refresh failed; reopen the app to retry.") }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { notice(e.message?.take(180) ?: "Restore could not finish. Your existing data is kept.") }
                finally { backupBusy = false }
            }
        }) { Text(if (backupBusy) "Restoring…" else "Restore") } },
        dismissButton = { TextButton(enabled = !backupBusy, onClick = { restoreText = null; restorePackage = null }) { Text("Cancel") } })
}
