package com.dracula.taskbite

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
        Eyebrow("TASKBITE / $subtitle")
        Text(title, style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable fun Onboarding(onContinue: (Boolean) -> Unit) {
    var speak by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(12.dp))
        Eyebrow("MEET YOUR PRODUCTIVITY SIDEKICK", Lime)
        BiteMascot(Modifier.size(132.dp))
        Text("Your tasks.\nMy unsolicited\ncommentary.", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp, lineHeight = 43.sp))
        Text("A little bite. A lot more done.", color = Lime, style = MaterialTheme.typography.titleLarge)
        Text("Plan your day, speak your tasks, and keep a record of every little win. I'll provide the gentle roasting.", color = Muted)
        Surface(shape = RoundedCornerShape(24.dp), color = Panel) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Feature(Icons.Rounded.Mic, "Say it. Save it.", "Speak a task, then review before adding.")
                Feature(Icons.Rounded.CalendarMonth, "Give someday a date.", "Daily planning and calendar handoff.")
                Feature(Icons.Rounded.Shield, "Your progress stays yours.", "Saved on your phone. Export backups anytime.")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Let the sidekick talk", style = MaterialTheme.typography.titleMedium)
                Text("Voice reactions inside the app. Change anytime.", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
            Switch(checked = speak, onCheckedChange = { speak = it })
        }
        Button(onClick = { onContinue(speak) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Let's get something done", fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
        }
        Text("Made By Dracula ❤️", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Lime, modifier = Modifier.size(26.dp))
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable fun TodayScreen(tasks: List<Task>, bite: String, now: Long, onVoice: () -> Unit, onAdd: () -> Unit,
    onComplete: (Task) -> Unit, onStart: (Task) -> Unit, onSnooze: (Task) -> Unit,
    onEdit: (Task) -> Unit, onArchive: (Task) -> Unit, onCalendar: (Task) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val today = localDate(now)
    val end = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val doneToday = tasks.count { it.completedAt?.let(::localDate) == today }
    val dueToday = tasks.count { it.completedAt == null && it.dueAt < end }
    val overdue = tasks.count { it.completedAt == null && it.dueAt < now }
    val visible = when (filter) {
        0 -> tasks.filter { (it.completedAt == null && it.dueAt < end) || it.completedAt?.let(::localDate) == today }
        1 -> tasks.filter { it.completedAt == null }
        else -> tasks.filter { it.completedAt != null }.sortedByDescending { it.completedAt }
    }.sortedBy { it.completedAt != null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("taskbite", fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = (-1).sp)
                Surface(shape = CircleShape, color = Panel) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEE, MMM d")), Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium, color = Muted)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Less later.\nMore done.", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onVoice, modifier = Modifier.size(56.dp), colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Lime, contentColor = Ink)) {
                    Icon(Icons.Rounded.Mic, "Add task by voice", Modifier.size(28.dp))
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF28321D)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BiteMascot(Modifier.size(64.dp), happy = doneToday > 0)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("BITE SAYS", Lime)
                            Text(bite, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Today's little victories", style = MaterialTheme.typography.bodyMedium, color = Muted)
                            Text("$doneToday / ${doneToday + dueToday}", fontFamily = FontFamily.Monospace, color = Lime)
                        }
                        LinearProgressIndicator(progress = { if (doneToday + dueToday == 0) 0f else doneToday.toFloat() / (doneToday + dueToday) },
                            modifier = Modifier.fillMaxWidth().height(6.dp), color = Lime, trackColor = Ink)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("TO DO TODAY", dueToday.toString(), Lime, Modifier.weight(1f))
                Stat("OVERDUE", overdue.toString(), Coral, Modifier.weight(1f))
                Stat("DONE TODAY", doneToday.toString(), Cream, Modifier.weight(1f))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("YOUR MISSIONS")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Today", "All pending", "Done").forEachIndexed { index, label ->
                        FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                    }
                }
            }
        }
        if (visible.isEmpty()) item {
            EmptyState(if (filter == 2) "Your victories go here." else "A little too peaceful.",
                if (filter == 2) "Finish your first task. I'll try to act surprised." else "No tasks in this view. Give your day a small mission.", onAdd)
        }
        items(visible, key = { it.id }) { task -> TaskCard(task, now, onComplete, onStart, onSnooze, onEdit, onArchive, onCalendar) }
        if (visible.isNotEmpty()) item { Text("One small step counts. Even the embarrassingly small ones.", color = Muted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Panel) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = color)
            Eyebrow(label)
        }
    }
}

