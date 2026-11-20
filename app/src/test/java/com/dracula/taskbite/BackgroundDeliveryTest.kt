package com.dracula.taskbite

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import org.robolectric.Robolectric
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class BackgroundDeliveryTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val p get() = app.preferences
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        app.foreground = false
        p.apply { onboarded = true; reminders = true; backgroundVoice = true; quietEnabled = false; planningNudge = false }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private suspend fun work() {
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork())
    }
    @Test fun workerPostsDeadlineNotificationEvenWhenVoiceUsesAnExactAlarm() = runBlocking {
        val now = System.currentTimeMillis()
        val task = Task(title = "Due while Bite is closed", dueAt = now - 60_000)
        app.repository.dao.put(task)
        work()
        val notice = manager.activeNotifications.single { it.tag == task.id }.notification
        val audio = requireNotNull(ReminderAudio.peek(app))
        assertEquals(notice.extras.getString(Notification.EXTRA_TEXT), audio.reaction.caption)
        assertEquals(notice.extras.getString(NotificationReactions.TOKEN), audio.notice)
        assertTrue(audio.reaction.clip != null)
        assertEquals(task.updatedAt, app.repository.dao.task(task.id)!!.updatedAt)
        assertTrue(app.repository.dao.allEvents().isEmpty())
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
            it.operation != null && shadowOf(it.operation).savedIntent.component?.className == SpokenAlarmReceiver::class.java.name
        }
        assertEquals(audio.at, alarm.triggerAtMs)
        work() // Worker/alarm overlap cannot duplicate the notification or choose a new clip.
        assertEquals(audio.token, ReminderAudio.peek(app)!!.token)
        assertEquals(1, manager.activeNotifications.count { it.tag == task.id })
    }
    @Test fun missingAlarmPermissionStillPostsAndExplainsTheMissingVoice() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val task = Task(title = "No alarm permission", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        work()
        assertTrue(manager.activeNotifications.any { it.tag == task.id })
        assertTrue(DeliveryStatus.report(app).any { it.contains("Enable Alarms & reminders") })
    }
    @Test @Config(shadows = [BlockedPlaybackService::class])
    fun deniedForegroundServiceFallsBackInsteadOfSilentlyEatingTheReminder() = runBlocking {
        val task = Task(title = "Foreground service denied", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        val service = Robolectric.buildService(SpokenReminderService::class.java).create()
        service.startCommand(0, 1)
        assertTrue(DeliveryStatus.report(app).any { it.contains("couldn't start the voice service") })
        assertEquals(task, app.repository.dao.task(task.id))
        work()
        assertTrue(manager.activeNotifications.any { it.tag == task.id })
        service.destroy()
        Unit
    }
    @Test fun blockedChannelIsReportedAndDoesNotConsumeTheDeadline() = runBlocking {
        val task = Task(title = "Channel blocked", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        manager.getNotificationChannel(Reminders.CHANNEL).also {
            it.importance = NotificationManager.IMPORTANCE_NONE
            manager.createNotificationChannel(it)
        }
        assertFalse(Reminders.allowed(app))
        assertEquals("Android notifications are blocked", ReminderHealth.read(app).status)
        work()
        assertEquals(task, app.repository.dao.task(task.id))
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun processRestartedAwayStateCanPostAnEmptyDayNudge() = runBlocking {
        val now = System.currentTimeMillis()
        p.backgroundSince = now - 31 * 60_000
        p.lastTaskActivity = 0
        assertEquals(p.backgroundSince, Preferences(app).backgroundSince)
        work()
        assertTrue(manager.activeNotifications.any { it.tag == "away_plan" })
        assertTrue(app.repository.dao.allTasks().isEmpty())
    }
    @Test fun quietHoursAndDeniedNotificationPermissionDoNotConsumeTasks() = runBlocking {
        val task = Task(title = "Respect settings", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        p.quietEnabled = true; p.quietStart = 0; p.quietEnd = 0
        work()
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(task, app.repository.dao.task(task.id))
        p.quietEnabled = false
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        work()
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(task, app.repository.dao.task(task.id))
    }
    @Test fun explicitTenSecondTestIgnoresQuietHoursAndDoesNotTouchRealTasks() = runBlocking {
        val now = System.currentTimeMillis()
        p.quietEnabled = true; p.quietStart = 0; p.quietEnd = 0
        val task = Task(title = "Leave this alone", dueAt = now + 3_600_000)
        app.repository.dao.put(task)
        assertTrue(NotificationTest.schedule(app, now))
        assertEquals(now + 10_000, NotificationTest.nextAt(app, now))
        assertFalse(NotificationTest.deliver(app, false, now + 9_999))
        assertTrue(NotificationTest.deliver(app, false, now + 10_000))
        assertFalse(NotificationTest.deliver(app, false, now + 10_000))
        assertEquals(1, manager.activeNotifications.count { it.tag == "delivery_test" })
        assertTrue(ReminderAudio.peek(app, now + 10_000)!!.preview)
        assertEquals(listOf(task), app.repository.dao.allTasks())
        assertTrue(app.repository.dao.allEvents().isEmpty())
        assertEquals(0L, p.lastReminder); assertEquals(0L, p.lastAwayNudge)
    }
    @Test fun cancelingThePhoneTestKeepsTheRealReminderAndItsData() = runBlocking {
        val now = System.currentTimeMillis()
        val task = Task(title = "A real upcoming task", dueAt = now + 120_000)
        app.repository.dao.put(task)
        SpokenAlarms.schedule(app, now)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val realDue = alarms.scheduledAlarms.single().triggerAtMs
        val settings = app.getSharedPreferences("taskbite", android.content.Context.MODE_PRIVATE).all.toMap()
        assertTrue(NotificationTest.schedule(app, now))
        assertEquals(TestDeliveryPhase.QUEUED, NotificationTest.state(app, now).phase)
        assertTrue(NotificationTest.cancel(app))
        assertEquals(TestDeliveryPhase.CANCELED, NotificationTest.state(app, now).phase)
        assertNull(NotificationTest.nextAt(app, now))
        assertFalse(NotificationTest.deliver(app, false, now + 10_000))
        assertEquals(realDue, alarms.scheduledAlarms.single().triggerAtMs)
        assertEquals(listOf(task), app.repository.dao.allTasks())
        assertTrue(app.repository.dao.allEvents().isEmpty())
        assertEquals(settings, app.getSharedPreferences("taskbite", android.content.Context.MODE_PRIVATE).all)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun duplicateTestRequestsKeepTheCountdownAndTheSentResultSurvivesOtherDelivery() = runBlocking {
        val now = System.currentTimeMillis()
        assertTrue(NotificationTest.schedule(app, now))
        assertTrue(NotificationTest.schedule(app, now + 2_000))
        assertEquals(now + 10_000, NotificationTest.nextAt(app, now))
        assertTrue(NotificationTest.deliver(app, false, now + 10_000))
        assertEquals(TestDeliveryPhase.SENT, NotificationTest.state(app, now + 10_000).phase)
        assertFalse(NotificationTest.cancel(app))
        DeliveryStatus.event(app, "A different regular reminder")
        assertEquals(TestDeliveryPhase.SENT, NotificationTest.state(app, now + 11_000).phase)
        assertEquals(1, manager.activeNotifications.count { it.tag == "delivery_test" })
    }
    @Test fun blockedAndExpiredTestsShowTheirOwnResultsAndCanBeRetried() = runBlocking {
        val now = System.currentTimeMillis()
        assertTrue(NotificationTest.schedule(app, now))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationTest.deliver(app, false, now + 10_000))
        assertEquals(TestDeliveryPhase.BLOCKED, NotificationTest.state(app, now + 10_000).phase)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationTest.schedule(app, now + 20_000))
        assertEquals(TestDeliveryPhase.EXPIRED, NotificationTest.state(app, now + 16 * 60_000).phase)
        assertFalse(NotificationTest.deliver(app, false, now + 16 * 60_000))
        assertTrue(NotificationTest.schedule(app, now + 17 * 60_000))
        assertEquals(TestDeliveryPhase.QUEUED, NotificationTest.state(app, now + 17 * 60_000).phase)
    }
    @Test fun fallbackAfterServiceFailureRetainsTheTextDeliveryPath() = runBlocking {
        val now = System.currentTimeMillis()
        val task = Task(title = "Service couldn't start", dueAt = now - 60_000)
        app.repository.dao.put(task)
        SpokenAlarms.notificationFallback(app, "Playback denied by Android")
        assertNull(ReminderAudio.peek(app))
        work()
        assertTrue(manager.activeNotifications.any { it.tag == task.id })
        assertTrue(DeliveryStatus.report(app).any { it.contains("Last notification") })
    }
    @Test fun completingTaskCancelsQueuedVoiceWithoutRemovingOtherTasks() = runBlocking {
        val task = Task(title = "Complete before audio", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        work()
        val pending = requireNotNull(ReminderAudio.peek(app))
        app.repository.complete(task.id, true)
        assertTrue(ReminderAudio.playPending(app, pending.token))
        assertNull(ReminderAudio.peek(app))
        assertTrue(DeliveryStatus.report(app).any { it.contains("Voice canceled") })
        assertNotNull(app.repository.dao.task(task.id)!!.completedAt)
    }
    @Test fun expiredVoiceNeverReplaysHoursLater() = runBlocking {
        val task = Task(title = "Expired audio", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task); work()
        val pending = requireNotNull(ReminderAudio.peek(app))
        assertNull(ReminderAudio.peek(app, pending.expires + 1))
        assertFalse(ReminderAudio.playPending(app, pending.token, pending.expires + 1))
        assertTrue(manager.activeNotifications.any { it.tag == task.id })
    }
}

@Implements(Service::class)
class BlockedPlaybackService : ShadowService() {
    @Implementation override fun startForeground(id: Int, notification: Notification, type: Int) {
        throw SecurityException("Background playback denied for regression test")
    }
}
