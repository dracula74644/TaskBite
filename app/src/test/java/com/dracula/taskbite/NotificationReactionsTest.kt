package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class NotificationReactionsTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val p get() = app.preferences
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    @Before fun setup() {
        p.apply { onboarded = true; reminders = true; quietEnabled = false; notificationReactions = true; backgroundVoice = true }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowToast.reset()
    }
    private suspend fun post(now: Long, title: String = "Still waiting"): Pair<Task, BiteNotice> {
        val task = Task(title = title, dueAt = now + 3_600_000, updatedAt = now - 60_000)
        app.repository.dao.put(task)
        return task to requireNotNull(Reminders.notify(app, task, "One step.", now = now))
    }
    @Test fun notificationIntentsAreUniqueDirectActivityOpensAndExplicitDismissals() = runBlocking {
        val now = System.currentTimeMillis()
        val (task, first) = post(now)
        val (other, second) = post(now, "Other task")
        val notices = manager.activeNotifications.associateBy { it.tag }
        val a = notices.getValue(task.id).notification
        val b = notices.getValue(other.id).notification
        assertTrue(shadowOf(a.contentIntent).isActivityIntent)
        assertEquals(MainActivity::class.java.name, shadowOf(a.contentIntent).savedIntent.component!!.className)
        assertEquals(first.token, shadowOf(a.contentIntent).savedIntent.getStringExtra(NotificationReactions.TOKEN))
        assertEquals(second.token, shadowOf(b.deleteIntent).savedIntent.getStringExtra(NotificationReactions.TOKEN))
        assertNotEquals(a.contentIntent, b.contentIntent)
        assertNotEquals(a.deleteIntent, b.deleteIntent)
        assertTrue(a.deleteIntent.isForegroundService)
        assertEquals(SpokenReminderService::class.java.name, shadowOf(a.deleteIntent).savedIntent.component!!.className)
        assertFalse(shadowOf(a.deleteIntent).savedIntent.hasExtra(NotificationReactions.DISMISSED_AT))
        assertEquals(listOf("Done", "Tomorrow"), a.actions.map { it.title.toString() })
    }
    @Test fun openingConsumesOnceKeepsTaskPendingAndCancelsQueuedOriginalVoice() = runBlocking {
        val now = System.currentTimeMillis()
        val (task, n) = post(now)
        ReminderAudio.enqueue(app, VoiceLibrary(app).reaction(RoastKind.OVERDUE), n.token, now = now)
        val reaction = requireNotNull(NotificationReactions.opened(app, n.token, now + 1_000))
        assertEquals(RoastKind.STARTED, reaction.kind)
        assertTrue(VoiceLibrary(app).bundled(RoastKind.STARTED).any { it.caption == reaction.caption && it.asset == reaction.clip?.asset })
        assertNull(ReminderAudio.peek(app))
        assertNull(NotificationReactions.opened(app, n.token, now + 2_000))
        assertNull(NotificationReactions.dismissed(app, n.token, now + 2_000))
        assertEquals("opened", NotificationReactions.find(app, n.token)!!.state)
        assertFalse(manager.activeNotifications.any { it.tag == task.id })
        assertEquals(task, app.repository.dao.task(task.id))
        assertTrue(app.repository.dao.allEvents().isEmpty())
    }
    @Test fun clearingAllNoticesQueuesOnlyOneMatchingHumanReaction() = runBlocking {
        val now = System.currentTimeMillis()
        val (_, first) = post(now)
        val (_, second) = post(now, "Other task")
        val audio = requireNotNull(NotificationReactions.dismissed(app, first.token, now + 1_000))
        assertTrue(audio.interaction)
        assertEquals(now + 1_000, audio.at) // A dismissal is ready immediately, never after the ordinary five-second handoff.
        assertEquals(RoastKind.NOTIFICATION_CLEARED, audio.reaction.kind)
        assertNull(NotificationReactions.dismissed(app, second.token, now + 1_100))
        assertEquals(audio.token, ReminderAudio.peek(app, now + 1_100)!!.token)
        assertNull(NotificationReactions.dismissed(app, first.token, now + 1_200))
        assertEquals("dismissed", NotificationReactions.find(app, second.token)!!.state)
        assertTrue(app.repository.dao.allEvents().isEmpty())
    }
    @Test fun dismissalFallbackSchedulesImmediatelyAndNeverPlaysALateReaction() = runBlocking {
        val now = System.currentTimeMillis()
        val (task, n) = post(now)
        val audio = requireNotNull(NotificationReactions.dismissed(app, n.token, now))
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        SpokenAlarms.schedule(app, now)
        val alarm = shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.single {
            it.operation != null && shadowOf(it.operation).savedIntent.component?.className == SpokenAlarmReceiver::class.java.name
        }
        assertEquals(now + 100, alarm.triggerAtMs)
        assertEquals(now + ReminderAudio.INTERACTION_LIFETIME, audio.expires)
        assertFalse(ReminderAudio.playPending(app, audio.token, now + ReminderAudio.INTERACTION_LIFETIME + 1))
        assertNull(ReminderAudio.peek(app))
        assertTrue(DeliveryStatus.report(app).any { it.contains("Dismissal voice skipped") })
        assertEquals(task, app.repository.dao.task(task.id))
    }
    @Test fun programmaticRemovalAndOldTaskRevisionsNeverProduceDismissalAudio() = runBlocking {
        val now = System.currentTimeMillis()
        val (task, notice) = post(now)
        Reminders.clear(app, task.id)
        assertNull(NotificationReactions.dismissed(app, notice.token, now + 1_000))
        val (_, second) = post(now, "Second task")
        val changed = requireNotNull(app.repository.dao.task(second.taskId!!))
        app.repository.dao.put(changed.copy(completedAt = now + 1_000, updatedAt = now + 1_000))
        assertNull(NotificationReactions.dismissed(app, second.token, now + 2_000))
        assertNull(ReminderAudio.peek(app))
    }
    @Test fun unansweredAndOpenedWithoutProgressUseDifferentFollowUpsAfterTheChosenGap() = runBlocking {
        val now = System.currentTimeMillis()
        p.awayGapMinutes = 47
        val (task, notice) = post(now)
        val nudge = AwayNudge(now, AwayScenario.NO_PROGRESS, task)
        assertNull(NotificationReactions.followUp(app, nudge, now + 46 * 60_000))
        val unanswered = requireNotNull(NotificationReactions.followUp(app, nudge, now + 47 * 60_000))
        assertTrue(unanswered.first.startsWith("No response yet:")); assertEquals(RoastKind.IDLE, unanswered.second)
        NotificationReactions.opened(app, notice.token, now + 48 * 60_000)
        assertNull(NotificationReactions.followUp(app, nudge, now + 49 * 60_000))
        val opened = requireNotNull(NotificationReactions.followUp(app, nudge, now + 95 * 60_000))
        assertTrue(opened.first.startsWith("Opened, still waiting:")); assertEquals(RoastKind.ABANDON, opened.second)
        p.lastTaskActivity = now + 94 * 60_000
        assertNull(NotificationReactions.followUp(app, nudge, now + 95 * 60_000))
    }
    @Test fun dismissedOrNoLongerVisibleNoticesAreNotCalledUnanswered() = runBlocking {
        val now = System.currentTimeMillis()
        val (task, n) = post(now)
        val nudge = AwayNudge(now, AwayScenario.NO_PROGRESS, task)
        manager.cancel(n.tag, n.id)
        assertNull(NotificationReactions.followUp(app, nudge, now + 30 * 60_000))
        val replacement = requireNotNull(Reminders.notify(app, task, "Again.", now = now))
        NotificationReactions.dismissed(app, replacement.token, now + 1_000)
        assertNull(NotificationReactions.followUp(app, nudge, now + 30 * 60_000))
    }
    @Test fun offSwitchAndQuietHoursPreventInteractionAudio() = runBlocking {
        val now = System.currentTimeMillis()
        val (_, n) = post(now)
        p.notificationReactions = false
        assertNull(NotificationReactions.opened(app, n.token, now + 1_000))
        val (_, second) = post(now)
        assertNull(NotificationReactions.dismissed(app, second.token, now + 1_000))
        p.notificationReactions = true
        val (_, third) = post(now)
        val queued = requireNotNull(NotificationReactions.dismissed(app, third.token, now + 2_000))
        p.quietEnabled = true; p.quietStart = 0; p.quietEnd = 0
        assertTrue(ReminderAudio.playPending(app, queued.token, now + 2_000))
        assertTrue(DeliveryStatus.report(app).any { it.contains("Quiet hours paused") })
    }
    @Test fun missingSweetInteractionIsTextOnlyWithoutSubstitutingAnotherVoice() = runBlocking {
        val now = System.currentTimeMillis()
        p.teasingStyle = TeasingStyle.SWEET
        val (_, n) = post(now)
        val reaction = requireNotNull(NotificationReactions.opened(app, n.token, now + 1_000))
        assertNull(reaction.clip)
        assertTrue(reaction.caption in Roasts.options(RoastKind.STARTED, TeasingStyle.SWEET))
        val (_, second) = post(now)
        assertNull(NotificationReactions.dismissed(app, second.token, now + 2_000))
        assertTrue(DeliveryStatus.report(app).any { it.contains("recording", ignoreCase = true) })
    }
    @Test fun clearingAnEmptyListWithMissingSweetAudioShowsOneHonestTextResponse() = runBlocking {
        p.teasingStyle = TeasingStyle.SWEET
        val now = System.currentTimeMillis()
        val notice = requireNotNull(Reminders.notifyPlanning(app, "An empty day", now = now))
        assertNull(NotificationReactions.dismissed(app, notice.token, now))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val text = ShadowToast.getTextOfLatestToast()
        assertTrue(Roasts.options(RoastKind.NOTIFICATION_CLEARED, TeasingStyle.SWEET).any { text == "Text only: $it" })
        assertEquals(1, ShadowToast.shownToastCount())
        assertNull(NotificationReactions.dismissed(app, notice.token, now + 100))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertNull(ReminderAudio.peek(app))
        assertTrue(app.repository.dao.allTasks().isEmpty())
        assertTrue(app.repository.dao.allEvents().isEmpty())
        val clearing = DeliveryStatus.report(app).single { it.startsWith("Last clearing reaction") }
        assertTrue(clearing.contains("Text only"))
        DeliveryStatus.audio(app, "A later regular nudge played")
        assertTrue(clearing in DeliveryStatus.report(app))
    }
    @Test fun legacyReceiverStillStartsPlaybackForNotificationsPostedBeforeUpgrade() = runBlocking {
        val (_, n) = post(System.currentTimeMillis())
        NoticeDismissReceiver().onReceive(app, android.content.Intent(app, NoticeDismissReceiver::class.java)
            .setAction(NotificationReactions.DISMISS).putExtra(NotificationReactions.TOKEN, n.token))
        val intent = shadowOf(app).nextStartedService
        assertEquals(SpokenReminderService::class.java.name, intent.component!!.className)
        assertEquals(n.token, intent.getStringExtra(NotificationReactions.TOKEN))
        assertTrue(intent.hasExtra(NotificationReactions.DISMISSED_AT))
        assertEquals("posted", NotificationReactions.find(app, n.token)!!.state)
    }
}
