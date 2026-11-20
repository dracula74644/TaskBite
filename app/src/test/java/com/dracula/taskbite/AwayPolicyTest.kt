package com.dracula.taskbite

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AwayPolicyTest {
    private val day = LocalDate.of(2026, 11, 19)
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int = 0, offset: Long = 0) = day.plusDays(offset).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun task(id: String = "sample", minutes: Int = 15) = Task(id = id, title = id, dueAt = at(18),
        minutes = minutes, createdAt = at(8), updatedAt = at(8))
    private val away get() = AwayState(since = at(9), lastActivity = at(8))
    private fun next(tasks: List<Task>, state: AwayState = away, now: Long = at(9), active: Boolean = true) =
        AwayPolicy.next(tasks, state, now, active, zone)

    @Test fun repeatsEveryThirtyMinutesWithoutADailyCap() {
        val item = task()
        var state = away
        var time = at(9)
        repeat(8) {
            val nudge = next(listOf(item), state, time)!!
            assertEquals(time + 30 * 60_000L, nudge.at)
            state = state.copy(lastAt = nudge.at)
            time = nudge.at
        }
        assertEquals(at(13), time)
        assertEquals(at(18), item.dueAt); assertEquals(at(18), item.remindAfter)
    }
    @Test fun customGapControlsFirstAndRepeatNudgesIncludingFifteenMinutes() {
        listOf(15, 30, 47, 120).forEach { minutes ->
            val state = away.copy(gapMinutes = minutes, lastReminder = at(9))
            val first = next(listOf(task()), state)!!
            assertEquals(at(9) + minutes * 60_000L, first.at)
            assertEquals(first.at + minutes * 60_000L, next(listOf(task()), state.copy(lastAt = first.at), first.at)!!.at)
        }
    }
    @Test fun smallListsAndLowPriorityOnlyListsHaveDistinctSituations() {
        val three = (1..3).map { task("Task $it") }
        val small = next(three)!!
        assertEquals(AwayScenario.SMALL_LIST, small.scenario); assertEquals(3, small.taskCount)
        assertTrue(small.title.startsWith("3 tasks."))
        assertEquals(AwayScenario.LOW_PRIORITY, next(three.map { it.copy(priority = 0) })!!.scenario)
        val high = three.map { it.copy(priority = 0) }.toMutableList()
        high[2] = high[2].copy(priority = 2)
        assertEquals(high[2].id, next(high)!!.task!!.id)
        assertEquals(AwayScenario.SMALL_LIST, next(high)!!.scenario)
        assertEquals(AwayScenario.NO_PROGRESS, next(three + task("Fourth"))!!.scenario)
    }
    @Test fun overdueStartedPostponedAndTinyTasksKeepTheirOwnSituations() {
        val normal = task()
        assertEquals(AwayScenario.TINY_TASK, next(listOf(normal, task("tiny", 2)))!!.scenario)
        assertEquals(AwayScenario.POSTPONED_TASK, next(listOf(normal.copy(snoozeCount = 3)))!!.scenario)
        assertEquals(AwayScenario.STILL_WORKING, next(listOf(normal.copy(startedAt = at(8))))!!.scenario)
        val overdue = normal.copy(dueAt = at(8), remindAfter = at(12))
        assertEquals(AwayScenario.OVERDUE_TASK, next(listOf(overdue))!!.scenario)
        assertEquals(at(12), overdue.remindAfter)
    }
    @Test fun startedWorkGetsItsEstimatePlusTheSelectedGap() {
        val started = task().copy(startedAt = at(9), minutes = 180)
        assertEquals(at(12, 30), next(listOf(started))!!.at)
        assertEquals(at(12, 15), next(listOf(started), away.copy(gapMinutes = 15))!!.at)
        assertEquals(at(9, 45), next(listOf(started.copy(minutes = 15)))!!.at)
    }
    @Test fun exitNudgeUsesChosenGapAndIsNotRepeatedAsAnotherAbandonment() {
        val visit = away.copy(abandonedAt = at(9))
        assertEquals(AwayScenario.LEFT_WITHOUT_PROGRESS, next(listOf(task()), visit)!!.scenario)
        assertEquals(at(9, 30), next(listOf(task()), visit)!!.at)
        assertEquals(AwayScenario.SMALL_LIST, next(listOf(task()), visit.copy(handledAbandon = at(9)))!!.scenario)
        assertNull(next(listOf(task()), visit.copy(since = 0)))
        assertEquals(at(10, 30), next(listOf(task()), visit.copy(lastActivity = at(10)), at(10))!!.at)
    }
    @Test fun completedArchivedAndFutureTasksDoNotBecomeTodaysPendingNudges() {
        assertNull(next(listOf(task().copy(completedAt = at(10)), task("old").copy(archived = true))))
        val future = task().copy(dueAt = at(18, offset = 1), remindAfter = at(18, offset = 1))
        assertEquals(at(8, offset = 1), next(listOf(future))!!.at)
    }
    @Test fun quietHoursApplyButTheOldNineToTwentyWindowDoesNot() {
        val evening = task().copy(dueAt = at(23), remindAfter = at(23))
        assertEquals(at(21, 30), next(listOf(evening), away.copy(since = at(21)), at(21))!!.at)
        assertEquals(at(8, offset = 1), next(listOf(evening), away.copy(since = at(21, 45)), at(21, 45))!!.at)
        assertNull(next(listOf(task()), away.copy(quietStart = 8, quietEnd = 8)))
        assertEquals(at(10), next(listOf(task()), away.copy(quietStart = 9, quietEnd = 10))!!.at)
        val noQuiet = away.copy(since = at(21, 45), quietEnabled = false)
        assertEquals(at(22, 15), next(listOf(evening), noQuiet, at(21, 45))!!.at)
    }
    @Test fun emptyListRepeatsWithoutRequiringADailyPlanningPromptButFinishedDaysRest() {
        val first = next(emptyList(), active = false)!!
        assertEquals(AwayNudge(at(9, 30), AwayScenario.EMPTY_DAY), first)
        assertEquals(at(10), next(emptyList(), away.copy(lastAt = first.at), first.at, active = false)!!.at)
        assertNull(next(emptyList(), active = true))
        assertNull(next(emptyList(), away.copy(since = 0), active = false))
    }
    @Test fun taskActionsAndOtherRemindersResetTheGap() {
        assertEquals(at(10, 15), next(listOf(task()), away.copy(lastActivity = at(9, 45)), at(9, 45))!!.at)
        assertEquals(at(10, 15), next(listOf(task()), away.copy(lastReminder = at(9, 45)), at(9, 45))!!.at)
    }
    @Test fun imminentDeadlinesKeepTheirSlotEvenWithAFifteenMinuteGap() {
        val imminent = task("deadline").copy(dueAt = at(9, 20), remindAfter = at(9, 20))
        assertEquals(at(9, 35), next(listOf(imminent, task("tiny", 2)), away.copy(gapMinutes = 15))!!.at)
        assertEquals(at(9, 50), next(listOf(imminent.copy(remindAfter = at(13, 20)), task("tiny", 2)),
            away.copy(lastReminder = at(9, 20)), at(9, 20))!!.at)
    }
    @Test fun quietWindowSurvivesDaylightSavingJump() {
        val ny = ZoneId.of("America/New_York")
        val night = LocalDate.of(2026, 3, 7).atTime(21, 45).atZone(ny).toInstant().toEpochMilli()
        val nudge = AwayPolicy.next(emptyList(), AwayState(since = night), night, false, ny)!!
        assertEquals(LocalDate.of(2026, 3, 8).atTime(8, 0).atZone(ny).toInstant().toEpochMilli(), nudge.at)
    }
}
