@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dracula.taskbite

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val shortDate = DateTimeFormatter.ofPattern("MMM d · h:mm a")
private val clockFormat = DateTimeFormatter.ofPattern("h:mm a")
fun displayTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(shortDate)
fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

@Composable fun Eyebrow(text: String, color: Color = Muted) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}
@Composable fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (subtitle.isNotBlank()) Eyebrow(subtitle)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable fun Onboarding(onContinue: (Boolean) -> Unit) {
    var speak by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(32.dp))
        BiteMascot(Modifier.size(56.dp))
        Text("TaskBite", style = MaterialTheme.typography.headlineLarge)
        Text("Your day, one task at a time.", style = MaterialTheme.typography.titleLarge)
        Text("Add a task, give it a time, and check it off. Bite adds a little commentary along the way.", color = Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("In-app voice", style = MaterialTheme.typography.titleMedium)
                Text("Real recorded reactions. Optional, always.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(speak, onCheckedChange = { speak = it })
        }
        Text("Tasks stay on your phone. Updates keep your progress.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onContinue(speak) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Let's get something done")
        }
        Text("Made By Dracula ❤️", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun TodayScreen(tasks: List<Task>, bite: String, now: Long, onVoice: () -> Unit, onAdd: () -> Unit,
    voiceStatus: String? = null, onReplay: (() -> Unit)? = null,
    onComplete: (Task) -> Unit, onStart: (Task) -> Unit, onSnooze: (Task) -> Unit,
    onEdit: (Task) -> Unit, onArchive: (Task) -> Unit, onDelete: (Task) -> Unit, onCalendar: (Task) -> Unit,
    onLater: (Task) -> Unit = {}, onCheckItem: (Task, String, Boolean) -> Unit = { _, _, _ -> }) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val today = localDate(now)
    val end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val doneToday = tasks.count { it.completedAt?.let(::localDate) == today }
    val dueToday = tasks.count { it.completedAt == null && it.dueAt < end }
    val overdue = tasks.count { it.completedAt == null && it.dueAt < now }
    val visible = when (filter) {
        0 -> tasks.filter { (it.completedAt == null && it.dueAt < end) || it.completedAt?.let(::localDate) == today }
        1 -> tasks.filter { it.completedAt == null }
        else -> tasks.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    }.filter { Checklist.matches(it, query) }.sortedBy { it.completedAt != null }
    LazyColumn(Modifier.fillMaxSize().testTag("today_tasks"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your tasks", style = MaterialTheme.typography.headlineMedium)
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(48.dp)) {
                    Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close task search" else "Search tasks")
                }
                FilledTonalIconButton(onClick = onVoice, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = PrimaryContainer, contentColor = Lime)) {
                    Icon(Icons.Rounded.Mic, "Add task by voice", tint = Lime)
                }
            }
            Text("$dueToday pending · $doneToday done" + if (overdue > 0) " · $overdue overdue" else "",
                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            BiteChoiceRow(listOf("Today", "All pending", "Done"), filter, { filter = it })
        }
        if (searching) item { TaskSearch(query, { query = it }, "Search this task view") }
        if (onReplay != null || voiceStatus != null) item {
            Surface(shape = MaterialTheme.shapes.large, color = PanelRaised) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(bite, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reaction_caption"))
                        if (voiceStatus != null) Text(voiceStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (onReplay != null) IconButton(onClick = onReplay, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.PlayArrow, "Play reaction", tint = Lime)
                    }
                }
            }
        }
        if (visible.isEmpty()) item {
            if (query.isNotBlank()) EmptyState("No matching tasks", "Try another word or change the task filter.")
            else EmptyState(if (filter == 2) "Your victories go here." else "A little too peaceful.",
                if (filter == 2) "Finish your first task. I'll try to act surprised." else "No tasks in this view. Give your day a small mission.", onAdd)
        }
        items(visible, key = { it.id }) { task -> TaskCard(task, now, onComplete, onStart, onSnooze, onEdit, onArchive, onDelete, onCalendar, onLater, onCheckItem) }
    }
}

@Composable private fun EmptyState(title: String, detail: String, onAdd: (() -> Unit)? = null) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = SurfaceLow)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = Muted)
            if (onAdd != null) TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(" Add task") }
        }
    }
}

