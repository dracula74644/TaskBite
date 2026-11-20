package com.dracula.taskbite

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VoiceSettings(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { Preferences(context) }
    val library = remember { VoiceLibrary(context) }
    val scripts = remember { VoiceScripts(context) }
    val voice = remember { Voice(context) }
    val recorder = remember { VoiceRecorder(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val links = LocalUriHandler.current
    var actor by rememberSaveable { mutableStateOf(preferences.actorFor(preferences.teasingStyle)) }
    var useCustom by rememberSaveable { mutableStateOf(preferences.customVoice) }
    var category by rememberSaveable { mutableStateOf(RoastKind.OVERDUE.name) }
    val kind = RoastKind.valueOf(category)
    val style = preferences.teasingStyle
    var label by rememberSaveable(category, style.name) { mutableStateOf(scripts.nextLine(kind, style, library.custom(kind, style))) }
    var revision by remember { mutableIntStateOf(0) }
    val clips = remember(category, revision) { library.custom(kind) }
    val variantCount = remember(category, revision, actor, useCustom, style) { library.available(kind, style).size }
    val covered = remember(revision) { RoastKind.entries.filter { library.custom(it).isNotEmpty() }.toSet() }
    val recordingCounts = remember(revision, style) { RoastKind.entries.associateWith { library.custom(it, style).size } }
    val recorded = recordingCounts.values.sumOf { minOf(it, VoiceScripts.TARGET_PER_SITUATION) }
    var busy by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var remove by remember { mutableStateOf<VoiceClip?>(null) }
    var editCaption by remember { mutableStateOf<VoiceClip?>(null) }
    var previewCaption by remember { mutableStateOf<String?>(null) }
    var previewGeneration by remember { mutableIntStateOf(0) }
    val categoryLocked = busy || recorder.recording || recorder.draft != null
    LaunchedEffect(recorder.recording) {
        seconds = 0
        if (recorder.recording) {
            val started = SystemClock.elapsedRealtime()
            while (true) { seconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt(); delay(250) }
        }
    }
    fun stopPreview() { previewGeneration++; previewJob?.cancel(); voice.stop(); playing = false }
    fun preview(clip: VoiceClip? = null) {
        stopPreview()
        val token = previewGeneration
        previewJob = scope.launch {
            playing = true
            try {
                message = null
                val selected = clip?.let { VoiceReaction(kind, style, it.caption ?: "Recording: ${it.label}", it) }
                    ?: library.reaction(kind)
                previewCaption = selected.caption
                val result = voice.play(selected, preview = true)
                if (previewGeneration == token) message = if (result == VoiceResult.NO_RECORDING) selected.explanation
                    else result.message ?: selected.explanation ?: if (result == VoiceResult.PLAYED) "Recording played" else null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (previewGeneration == token) message = "Couldn't load this recording. Try another clip." }
            finally { if (previewGeneration == token) playing = false }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { stopPreview(); recorder.cancelRecording() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopPreview(); recorder.close(); voice.close() }
    }
    fun beginRecording() { stopPreview(); message = null; recorder.start() }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) beginRecording() else message = "Microphone permission is needed to record. You can still import audio."
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val filename = runCatching {
                            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            }
                        }.getOrNull() ?: "Imported recording"
                        library.importAudio(kind, uri, filename, style)
                    }
                    preferences.customVoice = true; useCustom = true; revision++
                    message = "Recording imported. Bite will use it for ${kind.displayName().lowercase()}."
                    editCaption = imported
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: "Couldn't import that audio file." }
                finally { busy = false }
            }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.94f).testTag("voice_library"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("BITE, WITH A HUMAN VOICE", Lime)
                        Text("Give Bite a voice.", style = MaterialTheme.typography.headlineSmall)
                    }
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Rounded.Close, "Close voice settings") }
                }
                Text("Real performances, played offline. Pick a voice, or record your own.", color = Muted)
                Text("${style.label} recordings", color = Lime, style = MaterialTheme.typography.titleMedium)
            }
            item {
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !categoryLocked, modifier = Modifier.fillMaxWidth()) {
                        Text(kind.displayName(), Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        RoastKind.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.displayName()) }, onClick = {
                                stopPreview(); category = option.name; menu = false; message = null; previewCaption = null
                            })
                        }
                    }
                }
                Text(kind.triggerDescription(), color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { preview() }, enabled = !categoryLocked && !playing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null); Text("Preview this reaction")
                }
                Text(when (variantCount) {
                    0 -> "No recording for this situation. Record or import one below."
                    1 -> "1 recording for this situation. Add another to vary the voice."
                    else -> "$variantCount recordings for this situation. Bite rotates through them before repeating, even after a restart."
                }, color = Muted, style = MaterialTheme.typography.bodySmall)
                if (kind == RoastKind.NOTIFICATION_CLEARED && useCustom && clips.isEmpty() && library.customForReaction(kind, style).isNotEmpty()) {
                    Text("Using your Leaving without progress recordings. Add a recording here to replace them for clearing notifications.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            previewCaption?.let { caption -> item { Text(caption, color = Lime, modifier = Modifier.testTag("voice_preview_caption")) } }
            if (message != null || recorder.error != null) item { Text(message ?: recorder.error.orEmpty(), color = Lime) }
            if (playing) item { TextButton(onClick = { stopPreview() }) { Text("Stop preview") } }
            item { Text(if (style == TeasingStyle.SWEET && library.actors(style).isEmpty()) "No Sweet voice pack installed. Record or import your own below." else "Included voices", style = MaterialTheme.typography.titleMedium) }
            items(library.actors(style), key = { it.id }) { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(modifier = Modifier.semantics { contentDescription = "${option.name} voice" }, selected = actor == option.id, enabled = !categoryLocked && !playing,
                        onClick = { actor = option.id; preferences.selectActor(style, actor) })
                    Column(Modifier.weight(1f)) {
                        Text(option.name)
                        if (option.description.isNotBlank()) Text(option.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !categoryLocked && !playing, onClick = {
                        actor = option.id; preferences.selectActor(style, actor)
                        preview(library.choose(kind, allowCustom = false))
                    }) { Text("Hear voice") }
                }
            }
            item {
                Text("The caption follows the recording. Task names aren't spoken.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use my recordings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(modifier = Modifier.semantics { contentDescription = "Use my recordings" }, checked = useCustom, onCheckedChange = { stopPreview(); useCustom = it; preferences.customVoice = it })
                }
                Text("Recordings are separate for Classic and Sweet. Missing Sweet clips stay text-only. Older recordings are kept in Classic; use Edit caption to assign their style.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            item { HorizontalDivider(); Text("Your reaction recordings", style = MaterialTheme.typography.titleLarge) }
            if (style == TeasingStyle.SWEET) item {
                Text("Sweet teasing · your own voice", style = MaterialTheme.typography.titleMedium)
                Text("Read these affectionate lines for someone you love, or import their recordings for yourself. Save each clip under its situation. These prompts are scripts until you record them.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("${covered.size}/${RoastKind.entries.size} situations recorded", style = MaterialTheme.typography.titleMedium)
                Text("Your voice pack · $recorded/${RoastKind.entries.size * VoiceScripts.TARGET_PER_SITUATION} recordings", color = Lime)
                LinearProgressIndicator(progress = { recorded.toFloat() / (RoastKind.entries.size * VoiceScripts.TARGET_PER_SITUATION) }, modifier = Modifier.fillMaxWidth())
                Text("Four different human recordings per situation. Read a suggested line, listen, then save. Scripts become voices only after you record or import them.", color = Muted, style = MaterialTheme.typography.bodySmall)
                val missing = RoastKind.entries.firstOrNull { it !in covered }
                if (missing != null) TextButton(enabled = !categoryLocked, onClick = {
                    stopPreview(); category = missing.name; message = "Next: ${missing.displayName()}. Read the suggested line below."
                }) { Text("Next missing situation") }
                val next = RoastKind.entries.firstOrNull { recordingCounts.getValue(it) < VoiceScripts.TARGET_PER_SITUATION }
                if (next != null) TextButton(enabled = !categoryLocked, onClick = {
                    stopPreview(); category = next.name
                    label = scripts.nextLine(next, style, library.custom(next, style))
                    message = "${next.displayName()} · recording ${recordingCounts.getValue(next) + 1} of 4"
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next recording") }
                else Text("Your 48-recording pack is ready. Bite rotates your saved takes.", color = Lime)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(clips, key = { it.file!!.name }) { clip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clip.caption ?: clip.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(enabled = !categoryLocked && !playing, onClick = { preview(clip) }) { Icon(Icons.Rounded.PlayArrow, "Play ${clip.label}") }
                    IconButton(enabled = !categoryLocked, onClick = { stopPreview(); editCaption = clip }) { Icon(Icons.Rounded.Edit, "Edit caption ${clip.label}") }
                    IconButton(enabled = !categoryLocked, onClick = { stopPreview(); remove = clip }) { Icon(Icons.Rounded.DeleteOutline, "Remove ${clip.label}") }
                }
            }
            item {
                OutlinedTextField(value = label, onValueChange = { label = it.take(500) },
                    label = { Text("Words to record") }, enabled = !recorder.recording && !busy,
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("${clips.size}/4 suggested recordings for this situation · up to 5 allowed", color = Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !categoryLocked, onClick = {
                    val options = scripts.lines(kind, style)
                    label = options[(options.indexOf(label) + 1).mod(options.size)]
                }) { Text("Suggest another roast") }
                when {
                    recorder.recording -> {
                        Text("Recording · ${seconds}s / 30s", color = Coral)
                        Button(onClick = { recorder.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Stop recording") }
                        TextButton(onClick = { recorder.cancelRecording() }) { Text("Cancel recording") }
                    }
                    recorder.draft != null -> {
                        Text("Listen to your take, then save it.", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && !playing, onClick = { preview(VoiceClip(label, file = recorder.draft, caption = label)) }) { Text("Listen") }
                            Button(enabled = !busy && !playing, onClick = {
                                val draft = recorder.draft ?: return@Button
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { library.importFile(kind, draft, label, style, caption = label) }
                                        recorder.discardDraft(); revision++
                                        preferences.customVoice = true; useCustom = true
                                        message = "Recording saved. Bite's ready."
                                        label = scripts.nextLine(kind, style, library.custom(kind, style))
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { message = e.message ?: "Couldn't save the recording." }
                                    finally { busy = false }
                                }
                            }) { Text("Save recording") }
                        }
                        TextButton(enabled = !busy, onClick = { stopPreview(); recorder.discardDraft() }) { Text("Discard take") }
                    }
                    else -> {
                        Button(enabled = clips.size < 5 && !busy, onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecording()
                            else microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Mic, null); Text(" Record a roast") }
                        OutlinedButton(enabled = clips.size < 5 && !busy, onClick = { stopPreview(); import.launch(arrayOf("audio/*")) },
                            modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.AudioFile, null); Text(" Import audio") }
                    }
                }
                Text("Recording stops if you leave this screen. Saved clips survive updates and are included in Back up everything. Task-only JSON exports exclude audio.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            item {
                HorizontalDivider()
                Text("Voice credits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                Text("Super Dialogue Audio Pack v1 by Dillon Becker. Voices: Alex Brodie, Sean Lenhart, and Meghan Christian. CC BY 4.0. Performances trimmed and joined into action reactions; mono 16-bit PCM WAV. Original voices, no synthesis. No endorsement implied.", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (library.actors().any { it.id == "high_on_life" }) {
                    Text("High on Life game dialogue: Squanch Games. Clips sourced from Nhaudio32’s Voicy soundboard and converted to 16-bit PCM WAV for this personal build. These game clips are separate from the CC BY pack above.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { links.openUri("https://www.voicy.network/official-soundboards/games/high-on-life") }) { Text("High on Life clip sources") }
                }
                TextButton(onClick = { links.openUri("https://dillonbecker.itch.io/sdap") }) { Text("Original voice pack") }
                TextButton(onClick = { links.openUri("https://creativecommons.org/licenses/by/4.0/") }) { Text("Creative Commons license") }
            }
        }
    }
    editCaption?.let { clip ->
        var words by rememberSaveable(clip.file?.path) { mutableStateOf(clip.caption.orEmpty()) }
        var targetStyle by rememberSaveable(clip.file?.path) { mutableStateOf(style.name) }
        var editError by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editCaption = null }, title = { Text("Caption & style") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter exactly what the recording says. Leave blank if you don't have a transcript.")
                OutlinedTextField(value = words, onValueChange = { words = it.take(500) }, label = { Text("Spoken words") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeasingStyle.entries.forEach { option -> FilterChip(selected = targetStyle == option.name,
                        onClick = { targetStyle = option.name }, label = { Text(option.label) }) }
                }
                editError?.let { Text(it, color = Coral) }
            }
        }, confirmButton = { TextButton(onClick = {
            runCatching { library.updateCaption(clip, kind, TeasingStyle.valueOf(targetStyle), words) }
                .onSuccess { revision++; editCaption = null; previewCaption = null }
                .onFailure { editError = it.message ?: "Couldn't save the caption." }
        }) { Text("Save caption") } }, dismissButton = { TextButton(onClick = { editCaption = null }) { Text("Keep as is") } })
    }
    remove?.let { clip -> AlertDialog(onDismissRequest = { remove = null },
        title = { Text("Remove recording?") }, text = { Text(clip.label) },
        confirmButton = { TextButton(onClick = {
            runCatching { library.remove(clip) }.onSuccess { revision++ }.onFailure { message = "Couldn't remove this recording." }
            remove = null
        }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Keep") } }) }
}
