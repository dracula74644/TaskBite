package com.dracula.taskbite

import androidx.room.withTransaction
import java.time.Instant
import java.time.ZoneId

/** No fabricated task records: the daily prompt is tracked separately from task history. */
object PlanningNudges {
    suspend fun nextAt(app: TaskBiteApp, now: Long = System.currentTimeMillis()): Long? {
        val prefs = app.preferences
        if (!prefs.onboarded || !prefs.planningNudge ||
            (prefs.quietEnabled && prefs.quietStart == prefs.quietEnd)) return null
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val activeToday = app.database.withTransaction {
            if (app.repository.dao.pending().isNotEmpty()) return@withTransaction null
            prefs.lastTaskActivity in start until end || app.repository.dao.hasActivityBetween(start, end)
        } ?: return null
        val alreadyPrompted = prefs.lastPlanningDay == today.toString()
        fun afterQuiet(at: Long): Long {
            val local = Instant.ofEpochMilli(at).atZone(zone)
            if (!prefs.isQuiet(local.hour)) return at
            var date = local.toLocalDate()
            if (prefs.quietStart > prefs.quietEnd && local.hour >= prefs.quietStart) date = date.plusDays(1)
            return date.atTime(prefs.quietEnd, 0).atZone(zone).toInstant().toEpochMilli()
        }
        val date = if (activeToday || alreadyPrompted) today.plusDays(1) else today
        // A time inside overnight quiet hours is deferred to morning, once per local day.
        val deferred = afterQuiet(date.minusDays(1).atTime(prefs.planningHour, prefs.planningMinute).atZone(zone).toInstant().toEpochMilli())
        val planned = if (Instant.ofEpochMilli(deferred).atZone(zone).toLocalDate() == date) deferred
            else afterQuiet(date.atTime(prefs.planningHour, prefs.planningMinute).atZone(zone).toInstant().toEpochMilli())
        return afterQuiet(maxOf(now, planned, prefs.teasingPausedUntil, prefs.lastReminder + ReminderPolicy.GLOBAL_COOLDOWN,
            prefs.lastAwayNudge + minOf(ReminderPolicy.GLOBAL_COOLDOWN, AwayPolicy.gapMillis(prefs.awayGapMinutes))))
    }

    /** Caller holds ReminderDelivery's mutex, shared by foreground and background delivery. */
    suspend fun claim(app: TaskBiteApp, now: Long): Boolean = app.database.withTransaction {
        val at = nextAt(app, now) ?: return@withTransaction false
        if (at > now) return@withTransaction false
        val prefs = app.preferences
        prefs.lastPlanningDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        prefs.lastPlanningAt = now
        prefs.lastReminder = now
        true
    }
}