@Composable private fun TaskCard(task: Task, now: Long, onComplete: (Task) -> Unit, onStart: (Task) -> Unit,
    onSnooze: (Task) -> Unit, onEdit: (Task) -> Unit, onArchive: (Task) -> Unit, onDelete: (Task) -> Unit, onCalendar: (Task) -> Unit,
    onLater: (Task) -> Unit, onCheckItem: (Task, String, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var checklistOpen by rememberSaveable(task.id) { mutableStateOf(false) }
    val checklist = remember(task.checklist) { Checklist.decode(task.checklist) }
    val done = task.completedAt != null
    val overdue = task.dueAt < now && !done
    val started = task.startedAt != null && !done
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (done) SurfaceLow else Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 1.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                FilledTonalIconToggleButton(checked = done, onCheckedChange = { onComplete(task) }, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                        containerColor = PanelRaised, contentColor = Muted,
                        checkedContainerColor = PrimaryContainer, checkedContentColor = Lime)) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (done) "Reopen ${task.title}" else "Complete ${task.title}", tint = if (done) Lime else Muted)
                }
                Column(Modifier.weight(1f).clickable { onEdit(task) }.padding(start = 8.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, color = if (done) Muted else Cream,
                        textDecoration = if (done) TextDecoration.LineThrough else null)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (overdue) StatusChip("Overdue", Coral, filled = true)
                        if (started) StatusChip("Started", Lime)
                        if (task.priority == 2) StatusChip("High", Coral)
                        StatusChip("${task.minutes} min")
                        if (task.repeatRule != RepeatRule.NONE.name) StatusChip(RepeatRule.valueOf(task.repeatRule).label, Lime)
                    }
                    Text(displayTime(task.dueAt), color = if (overdue) Coral else Muted, style = MaterialTheme.typography.bodySmall)
                    if (task.notes.isNotBlank()) Text(task.notes, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreVert, "Task options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit task") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit(task) })
                        DropdownMenuItem(text = { Text("Add to phone calendar") }, leadingIcon = { Icon(Icons.Rounded.EventAvailable, null) }, onClick = { menu = false; onCalendar(task) })
                        DropdownMenuItem(text = { Text("Archive task") }, leadingIcon = { Icon(Icons.Rounded.Inventory2, null) }, onClick = { menu = false; onArchive(task) })
                        DropdownMenuItem(text = { Text("Delete task", color = Coral) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Coral) }, onClick = { menu = false; onDelete(task) })
                    }
                }
            }
            if (checklist.isNotEmpty()) {
                TextButton(onClick = { checklistOpen = !checklistOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(if (checklistOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Text("Checklist · ${checklist.count { it.done }}/${checklist.size}")
                }
                if (checklistOpen) checklist.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, enabled = !done, onCheckedChange = { onCheckItem(task, item.id, it) },
                            modifier = Modifier.semantics { contentDescription = "Checklist: ${item.text}" })
                        Text(item.text, Modifier.weight(1f), textDecoration = if (item.done) TextDecoration.LineThrough else null)
                    }
                }
            }
            if (!done) FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { onStart(task) }, enabled = task.startedAt == null,
                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = PrimaryContainer, contentColor = Lime)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(if (task.startedAt == null) "Start" else "In progress")
                }
                TextButton(onClick = { onLater(task) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Later today", color = Muted)
                }
                TextButton(onClick = { onSnooze(task) }, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Snooze, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Tomorrow", color = Muted)
                }
            }
        }
    }
}

