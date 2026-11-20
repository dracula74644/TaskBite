package com.dracula.taskbite

import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId

data class AwayState(
    val since: Long, val lastActivity: Long = 0, val lastReminder: Long = 0,
    val abandonedAt: Long = 0, val handledAbandon: Long = 0, val lastAt: Long = 0,
    val gapMinutes: Int = AwayPolicy.DEFAULT_GAP_MINUTES,
    val quietEnabled: Boolean = true, val quietStart: Int = 22, val quietEnd: Int = 8,
    val pauseUntil: Long = 0,
)

enum class AwayScenario(val kind: RoastKind) {
    OVERDUE_TASK(RoastKind.OVERDUE), STILL_WORKING(RoastKind.IDLE),
    POSTPONED_TASK(RoastKind.SNOOZE_REPEAT), TINY_TASK(RoastKind.TINY),
    LEFT_WITHOUT_PROGRESS(RoastKind.ABANDON), LOW_PRIORITY(RoastKind.IDLE),
    SMALL_LIST(RoastKind.IDLE), NO_PROGRESS(RoastKind.IDLE), EMPTY_DAY(RoastKind.PLAN_DAY);
    fun title(task: Task?, taskCount: Int = 0): String = when (this) {
        OVERDUE_TASK -> "The deadline escaped: ${task?.title.orEmpty()}"
        LEFT_WITHOUT_PROGRESS -> "One small step: ${task?.title.orEmpty()}"
        TINY_TASK -> "A quick win: ${task?.title.orEmpty()}"
        POSTPONED_TASK -> "That postponed task: ${task?.title.orEmpty()}"
        STILL_WORKING -> "Still working on: ${task?.title.orEmpty()}?"
        LOW_PRIORITY -> "All low priority? Start with: ${task?.title.orEmpty()}"
        SMALL_LIST -> "${if (taskCount == 1) "One task left" else "$taskCount tasks"}. Your move: ${task?.title.orEmpty()}"
        NO_PROGRESS -> "Make a little progress: ${task?.title.orEmpty()}"
        EMPTY_DAY -> "Your list is playing hide-and-seek. Add one task?"
    }
}
data class AwayNudge(val at: Long, val scenario: AwayScenario, val task: Task? = null, val taskCount: Int = 0) {
    val title get() = scenario.title(task, taskCount)
}

/** Only Bite's task actions and lifecycle timestamps are used; other apps aren't observed. */
object AwayPolicy {
    const val DEFAULT_GAP_MINUTES = 30
    const val MIN_GAP_MINUTES = 15
    const val MAX_GAP_MINUTES = 1440
    fun gapMillis(minutes: Int) = minutes.coerceIn(MIN_GAP_MINUTES, MAX_GAP_MINUTES) * 60_000L

