package com.dracula.taskbite

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun DaySimulationScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val voice = remember { Voice(context) }
    val library = remember { VoiceLibrary(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dateText = rememberSaveable { LocalDate.now().toString() }
    val zoneText = rememberSaveable { ZoneId.systemDefault().id }
    val date = LocalDate.parse(dateText)
    val zone = ZoneId.of(zoneText)
    var commands by rememberSaveable(stateSaver = listSaver<List<String>, String>(
        save = { it }, restore = { it.toList() }
    )) { mutableStateOf(emptyList<String>()) }
    var gapMinutes by rememberSaveable { mutableIntStateOf(prefs.awayGapMinutes) }
    var gapOpen by rememberSaveable { mutableStateOf(false) }
    val model = remember(commands, dateText, zoneText, gapMinutes) { DaySimulation.replay(date, zone, commands, gapMinutes) }
    var styleName by rememberSaveable { mutableStateOf(prefs.teasingStyle.name) }
    val style = TeasingStyle.valueOf(styleName)
    var audioEnabled by rememberSaveable { mutableStateOf(true) }
    var showTasks by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var jumpOpen by rememberSaveable { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var audioStatus by remember { mutableStateOf<String?>(null) }
    var savedReaction by rememberSaveable { mutableStateOf<String?>(null) }
    val reaction = remember(savedReaction) { VoiceReaction.restore(savedReaction) }
    var playback by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    fun stop() { generation++; playback?.cancel(); voice.stop(); running = false; playing = false }
    fun select(event: SimulationEvent, selectedStyle: TeasingStyle = TeasingStyle.valueOf(styleName)) {
        savedReaction = event.kind?.let { library.reaction(it, selectedStyle).saved() }
        audioStatus = null
    }
    fun execute(command: String, replace: Boolean = false): DaySimulation {
        val updated = if (replace) listOf(command) else commands + command
        commands = updated
        return DaySimulation.replay(date, zone, updated, gapMinutes).also { select(it.event) }
    }
    suspend fun hear(selected: VoiceReaction?, token: Int) {
        if (selected == null) return
        playing = true
        try {
            val result = voice.play(selected, preview = true)
            if (generation == token) audioStatus = if (result == VoiceResult.NO_RECORDING) null
                else result.message ?: if (result == VoiceResult.PLAYED) "Recording played" else null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (generation == token) audioStatus = "Couldn't load this recording. Check it in Voice settings."
        } finally { if (generation == token) playing = false }
    }
    fun perform(command: String, replace: Boolean = false) {
        stop()
        val result = execute(command, replace)
        val token = generation
        val selected = VoiceReaction.restore(savedReaction)
        if (audioEnabled && result.event.audible) playback = scope.launch { hear(selected, token) }
    }
    fun runDay() {
        stop()
        // Leave room for a complete run in the bounded saved command history.
        if (model.finished || commands.size + DaySimulation.scenes.size > 300) commands = emptyList()
        running = true
        val token = generation
        playback = scope.launch {
            try {
                while (generation == token && commands.size < 300 && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    val result = execute("next")
                    if (audioEnabled && result.event.audible) hear(VoiceReaction.restore(savedReaction), token)
                    if (result.finished) break
                    delay(2_000)
                }
            } finally { if (generation == token) { running = false; playing = false } }
        }
    }
    DisposableEffect(lifecycle, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop(); voice.close() }
    }
    BackHandler(onBack = onClose)
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(timeFormat)
    val nextDay = Instant.ofEpochMilli(model.now).atZone(zone).toLocalDate() != date
    val canInteract = commands.size < 290
    Scaffold(topBar = { TestToolsHeader("Simulate a day", "Close simulator", onClose) }, bottomBar = {
        Surface(color = Panel) {
            Column(Modifier.navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (running) stop() else runDay() }, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)) {
                        Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (running) "Pause" else if (model.finished && !model.standalone) "Run again" else "Run day")
                    }
                    OutlinedButton(onClick = { perform("next") }, enabled = !model.finished && canInteract,
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)) { Text("Next event") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { perform("+5m") }, enabled = canInteract && !model.clockFinished,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("+5 min") }
                    OutlinedButton(onClick = { perform("+1h") }, enabled = canInteract && !model.clockFinished,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("+1 hour") }
                    TextButton(onClick = { stop(); commands = emptyList(); audioStatus = null; savedReaction = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Reset") }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("day_simulation"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Sample mode · your tasks and history stay untouched.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(if (nextDay) "SAMPLE TIME · NEXT DAY" else "SAMPLE TIME", Lime)
                        Text(clock(model.now), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("simulation_clock"))
                    }
                    Text(if (model.standalone) "Scenario preview" else "${(model.sceneIndex + 1).coerceAtLeast(0)} / ${DaySimulation.scenes.size} events",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (!model.standalone) LinearProgressIndicator(progress = { (model.sceneIndex + 1).coerceAtLeast(0).toFloat() / DaySimulation.scenes.size },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                Text(if (model.foreground) "Simulating Today open" else "Simulating the app in the background", color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { stop(); jumpOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Jump to situation") }
            }
            item {
                Surface(color = Panel, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(model.event.title, color = Cream, style = MaterialTheme.typography.titleLarge)
                        Text("At ${clock(model.event.at)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text(model.event.detail, color = Muted)
                        if (commands.isEmpty()) Text("Run day plays the tour. Next event lets you go at your own pace.",
                            color = Lime, style = MaterialTheme.typography.bodyMedium)
                        model.event.kind?.let { kind ->
                            reaction?.let { Text(it.caption, color = Lime, style = MaterialTheme.typography.titleMedium) }
                            Text(kind.displayName(), color = Muted, style = MaterialTheme.typography.bodySmall)
                            reaction?.explanation?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                            if (!model.event.audible) Text("This event is silent. You can preview its recording manually.", color = Muted)
                            if (reaction?.clip != null) OutlinedButton(onClick = {
                                stop(); audioStatus = null
                                val token = generation
                                playback = scope.launch { hear(reaction, token) }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (playing) "Replay clip" else "Hear clip") }
                        }
                        audioStatus?.let { Text(it, color = if (it == "Recording played") Muted else Coral, style = MaterialTheme.typography.bodySmall) }
                        if (playing) TextButton(onClick = ::stop) { Text("Stop audio") }
                        if (model.canUndo) OutlinedButton(onClick = { perform("undo") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo sample action") }
                    }
                }
            }
            item {
                TextButton(onClick = { stop(); showOptions = !showOptions }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (showOptions) "Expanded" else "Collapsed" }) {
                    Text("Simulation options", Modifier.weight(1f))
                    Icon(if (showOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (showOptions) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Play voices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(checked = audioEnabled, onCheckedChange = { stop(); audioEnabled = it },
                            modifier = Modifier.semantics { contentDescription = "Simulation voices" })
                    }
                    Text("Uses your saved voice and volume. Captions follow the selected clip; missing recordings stay text-only.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    BiteChoiceRow(TeasingStyle.entries.map { it.label }, style.ordinal, { index ->
                        stop(); styleName = TeasingStyle.entries[index].name; select(model.event, TeasingStyle.entries[index])
                    })
                    OutlinedButton(onClick = { stop(); gapOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Sample nudge gap · $gapMinutes min")
                    }
                    TextButton(onClick = { perform(if (model.foreground) "leave" else "return") }, enabled = canInteract,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (model.foreground) "Leave sample app" else "Return to sample app") }
                    Text("Voice, style, and gap changes here affect this simulation only.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    Text("Fast-forward plays the latest reaction. Jumping rebuilds the sample day. Sample quiet hours are 22:00–08:00. Use Test & troubleshoot to check real phone notifications.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                TextButton(onClick = { showTasks = !showTasks }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Sample tasks (${model.tasks.size})")
                    Icon(if (showTasks) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (showTasks) {
                if (model.tasks.isEmpty()) item { Text("No sample tasks yet.", color = Muted) }
                items(model.tasks, key = { "task-${it.id}" }) { task ->
                    Column {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        val due = Instant.ofEpochMilli(task.dueAt).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d · HH:mm"))
                        Text(if (task.completedAt != null) "Completed · $due" else "Pending · $due · ${task.minutes} min", color = Muted)
                        if (task.repeatRule != RepeatRule.NONE.name) Text(RepeatRule.valueOf(task.repeatRule).label, color = Lime)
                    }
                }
                item { Text("${model.history.size} sample history entries", color = Muted) }
            }
            item { TextButton(onClick = { showLog = !showLog }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Simulation log (${model.log.size})") } }
            if (showLog) items(model.log.asReversed()) { entry ->
                Column { Text("${clock(entry.at)} · ${entry.title}"); Text(entry.detail, color = Muted, style = MaterialTheme.typography.bodySmall) }
            }
            if (!canInteract) item { Text("Reset to continue exploring.", color = Lime) }
        }
    }
    if (jumpOpen) AlertDialog(onDismissRequest = { jumpOpen = false }, title = { Text("Choose a situation") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp).testTag("simulation_scenarios")) {
            item { Text("Away scenarios", style = MaterialTheme.typography.titleMedium) }
            items(listOf("empty" to "An empty list", "small" to "Just three tasks", "low" to "Only low-priority tasks", "started" to "Started but unfinished")) { (preset, title) ->
                TextButton(onClick = { jumpOpen = false; perform("preset:$preset", replace = true) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(title) }
            }
            item { Text("Guided day", style = MaterialTheme.typography.titleMedium) }
            items(DaySimulation.scenes.withIndex().toList()) { (index, scene) ->
                TextButton(onClick = { jumpOpen = false; perform("jump:$index", replace = true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(scene.title, Modifier.weight(1f)); Text(if (scene.time.startsWith("33:")) "Next day" else scene.time.take(5), color = Muted)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { jumpOpen = false }) { Text("Close") } })
    if (gapOpen) NudgeGapDialog(gapMinutes, onDismiss = { gapOpen = false }, onSave = {
        stop(); gapMinutes = it; gapOpen = false
        select(DaySimulation.replay(date, zone, commands, it).event)
    })
}
