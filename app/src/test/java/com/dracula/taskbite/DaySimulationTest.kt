package com.dracula.taskbite

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DaySimulationTest {
    private val date = LocalDate.of(2026, 11, 18)
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun jump(index: Int) = DaySimulation.replay(date, zone, listOf("jump:$index"))
    private fun time(hour: Int, minute: Int = 0, nextDay: Boolean = false) =
        date.plusDays(if (nextDay) 1 else 0).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test fun guidedDayCoversEveryReactionAndSilentEvents() {
        val simulation = DaySimulation(date, zone)
        val kinds = mutableSetOf<RoastKind>()
        val expected = mapOf(0 to RoastKind.PLAN_DAY, 1 to RoastKind.CREATED, 2 to RoastKind.STARTED,
            3 to RoastKind.COMPLETED, 5 to RoastKind.OVERDUE, 6 to RoastKind.COMPLETED_LATE,
            8 to RoastKind.SNOOZE, 10 to RoastKind.SNOOZE_REPEAT, 12 to RoastKind.IDLE,
            14 to RoastKind.TINY, 17 to RoastKind.OVERDUE, 18 to RoastKind.ABANDON)
        DaySimulation.scenes.indices.forEach { index ->
            val previous = simulation.now
            simulation.next()
            assertTrue(simulation.now >= previous)
            expected[index]?.let { assertEquals("Scene $index: ${simulation.event}", it, simulation.event.kind) }
            simulation.log.mapNotNullTo(kinds) { it.kind }
            if (DaySimulation.scenes[index].action in listOf("widget", "quiet", "recurring")) assertFalse("Silent scene $index", simulation.event.audible)
        }
        assertEquals(RoastKind.entries.toSet() - setOf(RoastKind.STILL_WORKING, RoastKind.LOW_PRIORITY, RoastKind.SMALL_LIST), kinds)
        assertTrue(simulation.finished)
        assertEquals(time(9, nextDay = true), simulation.now)
        assertTrue(simulation.clockFinished)
    }
    @Test fun clearingPreviewLeavesSampleTasksAndHistoryUnchanged() {
        val index = DaySimulation.scenes.indexOfFirst { it.action == "clear_notice" }
        val simulation = jump(index - 1)
        val tasks = simulation.tasks
        val history = simulation.history
        simulation.next()
        assertEquals(RoastKind.NOTIFICATION_CLEARED, simulation.event.kind)
        assertEquals(tasks, simulation.tasks)
        assertEquals(history, simulation.history)
    }

    @Test fun fastForwardFiresEmptyDayOnceWithoutCreatingTasksOrHistory() {
        val simulation = DaySimulation(date, zone)
        simulation.advance(5 * 60_000)
        assertEquals(time(9), simulation.now)
        assertEquals(RoastKind.PLAN_DAY, simulation.event.kind)
        simulation.advance(60 * 60_000)
        assertEquals(time(10), simulation.now)
        assertEquals(1, simulation.log.count { it.kind == RoastKind.PLAN_DAY })
        assertTrue(simulation.tasks.isEmpty())
        assertTrue(simulation.history.isEmpty())
        simulation.advance(24 * 60 * 60_000)
        assertTrue(simulation.clockFinished)
        assertEquals(2, simulation.log.count { it.kind == RoastKind.PLAN_DAY })
        val end = simulation.now
        simulation.advance(60 * 60_000)
        assertEquals(end, simulation.now)
    }

    @Test fun overdueReminderUsesRealTaskCooldownAndUndoExpiresOnVirtualTime() {
        val simulation = jump(5)
        val task = simulation.tasks.single { it.id == "email" }
        assertEquals(time(10) + ReminderPolicy.TASK_COOLDOWN, task.remindAfter)
        simulation.advance(60 * 60_000)
        assertEquals(1, simulation.log.count { it.title == "Sample task is due" })
        val postponed = jump(8)
        assertTrue(postponed.canUndo)
        val before = postponed.tasks
        postponed.advance(5 * 60_000)
        assertFalse(postponed.canUndo)
        postponed.undoAction()
        assertEquals(before, postponed.tasks)
        assertEquals("Undo unavailable", postponed.event.title)
    }

    @Test fun completionUndoRemovesNextOccurrenceAndDeletionUndoRestoresHistory() {
        val simulation = jump(19)
        val beforeTasks = simulation.tasks
        val beforeHistory = simulation.history
        simulation.next()
        assertNotNull(simulation.tasks.single { it.id == "stretch" }.completedAt)
        val next = simulation.tasks.single { it.id == "stretch-next" }
        assertEquals(time(12, 30, nextDay = true), next.dueAt)
        simulation.next()
        assertEquals(beforeTasks, simulation.tasks)
        assertEquals(beforeHistory, simulation.history)
        simulation.next()
        assertTrue(simulation.tasks.none { it.id == "read" })
        assertTrue(simulation.history.none { it.taskId == "read" })
        assertTrue(simulation.canUndo)
        simulation.next()
        assertEquals(beforeTasks, simulation.tasks)
        assertEquals(beforeHistory, simulation.history)
    }

    @Test fun quietHoursSuppressReactionsAndNextMorningResumesPendingWork() {
        val simulation = jump(DaySimulation.scenes.indexOfFirst { it.action == "quiet" })
        assertTrue(simulation.quiet)
        assertFalse(simulation.event.audible)
        simulation.advance(9 * 60 * 60_000)
        assertTrue(simulation.quiet)
        assertFalse(simulation.event.audible)
        assertTrue(simulation.log.none { event ->
            val hour = java.time.Instant.ofEpochMilli(event.at).atZone(zone).hour
            event.audible && (hour >= 22 || hour < 8)
        })
        simulation.advance(2 * 60 * 60_000)
        assertFalse(simulation.quiet)
        assertTrue(simulation.event.audible)
        assertNotEquals(RoastKind.PLAN_DAY, simulation.event.kind)
    }

    @Test fun replayIsDeterministicAndAdvancedClockNeverRewindsForQueuedActions() {
        val commands = listOf("jump:19", "next", "undo", "+1h", "next")
        val first = DaySimulation.replay(date, zone, commands)
        val second = DaySimulation.replay(date, zone, commands)
        assertEquals(first.tasks, second.tasks)
        assertEquals(first.history, second.history)
        assertEquals(first.log, second.log)
        assertEquals(first.now, second.now)
        assertEquals(time(13, 15), first.now)
        val fresh = DaySimulation.replay(date, zone, emptyList())
        assertTrue(fresh.tasks.isEmpty())
        assertEquals(time(8, 55), fresh.now)
    }
    @Test fun emptyDayCanBeLeftAndFastForwardedToRepeatedNudgesWithoutRealTasks() {
        val simulation = DaySimulation.replay(date, zone, listOf("next", "leave", "+1h", "+1h", "+1h"))
        assertFalse(simulation.foreground)
        assertEquals(AwayScenario.EMPTY_DAY.title(null), simulation.event.title)
        assertEquals(RoastKind.PLAN_DAY, simulation.event.kind)
        assertTrue(simulation.tasks.isEmpty()); assertTrue(simulation.history.isEmpty())
        simulation.advance(4 * 60 * 60_000)
        assertEquals(14, simulation.log.count { it.title == AwayScenario.EMPTY_DAY.title(null) })
    }
    @Test fun outsideCheckInsKeepDeadlinesAndHaveNoDailyCap() {
        val simulation = jump(16)
        val before = simulation.tasks.single { it.id == "read" }
        simulation.next()
        assertEquals(RoastKind.OVERDUE, simulation.event.kind)
        assertEquals(before, simulation.tasks.single { it.id == "read" })
        simulation.next()
        assertEquals(RoastKind.ABANDON, simulation.event.kind)
        while (!simulation.finished) simulation.next()
        assertTrue(simulation.log.count { it.detail.startsWith("Away check-in") && it.at < time(0, nextDay = true) } > 2)
    }
    @Test fun focusedScenariosUseTheActualPolicyAndChosenGap() {
        for ((preset, scenario) in listOf("empty" to AwayScenario.EMPTY_DAY, "small" to AwayScenario.SMALL_LIST, "low" to AwayScenario.LOW_PRIORITY)) {
            val simulation = DaySimulation.replay(date, zone, listOf("preset:$preset"), gapMinutes = 15)
            assertTrue(simulation.standalone); assertFalse(simulation.foreground)
            val before = simulation.tasks
            simulation.advance(15 * 60_000)
            val title = if (preset == "empty") scenario.title(null) else scenario.title(before.first(), 3)
            assertEquals(title, simulation.event.title)
            assertEquals(1, simulation.log.count { it.detail.startsWith("Away check-in") })
            simulation.advance(45 * 60_000)
            assertEquals(4, simulation.log.count { it.detail.startsWith("Away check-in") })
            assertEquals(before, simulation.tasks)
            assertTrue(simulation.history.isEmpty())
        }
    }
}