    fun next(tasks: List<Task>, state: AwayState, now: Long, activeToday: Boolean,
        zone: ZoneId = ZoneId.systemDefault()): AwayNudge? {
        if (state.since <= 0 || state.since > now || (state.quietEnabled && state.quietStart == state.quietEnd)) return null
        val pending = tasks.filter { !it.archived && it.completedAt == null }
        val gap = gapMillis(state.gapMinutes)
        fun local(at: Long) = Instant.ofEpochMilli(at).atZone(zone)
        fun allowed(base: Long): Long? {
            var at = maxOf(now, base, state.lastReminder + gap, state.lastAt + gap, state.pauseUntil)
            // Deadlines take priority. Their cooldown uses a separate timestamp, so extra
            // check-ins never move a due reminder or silently impose a 30-minute minimum.
            repeat(96) {
                val time = local(at)
                val imminent = pending.asSequence().map { maxOf(now, it.dueAt, it.remindAfter,
                    state.lastReminder + ReminderPolicy.GLOBAL_COOLDOWN) }
                    .filter { it in at until (at + gap) }.minOrNull()
                at = when {
                    state.quietEnabled && ReminderPolicy.inQuietHours(time.hour, state.quietStart, state.quietEnd) -> {
                        val date = if (state.quietStart > state.quietEnd && time.hour >= state.quietStart)
                            time.toLocalDate().plusDays(1) else time.toLocalDate()
                        date.atTime(state.quietEnd, 0).atZone(zone).toInstant().toEpochMilli()
                    }
                    imminent != null -> imminent + gap
                    else -> return at
                }
            }
            return null
        }
        val candidates = mutableListOf<AwayNudge>()
        for (task in pending) {
            val dueDay = local(task.dueAt).toLocalDate()
            val idleAt = maxOf(maxOf(state.since, state.lastActivity, task.updatedAt) + gap,
                dueDay.atStartOfDay(zone).toInstant().toEpochMilli(),
                task.startedAt?.let { it + task.minutes * 60_000L + gap } ?: 0)
            val at = allowed(idleAt) ?: continue
            val relevant = pending.filter { local(it.dueAt).toLocalDate() <= local(at).toLocalDate() }
            if (dueDay > local(at).toLocalDate()) continue
            val scenario = when {
                task.dueAt <= at -> AwayScenario.OVERDUE_TASK
                task.startedAt != null -> AwayScenario.STILL_WORKING
                task.snoozeCount >= 3 -> AwayScenario.POSTPONED_TASK
                task.minutes < 5 -> AwayScenario.TINY_TASK
                state.abandonedAt >= state.since && state.abandonedAt > state.handledAbandon &&
                    state.lastActivity <= state.abandonedAt && at - state.abandonedAt <= gap * 2 -> AwayScenario.LEFT_WITHOUT_PROGRESS
                relevant.all { it.priority == 0 } -> AwayScenario.LOW_PRIORITY
                relevant.size in 1..3 -> AwayScenario.SMALL_LIST
                else -> AwayScenario.NO_PROGRESS
            }
            candidates += AwayNudge(at, scenario, task, relevant.size)
        }
        // Finishing or deliberately clearing today's tasks earns a rest, not an empty-list roast.
        // This does not require the once-daily planning prompt to be enabled or delivered first.
        if (pending.isEmpty() && !activeToday) {
            allowed(maxOf(state.since, state.lastActivity) + gap)?.let {
                candidates += AwayNudge(it, AwayScenario.EMPTY_DAY)
            }
        }
        return candidates.minWithOrNull(compareBy<AwayNudge> { it.at }.thenBy { it.scenario.ordinal }
            .thenByDescending { it.task?.priority ?: 0 }.thenBy { it.task?.dueAt ?: 0 })
    }
}

object AwayNudges {
    fun state(p: Preferences) = AwayState(since = p.backgroundSince, lastActivity = p.lastTaskActivity,
        lastReminder = p.lastReminder, abandonedAt = p.abandonedAt, handledAbandon = p.handledAwayAbandon,
        lastAt = p.lastAwayNudge, gapMinutes = p.awayGapMinutes, quietEnabled = p.quietEnabled,
        quietStart = p.quietStart, quietEnd = p.quietEnd, pauseUntil = p.teasingPausedUntil)

    suspend fun next(app: TaskBiteApp, now: Long = System.currentTimeMillis()): AwayNudge? {
        val p = app.preferences
        if (app.foreground || !p.onboarded || !p.reminders || !p.awayNudges) return null
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return app.database.withTransaction {
            AwayPolicy.next(app.repository.dao.pending(), state(p), now,
                p.lastTaskActivity in start until end || app.repository.dao.hasActivityBetween(start, end), zone)
        }
    }
    /** Called under ReminderDelivery's shared mutex; only counts an eligible notification attempt. */
    suspend fun claim(app: TaskBiteApp, now: Long): AwayNudge? = app.database.withTransaction {
        val nudge = next(app, now)?.takeIf { it.at <= now } ?: return@withTransaction null
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        app.preferences.recordAway(now, day, nudge.scenario)
        nudge
    }
}