@Composable fun CalendarScreen(tasks: List<Task>, selectedDate: LocalDate, now: Long,
    onDate: (LocalDate) -> Unit, onEdit: (Task) -> Unit, onCalendar: (Task) -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selectedDate).toString()) }
    val month = YearMonth.parse(monthText)
    val dayTasks = tasks.filter { localDate(it.dueAt) == selectedDate }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Your calendar", "") }
        item {
            Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { monthText = month.minusMonths(1).toString() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onDate(LocalDate.now()); monthText = YearMonth.now().toString() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Today") }
                        IconButton(onClick = { monthText = month.plusMonths(1).toString() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.ChevronRight, "Next month") }
                    }
                    Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall) } } }
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    val rows = (offset + month.lengthOfMonth() + 6) / 7
                    repeat(rows) { week ->
                        Row {
                            repeat(7) { day ->
                                val number = week * 7 + day - offset + 1
                                if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp)) else {
                                    val date = month.atDay(number)
                                    val selected = date == selectedDate
                                    val hasTasks = tasks.any { localDate(it.dueAt) == date }
                                    Column(Modifier.weight(1f).height(48.dp).background(if (selected) Lime else Color.Transparent, RoundedCornerShape(12.dp))
                                        .clickable { onDate(date) }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(number.toString(), color = if (selected) Ink else if (date == localDate(now)) Lime else Cream, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        Box(Modifier.padding(top = 3.dp).size(4.dp).background(if (hasTasks) (if (selected) Ink else Lime) else Color.Transparent, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleLarge)
            Text("${dayTasks.size} missions · Tap a task to edit", style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (dayTasks.isEmpty()) item { EmptyState("A blank page. Dangerous.", "Add a task using the button below. It'll use this date.") }
        items(dayTasks, key = { it.id }) { task ->
            Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Panel), onClick = { onEdit(task) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (task.completedAt != null) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule, null, tint = Lime)
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).format(clockFormat)} · ${task.minutes} min", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { onCalendar(task) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.EventAvailable, "Add to phone calendar", tint = Lime) }
                }
            }
        }
        item { Text("Phone calendar opens a separate event for you to save. Changes don't sync back to TaskBite.", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun HistoryScreen(tasks: List<Task>, events: List<TaskEvent>, onRestore: (Task) -> Unit, onReopen: (Task) -> Unit, onDelete: (Task) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val archived = tasks.filter { it.archived && Checklist.matches(it, query) }
    val finished = tasks.filter { it.completedAt != null && !it.archived && Checklist.matches(it, query) }.sortedByDescending { it.completedAt }
    val matchingIds = tasks.filter { Checklist.matches(it, query) }.mapTo(hashSetOf()) { it.id }
    val foundEvents = events.filter { it.title.contains(query.trim(), true) || it.kind.contains(query.trim(), true) || it.taskId in matchingIds }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Task history", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(48.dp)) {
                Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "Close history search" else "Search history")
            }
        } }
        if (searching) item { TaskSearch(query, { query = it }, "Search history") }
        item {
            Text("${finished.size} completed", color = Muted, style = MaterialTheme.typography.bodyMedium)
            BiteChoiceRow(listOf("Activity", "Completed", "Archived"), filter, { filter = it }, Modifier.padding(top = 12.dp))
        }
        if (filter == 0) {
            if (foundEvents.isEmpty()) item { EmptyState(if (query.isNotBlank()) "No matching activity" else "No receipts. Yet.", "Your task actions will appear here, even after archiving.") }
            items(foundEvents, key = { it.id }) { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Dot(if (event.kind == "COMPLETED") Lime else Muted, Modifier.padding(top = 6.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Eyebrow(event.kind, if (event.kind == "COMPLETED") Lime else Muted)
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(displayTime(event.at), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = Panel)
            }
        } else {
            val list = if (filter == 1) finished else archived
            if (list.isEmpty()) item { EmptyState(if (filter == 1) "Nothing finished yet." else "Nothing tucked away.", "Your history stays here across app updates.") }
            items(list, key = { it.id }) { task ->
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(displayTime(task.completedAt ?: task.updatedAt), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { if (filter == 1) onReopen(task) else onRestore(task) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (filter == 1) "Reopen" else "Restore") }
                        IconButton(onClick = { onDelete(task) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.DeleteOutline, "Delete ${task.title}", tint = Coral)
                        }
                    }
                }
            }
            item { Text("Deleting removes the task and its history from this phone, permanently.", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskEditor(existing: Task?, spokenTitle: String, initialDate: LocalDate, saving: Boolean = false,
    onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: ((Task) -> Unit)? = null) {
    val context = LocalContext.current
    val defaultDue = existing?.let { Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        ?: if (initialDate == LocalDate.now()) LocalDateTime.now().plusHours(1).withSecond(0).withNano(0) else initialDate.atTime(18, 0)
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: spokenTitle) }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.notes ?: "") }
    var checklist by rememberSaveable(existing?.id) { mutableStateOf(existing?.checklist ?: "[]") }
    var checklistOpen by rememberSaveable(existing?.id) { mutableStateOf(existing?.checklist?.let { it != "[]" } ?: false) }
    var newStep by rememberSaveable(existing?.id) { mutableStateOf("") }
    var dateText by rememberSaveable(existing?.id) { mutableStateOf(defaultDue.toLocalDate().toString()) }
    var timeText by rememberSaveable(existing?.id) { mutableStateOf(defaultDue.toLocalTime().toString()) }
    var minutes by rememberSaveable(existing?.id) { mutableStateOf((existing?.minutes ?: 15).toString()) }
    var priority by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.priority ?: 1) }
    var repeatRule by rememberSaveable(existing?.id) { mutableStateOf(existing?.repeatRule ?: RepeatRule.NONE.name) }
    var repeatMenu by remember { mutableStateOf(false) }
    val draftId = rememberSaveable { UUID.randomUUID().toString() }
    val createdAt = rememberSaveable { System.currentTimeMillis() }
    val baseRevision = rememberSaveable(existing?.id) { existing?.updatedAt ?: createdAt }
    val validMinutes = minutes.toIntOrNull()?.let { it in 1..1440 } == true
    val date = LocalDate.parse(dateText)
    val time = LocalTime.parse(timeText)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (existing == null) "New task" else "Edit task", style = MaterialTheme.typography.headlineMedium)
                }
                if (existing != null && onDelete != null) IconButton(onClick = { onDelete(existing) }, enabled = !saving, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, "Delete task", tint = Coral)
                }
            }
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Task") }, placeholder = { Text("Finally reply to that email") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, minLines = 1, maxLines = 3)
            OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, maxLines = 4)
            TextButton(onClick = { checklistOpen = !checklistOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Checklist, null); Text(" Checklist (optional)")
            }
            if (checklistOpen) {
                val steps = Checklist.decode(checklist)
                steps.forEach { item -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.done, { checked -> checklist = Checklist.encode(steps.map { if (it.id == item.id) it.copy(done = checked) else it }) },
                        modifier = Modifier.semantics { contentDescription = "Checklist: ${item.text}" })
                    Text(item.text, Modifier.weight(1f))
                    IconButton(onClick = { checklist = Checklist.encode(steps.filter { it.id != item.id }) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, "Remove step ${item.text}")
                    }
                } }
                if (steps.size < Checklist.MAX_ITEMS) {
                    OutlinedTextField(newStep, { newStep = it.take(200) }, label = { Text("New checklist step") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { checklist = Checklist.encode(steps + ChecklistItem(text = newStep.trim())); newStep = "" },
                        enabled = newStep.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Add step") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> dateText = LocalDate.of(y, m + 1, d).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))) }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> timeText = LocalTime.of(hour, minute).toString() }, time.hour, time.minute, false).show()
                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(time.format(clockFormat)) }
            }
            Box {
                OutlinedButton(onClick = { repeatMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Repeat, null, Modifier.size(18.dp))
                    Text(RepeatRule.valueOf(repeatRule).label, Modifier.weight(1f).padding(horizontal = 8.dp))
                    Icon(Icons.Rounded.ExpandMore, null)
                }
                DropdownMenu(expanded = repeatMenu, onDismissRequest = { repeatMenu = false }) {
                    RepeatRule.entries.forEach { rule -> DropdownMenuItem(text = { Text(rule.label) }, onClick = { repeatRule = rule.name; repeatMenu = false }) }
                }
            }
            if (repeatRule != RepeatRule.NONE.name) Text("Completing this task creates the next scheduled occurrence. Past completions stay in History.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(minutes, { value -> if (value.length <= 4 && value.all { it.isDigit() }) minutes = value },
                label = { Text("Estimated minutes") }, supportingText = { Text("1–1440 minutes") },
                isError = !validMinutes, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
            Column {
                Eyebrow("PRIORITY")
                BiteChoiceRow(listOf("Low", "Normal", "High"), priority, { priority = it }, Modifier.padding(top = 8.dp))
            }
            Button(onClick = {
                onSave(Task(id = existing?.id ?: draftId, title = title.trim(), notes = notes.trim(),
                    dueAt = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), minutes = minutes.toInt(),
                    priority = priority, createdAt = existing?.createdAt ?: createdAt, updatedAt = baseRevision, repeatRule = repeatRule,
                    checklist = if (newStep.isNotBlank() && Checklist.decode(checklist).size < Checklist.MAX_ITEMS)
                        Checklist.encode(Checklist.decode(checklist) + ChecklistItem(text = newStep.trim())) else checklist))
            }, enabled = !saving && title.isNotBlank() && validMinutes, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(if (saving) "Saving…" else if (existing == null) "Add task" else "Save changes", fontWeight = FontWeight.Bold)
            }
            Text("${if (spokenTitle.isNotBlank()) "Review your words, date, and time before saving. " else ""}Reminders may arrive later if Android delays background work.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable private fun TaskSearch(query: String, onQuery: (String) -> Unit, label: String) {
    OutlinedTextField(query, { onQuery(it.take(200)) }, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, "Clear search")
        } })
}
