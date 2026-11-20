package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class AwayNudgeTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val p get() = app.preferences
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun at(h: Int, m: Int = 0, offset: Long = 0) = LocalDate.now().plusDays(offset).atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun task(minutes: Int = 15) = Task(title = "Today's small task", dueAt = at(18), minutes = minutes, createdAt = at(8), updatedAt = at(8))
    @Before fun setup() {
        app.foreground = false
        p.apply { onboarded = true; reminders = true; awayNudges = true; notificationReactions = false; quietEnabled = false; backgroundSince = at(9); lastTaskActivity = at(8) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @Test fun recurringCheckInsUseMatchingCaptionsAndNeverMutateTasksOrHistory() = runBlocking {
        val item = task(2); app.repository.dao.put(item)
        assertEquals(at(9, 30), Reminders.nextAt(app, at(9)))
        repeat(8) { index ->
            val now = at(9) + (index + 1) * 30 * 60_000L
            ReminderDelivery.deliver(app, false, false, now)
            val notification = manager.activeNotifications.single { it.tag == "away_task" }.notification
            assertEquals("A quick win: ${item.title}", notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.extras.getString(Notification.EXTRA_TEXT) in VoiceLibrary(app).bundled(RoastKind.TINY).map { it.caption })
            assertEquals(item, app.repository.dao.task(item.id)); assertTrue(app.repository.dao.allEvents().isEmpty())
            assertEquals(index + 1, Preferences(app).awayCount)
            assertEquals(now + 30 * 60_000L, Reminders.nextAt(app, now))
        }
        assertEquals(0L, p.lastReminder) // Extra nudges cannot consume the deadline cooldown.
    }
    @Test fun fifteenMinuteSettingIsRespectedBySchedulerAndDelivery() = runBlocking {
        p.awayGapMinutes = 15
        p.lastReminder = at(9)
        app.repository.dao.put(task())
        assertEquals(at(9, 15), Reminders.nextAt(app, at(9)))
        ReminderDelivery.deliver(app, false, false, at(9, 15))
        assertEquals(1, p.awayCount)
        assertEquals(at(9, 30), Reminders.nextAt(app, at(9, 15)))
        ReminderDelivery.deliver(app, false, false, at(9, 30))
        assertEquals(2, p.awayCount)
        p.awayGapMinutes = 47
        assertEquals(47, Preferences(app).awayGapMinutes)
        assertEquals(at(10, 17), Reminders.nextAt(app, at(9, 30)))
    }
    @Test fun simultaneousWorkersUseOnePersistentClaim() = runBlocking {
        app.repository.dao.put(task())
        (1..6).map { async(Dispatchers.Default) { ReminderDelivery.deliver(app, false, false, at(9, 30)) } }.awaitAll()
        assertEquals(1, p.awayCount)
        assertEquals(1, manager.activeNotifications.count { it.tag == "away_task" })
    }
    @Test fun deadlinesWinAndKeepTheirRepeatTimeDespiteFrequentExtraNudges() = runBlocking {
        val overdue = task().copy(dueAt = at(10), remindAfter = at(10))
        val later = task(2)
        app.repository.dao.put(overdue); app.repository.dao.put(later)
        ReminderDelivery.deliver(app, false, false, at(11))
        assertEquals(0, p.awayCount)
        assertEquals(at(15), app.repository.dao.task(overdue.id)!!.remindAfter)
        repeat(7) { ReminderDelivery.deliver(app, false, false, at(11, 30) + it * 30 * 60_000L) }
        assertEquals(7, p.awayCount)
        assertEquals(later, app.repository.dao.task(later.id))
        assertEquals(at(15), Reminders.nextAt(app, at(14, 30)))
        ReminderDelivery.deliver(app, false, false, at(15))
        assertEquals(7, p.awayCount)
        assertEquals(at(19), app.repository.dao.task(overdue.id)!!.remindAfter)
        assertEquals(at(15, 30), Reminders.nextAt(app, at(15)))
    }
    @Test fun returningOptingOutQuietHoursAndDeniedNotificationsSuppressExtraNudges() = runBlocking {
        app.repository.dao.put(task())
        app.foreground = true; ReminderDelivery.deliver(app, false, false, at(11))
        app.foreground = false; p.awayNudges = false; ReminderDelivery.deliver(app, false, false, at(11))
        p.awayNudges = true; p.quietEnabled = true; p.quietStart = 10; p.quietEnd = 14
        ReminderDelivery.deliver(app, false, false, at(11))
        p.quietEnabled = false; shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderDelivery.deliver(app, false, false, at(11))
        assertEquals(0, p.awayCount); assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun emptyDayRepeatsWithPlanningOffAndLegacyDailyLimitDoesNotBlockIt() = runBlocking {
        p.lastTaskActivity = at(8, offset = -1); p.planningNudge = false
        p.awayCountDay = LocalDate.now().toString(); p.awayCount = 2; p.awayFollowedDay = p.awayCountDay
        repeat(4) { ReminderDelivery.deliver(app, false, false, at(9, 30) + it * 30 * 60_000L) }
        val notice = manager.activeNotifications.single { it.tag == "away_plan" }.notification
        assertEquals(AwayScenario.EMPTY_DAY.title(null), notice.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Add a task", notice.actions.single().title)
        assertEquals(0L, p.lastPlanningAt)
        assertEquals(6, p.awayCount)
        assertTrue(app.repository.dao.allTasks().isEmpty()); assertTrue(app.repository.dao.allEvents().isEmpty())
    }
    @Test fun situationIsRecomputedWhenCountOrPriorityChanges() = runBlocking {
        val items = (1..3).map { task().copy(title = "Task $it") }
        items.forEach { app.repository.dao.put(it) }
        ReminderDelivery.deliver(app, false, false, at(9, 30))
        fun title() = manager.activeNotifications.single { it.tag == "away_task" }.notification.extras.getString(Notification.EXTRA_TITLE)!!
        assertTrue(title().startsWith("3 tasks."))
        items.forEach { app.repository.dao.put(it.copy(priority = 0, updatedAt = at(9, 40))) }
        p.lastTaskActivity = at(9, 40)
        assertEquals(at(10, 10), Reminders.nextAt(app, at(9, 40)))
        ReminderDelivery.deliver(app, false, false, at(10, 10))
        assertTrue(title().startsWith("All low priority?"))
    }
    @Test fun taskActionClearsTheNotificationAndResetsInactivity() = runBlocking {
        val item = task(); app.repository.dao.put(item)
        ReminderDelivery.deliver(app, false, false, at(11))
        assertEquals(1, manager.activeNotifications.size)
        app.repository.start(item.id)
        assertTrue(p.lastTaskActivity > at(8))
        assertTrue(manager.activeNotifications.none { it.tag == "away_task" })
        assertEquals(1, p.awayCount)
    }
    @Test fun exitWorkerStillGivesAnEligibleDeadlinePriority() = runBlocking {
        val overdue = task().copy(dueAt = at(10), remindAfter = at(10))
        app.repository.dao.put(overdue); app.repository.dao.put(task(2)); p.abandonedAt = at(9)
        ReminderDelivery.deliver(app, true, false, at(11))
        assertEquals(0, p.awayCount)
        assertTrue(manager.activeNotifications.any { it.tag == overdue.id })
        assertTrue(manager.activeNotifications.none { it.tag == "away_task" })
    }
    @Test fun preferenceBoundsAndCustomValuesSurviveReload() {
        assertEquals(30, Preferences(app).awayGapMinutes)
        p.awayGapMinutes = 47; assertEquals(47, Preferences(app).awayGapMinutes)
        p.awayGapMinutes = 0; assertEquals(15, Preferences(app).awayGapMinutes)
        p.awayGapMinutes = 9000; assertEquals(1440, Preferences(app).awayGapMinutes)
    }
    @Test fun spokenAlarmUsesFifteenMinuteGapAndReschedulesWhenChanged() = runBlocking {
        p.awayGapMinutes = 15; p.backgroundVoice = true; p.lastReminder = at(9)
        app.repository.dao.put(task())
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val alarms = shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
        fun spoken() = alarms.scheduledAlarms.filter { it.operation != null &&
            shadowOf(it.operation).savedIntent.component?.className == SpokenAlarmReceiver::class.java.name }
        SpokenAlarms.schedule(app, at(9))
        assertEquals(at(9, 15), spoken().single().triggerAtMs)
        ReminderDelivery.deliver(app, false, false, at(9, 15))
        SpokenAlarms.schedule(app, at(9, 15))
        assertEquals(at(9, 15) + 5_000, spoken().single().triggerAtMs) // Text already posted; queued voice gets its own alarm.
        ReminderAudio.clear(app)
        SpokenAlarms.schedule(app, at(9, 15))
        assertEquals(at(9, 30), spoken().single().triggerAtMs)
        p.awayGapMinutes = 47
        SpokenAlarms.schedule(app, at(9, 15))
        assertEquals(at(10, 2), spoken().single().triggerAtMs)
        p.awayNudges = false
        SpokenAlarms.schedule(app, at(9, 15))
        assertEquals(at(18), spoken().single().triggerAtMs) // Only the deadline remains.
    }
}
