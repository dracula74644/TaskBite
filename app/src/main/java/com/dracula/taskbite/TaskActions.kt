package com.dracula.taskbite

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class RepeatRule(val label: String) { NONE("Does not repeat"), DAILY("Every day"), WEEKDAYS("Weekdays"), WEEKLY("Every week") }

object Recurrence {
    fun next(dueAt: Long, rule: RepeatRule, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        require(rule != RepeatRule.NONE)
        val original = Instant.ofEpochMilli(dueAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val elapsedDays = ChronoUnit.DAYS.between(original.toLocalDate(), today)
        var candidate = if (rule == RepeatRule.WEEKLY) original.plusWeeks(maxOf(1, elapsedDays / 7))
            else original.plusDays(maxOf(1, elapsedDays))
        while (candidate.toInstant().toEpochMilli() <= now ||
            (rule == RepeatRule.WEEKDAYS && candidate.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))) {
            candidate = if (rule == RepeatRule.WEEKLY) candidate.plusWeeks(1) else candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }
}

enum class TaskAction { COMPLETE, SNOOZE, LATER, DELETE }
class TaskChangeRejected(message: String) : IllegalStateException(message)

/** A short-lived capability to reverse exactly one saved action, never a newer edit. */
class TaskUndo internal constructor(
    val action: TaskAction,
    val before: Task,
    internal val after: Task?,
    internal val oldEvents: List<TaskEvent>,
    internal val addedEventIds: Set<String>,
    internal val spawned: Task?,
    internal val deletion: TaskDeletion?,
    internal val expiresAt: Long,
) {
    val affectedIds: List<String> get() = listOfNotNull(before.id, spawned?.id)
}