@Composable private fun EmptyState(title: String, detail: String, onAdd: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFF343B2C), RoundedCornerShape(22.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = Muted)
        if (onAdd != null) TextButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Text(" Add a mission") }
    }
}

@Composable private fun TaskCard(task: Task, now: Long, onComplete: (Task) -> Unit, onStart: (Task) -> Unit,
    onSnooze: (Task) -> Unit, onEdit: (Task) -> Unit, onArchive: (Task) -> Unit, onCalendar: (Task) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val done = task.completedAt != null
    val overdue = task.dueAt < now && !done
    Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = { onComplete(task) }, modifier = Modifier.size(44.dp)) {
                    Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (done) "Reopen ${task.title}" else "Complete ${task.title}", tint = if (done) Lime else Muted)
                }
                Column(Modifier.weight(1f).clickable { onEdit(task) }.padding(top = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, color = if (done) Muted else Cream,
                        textDecoration = if (done) TextDecoration.LineThrough else null)
                    Text("${if (overdue) "Overdue · " else ""}${displayTime(task.dueAt)}", color = if (overdue) Coral else Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Text("${task.minutes} min  ·  ${listOf("Low", "Normal", "High")[task.priority]} priority${if (task.startedAt != null && !done) "  ·  Started" else ""}",
                        color = if (task.startedAt != null && !done) Lime else Muted, style = MaterialTheme.typography.bodySmall)
                    if (task.notes.isNotBlank()) Text(task.notes, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "Task options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit task") }, onClick = { menu = false; onEdit(task) })
                        DropdownMenuItem(text = { Text("Add to phone calendar") }, onClick = { menu = false; onCalendar(task) })
                        DropdownMenuItem(text = { Text("Archive task") }, onClick = { menu = false; onArchive(task) })
                    }
                }
            }
            if (!done) Row(Modifier.padding(start = 42.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onStart(task) }, enabled = task.startedAt == null, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Text(if (task.startedAt == null) "Start" else "In progress")
                }
                TextButton(onClick = { onSnooze(task) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Snooze, null, Modifier.size(18.dp)); Text("Tomorrow", color = Muted)
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Someday, scheduled.", "CALENDAR") }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = Panel) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { monthText = month.minusMonths(1).toString() }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { onDate(LocalDate.now()); monthText = YearMonth.now().toString() }) { Text("Today") }
                        IconButton(onClick = { monthText = month.plusMonths(1).toString() }) { Icon(Icons.Rounded.ChevronRight, "Next month") }
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
            Surface(shape = RoundedCornerShape(18.dp), color = Panel, onClick = { onEdit(task) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (task.completedAt != null) Icons.Rounded.CheckCircle else Icons.Rounded.Schedule, null, tint = Lime)
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium)
                        Text("${Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).format(clockFormat)} · ${task.minutes} min", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { onCalendar(task) }) { Icon(Icons.Rounded.EventAvailable, "Add to phone calendar", tint = Lime) }
                }
            }
        }
        item { Text("Phone calendar opens a separate event for you to save. Changes don't sync back to TaskBite.", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun HistoryScreen(tasks: List<Task>, events: List<TaskEvent>, onRestore: (Task) -> Unit, onReopen: (Task) -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val archived = tasks.filter { it.archived }
    val finished = tasks.filter { it.completedAt != null && !it.archived }.sortedByDescending { it.completedAt }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader("Receipts of progress.", "HISTORY") }
        item {
            Text("${finished.size} completed. Every little win counts.", color = Lime)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Activity", "Completed", "Archived").forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                }
            }
        }
        if (filter == 0) {
            if (events.isEmpty()) item { EmptyState("No receipts. Yet.", "Your task actions will appear here, even after archiving.") }
            items(events, key = { it.id }) { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.padding(top = 6.dp).size(9.dp).background(if (event.kind == "COMPLETED") Lime else Muted, CircleShape))
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
                Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(displayTime(task.completedAt ?: task.updatedAt), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { if (filter == 1) onReopen(task) else onRestore(task) }) { Text(if (filter == 1) "Reopen" else "Restore") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskEditor(existing: Task?, spokenTitle: String, initialDate: LocalDate, saving: Boolean = false, onDismiss: () -> Unit, onSave: (Task) -> Unit) {
    val context = LocalContext.current
    val defaultDue = existing?.let { Instant.ofEpochMilli(it.dueAt).atZone(ZoneId.systemDefault()).toLocalDateTime() }
        ?: if (initialDate == LocalDate.now()) LocalDateTime.now().plusHours(1).withSecond(0).withNano(0) else initialDate.atTime(18, 0)
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: spokenTitle) }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.notes ?: "") }
    var dateText by rememberSaveable(existing?.id) { mutableStateOf(defaultDue.toLocalDate().toString()) }
    var timeText by rememberSaveable(existing?.id) { mutableStateOf(defaultDue.toLocalTime().toString()) }
    var minutes by rememberSaveable(existing?.id) { mutableStateOf((existing?.minutes ?: 15).toString()) }
    var priority by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.priority ?: 1) }
    val draftId = rememberSaveable { UUID.randomUUID().toString() }
    val createdAt = rememberSaveable { System.currentTimeMillis() }
    val validMinutes = minutes.toIntOrNull()?.let { it in 1..1440 } == true
    val date = LocalDate.parse(dateText)
    val time = LocalTime.parse(timeText)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Eyebrow(if (existing == null) "A NEW SIDE QUEST" else "ADJUST THE PLAN", Lime)
            Text(if (existing == null) "What's the mission?" else "Edit your mission.", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(title, { title = it.take(200) }, label = { Text("Task") }, placeholder = { Text("Finally reply to that email") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3)
            OutlinedTextField(notes, { notes = it.take(4000) }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    DatePickerDialog(context, { _, y, m, d -> dateText = LocalDate.of(y, m + 1, d).toString() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))) }
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute -> timeText = LocalTime.of(hour, minute).toString() }, time.hour, time.minute, false).show()
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(time.format(clockFormat)) }
            }
            OutlinedTextField(minutes, { value -> if (value.length <= 4 && value.all { it.isDigit() }) minutes = value },
                label = { Text("Estimated minutes") }, supportingText = { Text("1–1440 minutes. Under 5? Bite has opinions.") },
                isError = !validMinutes, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Column {
                Eyebrow("PRIORITY")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Low", "Normal", "High").forEachIndexed { index, label -> FilterChip(selected = priority == index, onClick = { priority = index }, label = { Text(label) }) }
                }
            }
            Button(onClick = {
                onSave(Task(id = existing?.id ?: draftId, title = title.trim(), notes = notes.trim(),
                    dueAt = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), minutes = minutes.toInt(),
                    priority = priority, createdAt = existing?.createdAt ?: createdAt))
            }, enabled = !saving && title.isNotBlank() && validMinutes, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (saving) "Saving…" else if (existing == null) "Add mission" else "Save changes", fontWeight = FontWeight.Bold)
            }
            Text("${if (spokenTitle.isNotBlank()) "Review your words, date, and time before saving. " else ""}Reminders may arrive later if Android delays background work.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}
