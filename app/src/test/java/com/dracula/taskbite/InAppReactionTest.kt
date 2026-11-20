package com.dracula.taskbite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class InAppReactionTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    @Test fun overdueCanReactInForegroundWithoutAlarmOrNotificationAccessAndClaimsOnlyOnce() = runBlocking {
        val now = System.currentTimeMillis()
        app.foreground = true
        app.preferences.apply { onboarded = true; actionVoice = true; reminders = false; backgroundVoice = false; quietEnabled = false }
        val task = Task(title = "Overdue", dueAt = now - 1000)
        app.repository.save(task)
        assertEquals(RoastKind.OVERDUE, ReminderDelivery.claimInApp(app, now))
        assertNull(ReminderDelivery.claimInApp(app, now + 1000))
        assertEquals(now + ReminderPolicy.TASK_COOLDOWN, app.repository.dao.task(task.id)!!.remindAfter)
        assertEquals(1, app.repository.dao.allEvents().size) // No fabricated task action.
    }
    @Test fun disabledQuietAndBackgroundStatesDoNotConsumeDueReaction() = runBlocking {
        val now = System.currentTimeMillis()
        app.preferences.apply { onboarded = true; actionVoice = false; quietEnabled = false }
        app.foreground = true
        val task = Task(title = "Later", dueAt = now - 1000, minutes = 3)
        app.repository.save(task)
        assertNull(ReminderDelivery.claimInApp(app, now))
        app.preferences.actionVoice = true
        app.preferences.apply { quietEnabled = true; quietStart = 8; quietEnd = 8 }
        assertNull(ReminderDelivery.claimInApp(app, now))
        app.preferences.quietEnabled = false
        app.foreground = false
        assertNull(ReminderDelivery.claimInApp(app, now))
        assertEquals(task.remindAfter, app.repository.dao.task(task.id)!!.remindAfter)
        app.foreground = true
        assertEquals(RoastKind.TINY, ReminderDelivery.claimInApp(app, now))
    }
    @Test fun startingSomethingDoesNotDisableLaterIdleAndFutureTasksAreExcluded() {
        val now = System.currentTimeMillis()
        val task = Task(title = "Started", dueAt = now, startedAt = now - 400_000)
        assertEquals(RoastKind.IDLE, InAppReactionPolicy.idleKind(listOf(task), now))
        assertEquals(RoastKind.TINY, InAppReactionPolicy.idleKind(listOf(task.copy(minutes = 3)), now))
        assertNull(InAppReactionPolicy.idleKind(listOf(task.copy(dueAt = now + 7 * 86_400_000)), now))
        assertFalse(InAppReactionPolicy.canNudge(now, now - 1000))
        assertTrue(InAppReactionPolicy.canNudge(now, now - InAppReactionPolicy.NUDGE_COOLDOWN))
    }
}
