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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class PlanningNudgeTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val prefs get() = app.preferences
    private val today get() = LocalDate.now()
    private fun time(hour: Int, minute: Int = 0, day: LocalDate = today) =
        day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        app.foreground = true
        prefs.apply { onboarded = true; planningNudge = true; quietEnabled = false; planningHour = 9 }
    }

    @Test fun waitsForChosenMinuteThenClaimsOnceAcrossRecreationWithoutFakeTasks() = runBlocking {
        prefs.planningMinute = 15
        assertEquals(time(9, 15), PlanningNudges.nextAt(app, time(9, 14)))
        assertNull(ReminderDelivery.claimPlanningInApp(app, time(9, 14)))
        assertEquals(RoastKind.PLAN_DAY, ReminderDelivery.claimPlanningInApp(app, time(9, 15)))
        assertEquals(today.toString(), Preferences(app).lastPlanningDay)
        assertEquals(15, Preferences(app).planningMinute)
        assertNull(ReminderDelivery.claimPlanningInApp(app, time(12)))
        assertEquals(time(9, 15, today.plusDays(1)), PlanningNudges.nextAt(app, time(12)))
        assertTrue(app.repository.dao.allTasks().isEmpty())
        assertTrue(app.repository.dao.allEvents().isEmpty())
    }

    @Test fun simultaneousForegroundAttemptsShareOneClaim() = runBlocking {
        val results = (1..6).map { async(Dispatchers.Default) { ReminderDelivery.claimPlanningInApp(app, time(10)) } }.awaitAll()
        assertEquals(1, results.count { it == RoastKind.PLAN_DAY })
    }

    @Test fun notificationAndForegroundShareTheDailyLimitAndOfferAdd() = runBlocking {
        prefs.apply { planningHour = 0; teasingStyle = TeasingStyle.SWEET }
        app.foreground = false
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderDelivery.deliver(app, abandon = false, allowVoice = false)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notice = manager.activeNotifications.single { it.tag == "plan_day" }.notification
        assertEquals("Give today a little plan", notice.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notice.extras.getString(Notification.EXTRA_TEXT) in Roasts.sweetLines.getValue(RoastKind.PLAN_DAY))
        assertEquals("Add a task", notice.actions.single().title)
        assertEquals(WidgetNavigation.ADD, shadowOf(notice.contentIntent).savedIntent.action)
        app.foreground = true
        assertNull(ReminderDelivery.claimPlanningInApp(app))
        assertTrue(app.repository.dao.allTasks().isEmpty())
    }

    @Test fun deniedNotificationsDoNotUseUpTheInAppNudge() = runBlocking {
        prefs.planningHour = 0
        app.foreground = false
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ReminderDelivery.deliver(app, abandon = false, allowVoice = false)
        assertEquals("", prefs.lastPlanningDay)
        app.foreground = true
        prefs.apply { actionVoice = false; reminders = false }
        assertEquals(RoastKind.PLAN_DAY, ReminderDelivery.claimPlanningInApp(app))
    }
    @Test fun alarmWhileSettingsAreOpenIsClaimedInsteadOfReschedulingEveryFiveSeconds() = runBlocking {
        prefs.planningHour = 0
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.foreground = true
        ReminderDelivery.deliver(app, abandon = false, allowVoice = false, now = time(10))
        assertEquals(today.toString(), prefs.lastPlanningDay)
        assertEquals(time(0, day = today.plusDays(1)), PlanningNudges.nextAt(app, time(10)))
    }

    @Test fun existingFutureTaskAndWorkAlreadyDoneTodaySuppressTheNudge() = runBlocking {
        val old = time(10, day = today.minusDays(1))
        val task = Task(title = "Already planned", dueAt = time(10, day = today.plusDays(2)), createdAt = old)
        app.repository.dao.put(task)
        assertNull(PlanningNudges.nextAt(app, time(10)))
        app.repository.dao.put(task.copy(completedAt = time(8)))
        assertEquals(time(9, day = today.plusDays(1)), PlanningNudges.nextAt(app, time(10)))
        app.repository.dao.put(task.copy(completedAt = old))
        assertEquals(time(10), PlanningNudges.nextAt(app, time(10)))
    }

    @Test fun deletingTodaysLastTaskDoesNotEraseTheFactThatYouAlreadyPlanned() = runBlocking {
        val now = System.currentTimeMillis()
        prefs.planningHour = 0
        val task = Task(title = "Changed my plans", dueAt = now + 3600000)
        app.repository.save(task)
        app.repository.delete(task.id)
        assertTrue(app.repository.dao.allTasks().isEmpty())
        assertTrue(app.repository.dao.allEvents().isEmpty())
        assertNull(ReminderDelivery.claimPlanningInApp(app, now + 1000))
        assertTrue(prefs.lastTaskActivity > 0)
    }

    @Test fun quietHoursDisabledFeatureAndOnboardingAreRespected() = runBlocking {
        prefs.apply { quietEnabled = true; quietStart = 8; quietEnd = 10; planningMinute = 30 }
        assertEquals(time(10), PlanningNudges.nextAt(app, time(9)))
        prefs.apply { quietStart = 22; quietEnd = 8; planningHour = 23; planningMinute = 0 }
        assertEquals(time(8), PlanningNudges.nextAt(app, time(7)))
        prefs.quietEnd = 22
        assertNull(PlanningNudges.nextAt(app, time(12)))
        prefs.quietEnabled = false
        prefs.planningNudge = false
        assertNull(PlanningNudges.nextAt(app, time(12)))
        prefs.apply { planningNudge = true; onboarded = false }
        assertNull(PlanningNudges.nextAt(app, time(12)))
    }

    @Test fun sharedReminderAndInteractionCooldownsDelayInsteadOfConsuming() = runBlocking {
        prefs.lastReminder = time(9)
        assertEquals(time(9, 30), PlanningNudges.nextAt(app, time(9, 10)))
        prefs.lastForegroundReaction = time(9, 29)
        assertNull(ReminderDelivery.claimPlanningInApp(app, time(9, 30)))
        assertEquals("", prefs.lastPlanningDay)
        assertEquals(RoastKind.PLAN_DAY, ReminderDelivery.claimPlanningInApp(app, time(9, 35)))
    }

    @Test fun nextLocalMorningSurvivesDaylightSavingChange() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val before = LocalDate.of(2026, 3, 7)
            val now = time(9, day = before)
            prefs.lastPlanningDay = before.toString()
            val next = PlanningNudges.nextAt(app, now)!!
            assertEquals(time(9, day = before.plusDays(1)), next)
            assertEquals(23 * 60 * 60 * 1000L, next - now)
            assertEquals(9, Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()).hour)
        } finally { TimeZone.setDefault(original) }
    }
}
