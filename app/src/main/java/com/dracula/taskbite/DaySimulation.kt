package com.dracula.taskbite

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SimulationEvent(val at: Long, val title: String, val detail: String,
    val kind: RoastKind? = null, val audible: Boolean = kind != null)
data class SimulationScene(val time: String, val title: String, val action: String)
data class SimulationHistory(val taskId: String, val label: String)

/** A memory-only playground. No Context, repository, preferences, scheduler, or notification API. */
class DaySimulation(val date: LocalDate, val zone: ZoneId = ZoneId.systemDefault(),
    val gapMinutes: Int = AwayPolicy.DEFAULT_GAP_MINUTES) {
    var now = at("08:55"); private set
    var sceneIndex = -1; private set
    var tasks = listOf<Task>(); private set
    var history = listOf<SimulationHistory>(); private set
    var log = listOf<SimulationEvent>(); private set
    var event = SimulationEvent(now, "Ready for a sample day", "Start at 08:55 with an empty sample list."); private set
    var foreground = true; private set
    private var lastReminder = 0L
    private var lastReaction = 0L
    private var lastInteraction = now
    private var idleHandled = -1L
    private var plannedDay: LocalDate? = null
    private var activeDay: LocalDate? = null
    private var sessionStart = now
    private var sessionProgress = false
    private var abandonedAt: Long? = null
    private var awaySince = 0L
    private var lastActivity = 0L
    private var lastAway = 0L
    private var awayCount = 0
    private var awayDay = ""
    private var handledAbandon = 0L
    var standalone = false; private set
    private data class Undo(val tasks: List<Task>, val history: List<SimulationHistory>, val expires: Long)
    private var undo: Undo? = null
    val canUndo get() = undo?.let { now <= it.expires } == true
    val finished get() = standalone || sceneIndex >= scenes.lastIndex
    val clockFinished get() = now >= at("33:00")
    val quiet get() = ReminderPolicy.inQuietHours(localHour(), 22, 8)
    private fun localDay() = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    private fun localHour() = Instant.ofEpochMilli(now).atZone(zone).hour
    private fun at(clock: String): Long {
        val parts = clock.split(":").map(String::toInt)
        return date.plusDays((parts[0] / 24).toLong()).atTime(parts[0] % 24, parts[1], parts.getOrElse(2) { 0 })
            .atZone(zone).toInstant().toEpochMilli()
    }
    private fun emit(title: String, detail: String, kind: RoastKind? = null, audible: Boolean = kind != null) {
        event = SimulationEvent(now, title, detail, kind, audible)
        log = (log + event).takeLast(200)
        if (kind != null && audible && foreground) lastReaction = now
    }
    private fun interacted() { lastInteraction = now }
    private fun activity() { activeDay = localDay(); lastActivity = now; interacted() }
    private fun pending() = tasks.filter { !it.archived && it.completedAt == null }.sortedBy { it.dueAt }
    private fun rememberUndo() { undo = Undo(tasks, history, now + 10_000) }
    private fun add(id: String, title: String, due: String, minutes: Int = 15, repeat: RepeatRule = RepeatRule.NONE) {
        tasks = tasks + Task(id = id, title = title, dueAt = at(due), minutes = minutes,
            createdAt = now, updatedAt = now, repeatRule = repeat.name)
        history = history + SimulationHistory(id, "Added $title")
        activity()
        emit("Task added", "$title was added to the sample list.", RoastKind.CREATED)
    }
    private fun start(id: String) {
        val task = tasks.firstOrNull { it.id == id && it.completedAt == null } ?: return
        tasks = tasks.map { if (it.id == id) it.copy(startedAt = now) else it }
        history = history + SimulationHistory(id, "Started ${task.title}")
        activity(); sessionProgress = true
        emit("Task started", "Starting records progress; its deadline still applies.", RoastKind.STARTED)
    }
    private fun complete(id: String, fromWidget: Boolean = false) {
        val task = tasks.firstOrNull { it.id == id && it.completedAt == null } ?: return
        if (!fromWidget) rememberUndo() else undo = null
        val rule = RepeatRule.valueOf(task.repeatRule)
        val nextId = if (rule != RepeatRule.NONE) "$id-next" else null
        tasks = tasks.map { if (it.id == id) it.copy(completedAt = now, nextOccurrenceId = nextId) else it }
        if (nextId != null) tasks = tasks + Task(id = nextId, title = task.title, minutes = task.minutes,
            dueAt = Recurrence.next(task.dueAt, rule, now, zone), createdAt = now, updatedAt = now, repeatRule = rule.name)
        history = history + SimulationHistory(id, "Completed ${task.title}")
        activity(); sessionProgress = true
        emit(if (fromWidget) "Completed from a sample widget" else "Task completed",
            if (fromWidget) "Widget completion saves progress silently, without the in-app Undo bar."
            else if (nextId != null) "This task is done. Its next repeat is ready, and this completion stays in History."
            else "${task.title} is complete. Undo lasts ten seconds on the virtual clock.",
            if (fromWidget) null else Roasts.forAction(RoastKind.COMPLETED, task, now))
    }
    private fun snooze(id: String) {
        val task = tasks.firstOrNull { it.id == id && it.completedAt == null } ?: return
        rememberUndo()
        val due = Instant.ofEpochMilli(task.dueAt).atZone(zone)
        val next = maxOf(localDay(), due.toLocalDate()).plusDays(1).atTime(due.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        tasks = tasks.map { if (it.id == id) it.copy(dueAt = next, remindAfter = next, startedAt = null, snoozeCount = it.snoozeCount + 1) else it }
        history = history + SimulationHistory(id, "Postponed ${task.title} (${task.snoozeCount + 1})")
        activity()
        emit("Postponement ${task.snoozeCount + 1}", "Moved to the next day at the same time. Bite notices when you keep postponing.",
            Roasts.forAction(RoastKind.SNOOZE, task, now))
    }
    private fun delete(id: String) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        rememberUndo()
        tasks = tasks.filterNot { it.id == id }; history = history.filterNot { it.taskId == id }
        activity()
        emit("Sample deletion confirmed", "${task.title} and its sample history are removed. Try Undo before advancing ten virtual seconds.")
    }
    fun undoAction() {
        val snapshot = undo
        if (snapshot == null || now > snapshot.expires) {
            emit("Undo unavailable", "The ten-second virtual Undo window ended, or there is no action to undo.")
            return
        }
        tasks = snapshot.tasks; history = snapshot.history; undo = null; activity()
        emit("Sample action undone", "Your sample tasks and history are back to how they were before that action.")
    }
    private fun tick() {
        if (quiet) return
        val waiting = pending()
        val allowed = now - lastReminder >= ReminderPolicy.GLOBAL_COOLDOWN
        if (allowed && now - lastAway >= minOf(ReminderPolicy.GLOBAL_COOLDOWN, AwayPolicy.gapMillis(gapMinutes)) &&
            waiting.isEmpty() && localHour() >= 9 && plannedDay != localDay() && activeDay != localDay()) {
            plannedDay = localDay(); lastReminder = now
            emit("Plan your day", "Your sample list is empty. This is the daily reminder to plan something small.", RoastKind.PLAN_DAY)
            return
        }
        val canNudge = !foreground || InAppReactionPolicy.canNudge(now, lastReaction)
        val due = waiting.firstOrNull { ReminderPolicy.eligible(it, now) }
        if (allowed && canNudge && due != null) {
            tasks = tasks.map { if (it.id == due.id) it.copy(remindAfter = now + ReminderPolicy.TASK_COOLDOWN) else it }
            lastReminder = now
            emit(if (foreground) "Sample task is due" else "Sample background reminder",
                "Still waiting: ${due.title}. Bite reminds you to give it some attention.", ReminderPolicy.kind(due))
            return
        }
        if (!foreground) {
            val state = AwayState(since = awaySince, lastActivity = lastActivity, lastReminder = lastReminder,
                abandonedAt = abandonedAt ?: 0, handledAbandon = handledAbandon, lastAt = lastAway, gapMinutes = gapMinutes)
            AwayPolicy.next(waiting, state, now, activeDay == localDay(), zone)?.takeIf { it.at <= now }?.let { nudge ->
                awayCount = if (awayDay == localDay().toString()) awayCount + 1 else 1
                awayDay = localDay().toString(); lastAway = now
                if (nudge.scenario == AwayScenario.LEFT_WITHOUT_PROGRESS) handledAbandon = abandonedAt ?: 0
                emit(nudge.title, "Away check-in $awayCount today, using a $gapMinutes-minute gap. Bite reacts to what's still waiting.", nudge.scenario.kind)
                return
            }
        }
        if (foreground && now - lastInteraction >= InAppReactionPolicy.IDLE_DELAY && idleHandled != lastInteraction) {
            idleHandled = lastInteraction
            if (canNudge) InAppReactionPolicy.idleKind(waiting, now, zone)?.let { kind ->
                emit(if (kind == RoastKind.TINY) "Tiny-task avoidance" else "Five idle minutes",
                    if (kind == RoastKind.TINY) "Five sample minutes without progress. Bite points you toward a quick task."
                    else "Five sample minutes without a task action. Bite gives you a little nudge.", kind)
            }
        }
    }
    fun leave() {
        if (!foreground) return
        foreground = false; awaySince = now
        abandonedAt = if (!sessionProgress && lastActivity < sessionStart && pending().isNotEmpty() && now - sessionStart >= 10_000) now else null
        emit("Left the sample app", "Away timers are running. Your real app stays open; these are sample notifications only.")
    }
    fun returnToApp() {
        if (foreground) return
        foreground = true; awaySince = 0; sessionStart = now; sessionProgress = false; interacted()
        val abandoned = abandonedAt; abandonedAt = null
        if (abandoned != null && abandoned > handledAbandon && now - abandoned in 0..86_400_000 && pending().isNotEmpty() &&
            InAppReactionPolicy.canNudge(now, maxOf(lastReaction, lastAway)))
            emit("Back without progress", "You're back with tasks still waiting. Bite gives you a gentle push to start.", RoastKind.ABANDON)
        else emit("Back in Today", "You're back. Away nudges pause while you use the app.")
    }
    private fun preset(name: String) {
        now = at("09:00"); foreground = false; awaySince = now; standalone = true
        plannedDay = date // Focus this preview on repeated away nudges, not the daily planning prompt.
        tasks = if (name == "empty") emptyList() else (1..if (name == "started") 1 else 3).map { index ->
            Task(id = "preset-$index", title = listOf("Read ten pages", "Tidy the desk", "Plan dinner")[index - 1],
                dueAt = at("18:00"), priority = if (name == "low") 0 else 1, minutes = 15,
                createdAt = now, updatedAt = now, startedAt = if (name == "started") now else null)
        }
        if (tasks.isNotEmpty()) { lastActivity = now; activeDay = date }
        history = emptyList()
        emit(when (name) { "empty" -> "An empty list"; "low" -> "Only low-priority tasks"; "started" -> "Started but unfinished"; else -> "Just three tasks" },
            "Sample app is away. Advance $gapMinutes minutes to get the first check-in; keep advancing to hear more. No real tasks change.")
    }
    fun advance(millis: Long) {
        require(millis in 0..36 * 60 * 60 * 1000L)
        val target = minOf(now + millis, at("33:00"))
        val previous = event
        while (now < target) { now = minOf(now + 30_000, target); tick() }
        if (quiet) emit("Quiet hours", "Virtual quiet hours are 22:00–08:00. Automatic audio and reminders stay silent.")
        else if (event === previous) emit("Clock advanced", "No reaction is due right now. Move ahead or choose another situation to explore.")
    }
    fun next() {
        if (finished) return
        sceneIndex++
        val scene = scenes[sceneIndex]
        advance(maxOf(0, at(scene.time) - now))
        when (scene.action) {
            "plan", "due", "idle", "tiny", "quiet", "morning", "exit_notice" -> Unit
            "add_update" -> add("update", "Write the project update", "09:45")
            "start" -> start("update")
            "finish" -> complete("update")
            "add_email" -> add("email", "Reply to Priya", "10:00", 10)
            "finish_late" -> complete("email")
            "add_weekend" -> add("weekend", "Plan the weekend", "18:00")
            "snooze" -> snooze("weekend")
            "add_read" -> add("read", "Read ten pages", "17:30")
            "add_water" -> add("water", "Drink water", "11:00", 2)
            "visit" -> { foreground = true; awaySince = 0; sessionProgress = false; sessionStart = now; interacted(); emit("A fresh visit", "Open the sample list and browse without starting or completing anything.") }
            "leave" -> leave()
            "return" -> returnToApp()
            "add_repeat" -> add("stretch", "Stretch for five minutes", "12:30", 5, RepeatRule.DAILY)
            "repeat_done" -> complete("stretch")
            "undo" -> undoAction()
            "delete" -> delete("read")
            "widget" -> complete("water", fromWidget = true)
            "add_quick" -> add("quick", "Send a quick reply", "18:00", 2)
            "resume" -> { returnToApp(); complete("quick") }
            "recurring" -> emit("Check-ins keep going", "There is no daily cap. Your $gapMinutes-minute gap and quiet hours control the pace.")
            "clear_notice" -> emit("Notification cleared", "One clearing reaction plays. Tasks, history, and the next nudge time stay unchanged.", RoastKind.NOTIFICATION_CLEARED)
            "open_notice" -> emit("Notification opened", "Opening a reminder gets attention; it does not start or finish the task.", RoastKind.NOTIFICATION_OPENED)
        }
    }
    companion object {
        val scenes = listOf(
            SimulationScene("09:00", "An empty day", "plan"),
            SimulationScene("09:05", "Add a task", "add_update"),
            SimulationScene("09:10", "Start working", "start"),
            SimulationScene("09:30", "Finish on time", "finish"),
            SimulationScene("09:35", "Another task", "add_email"),
            SimulationScene("10:00", "Miss a deadline", "due"),
            SimulationScene("10:10", "Finish late", "finish_late"),
            SimulationScene("10:15", "Make a plan", "add_weekend"),
            SimulationScene("10:20", "Postpone once", "snooze"),
            SimulationScene("10:25", "Postpone twice", "snooze"),
            SimulationScene("10:30", "Third postponement", "snooze"),
            SimulationScene("10:35", "A task for later", "add_read"),
            SimulationScene("10:40", "Five idle minutes", "idle"),
            SimulationScene("10:45", "Add a tiny task", "add_water"),
            SimulationScene("10:50", "Avoid a tiny task", "tiny"),
            SimulationScene("11:25", "Browse without progress", "visit"),
            SimulationScene("11:30", "Leave the app", "leave"),
            SimulationScene("12:00", "Thirty minutes away", "exit_notice"),
            SimulationScene("12:05", "Return to the app", "return"),
            SimulationScene("12:10", "Add a repeating task", "add_repeat"),
            SimulationScene("12:15", "Complete and repeat", "repeat_done"),
            SimulationScene("12:15:05", "Undo completion", "undo"),
            SimulationScene("12:20", "Confirm a deletion", "delete"),
            SimulationScene("12:20:05", "Undo deletion", "undo"),
            SimulationScene("13:00", "Complete from a widget", "widget"),
            SimulationScene("13:05", "Another tiny task", "add_quick"),
            SimulationScene("13:10", "Spend the afternoon away", "leave"),
            SimulationScene("15:10", "Two hours without task activity", "away_wait"),
            SimulationScene("15:15", "Make progress again", "resume"),
            SimulationScene("15:20", "Leave after progress", "leave"),
            SimulationScene("17:20", "Repeated away check-ins", "recurring"),
            SimulationScene("17:21", "Clear a notification", "clear_notice"),
            SimulationScene("17:22", "Open a notification", "open_notice"),
            SimulationScene("22:00", "Quiet hours", "quiet"),
            SimulationScene("33:00", "The next morning", "morning"),
        )
        fun replay(date: LocalDate, zone: ZoneId, commands: List<String>, gapMinutes: Int = AwayPolicy.DEFAULT_GAP_MINUTES): DaySimulation = DaySimulation(date, zone, gapMinutes).apply {
            commands.take(300).forEach { command -> when {
                command == "next" -> next()
                command == "+5m" -> advance(5 * 60_000)
                command == "+1h" -> advance(60 * 60_000)
                command == "undo" -> undoAction()
                command == "leave" -> leave()
                command == "return" -> returnToApp()
                command.startsWith("preset:") -> preset(command.substringAfter(':'))
                command.startsWith("jump:") -> repeat(command.substringAfter(':').toInt().coerceIn(0, scenes.lastIndex) + 1) { next() }
            } }
        }
    }
}
