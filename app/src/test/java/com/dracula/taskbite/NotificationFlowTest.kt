package com.dracula.taskbite

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: TaskBiteApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun setup(): MutableList<Int> {
        app = compose.activity.application as TaskBiteApp
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        app.preferences.apply { actionVoice = false; backgroundVoice = true; notificationReactions = true
            quietEnabled = false; planningNudge = false; voiceActor = "sean" }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSystemService(AudioManager::class.java).apply {
            ringerMode = AudioManager.RINGER_MODE_NORMAL; setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        }
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        installTestVoicePack(app)
        val plays = mutableListOf<Int>()
        ShadowAudioTrack.addAudioDataListener { _, bytes, _ -> plays.add(bytes.contentHashCode()) }
        return plays
    }
    private fun deliverIntent(intent: Intent) {
        compose.runOnUiThread {
            val activity = compose.activity
            val launch = activity.intent
            ReflectionHelpers.callInstanceMethod<Any?>(activity, "onNewIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, intent))
            // ActivityScenario tracks lifecycle by its original launch intent.
            activity.intent = launch
        }
    }
    @Test fun tappingAwayNotificationAfterGoingHomePlaysMatchingGreetingOnlyOnce() {
        val plays = setup()
        val task = Task(title = "Tap and get moving", dueAt = System.currentTimeMillis() + 3_600_000)
        runBlocking { app.repository.dao.put(task) }
        compose.waitUntil(10_000) { compose.activity.pendingCount == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val n = requireNotNull(Reminders.notify(app, task, "Waiting.", awayTitle = "Still waiting"))
        val intent = shadowOf(manager.activeNotifications.single { it.tag == "away_task" }.notification.contentIntent).savedIntent
        // ActivityScenario cannot dispatch onNewIntent while stopped. Restore the
        // pending request as Android does before onStart, then test resumed intents below.
        compose.runOnUiThread { compose.activity.noticeRequest = intent.getStringExtra(NotificationReactions.TOKEN) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { plays.size == 1 }
        assertEquals("opened", NotificationReactions.find(app, n.token)!!.state)
        val clip = VoiceLibrary(app).bundled(RoastKind.NOTIFICATION_OPENED).single {
            it.pcm(app).samples.contentHashCode() == plays.single()
        }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasTestTag("reaction_caption"))
        compose.onNodeWithTag("reaction_caption").assertTextEquals(clip.caption!!)
        compose.activityRule.scenario.recreate()
        deliverIntent(intent)
        compose.waitForIdle()
        assertEquals(1, plays.size)
        assertEquals(task, runBlocking { app.repository.dao.task(task.id) })
        assertTrue(runBlocking { app.repository.dao.allEvents().isEmpty() })
    }
    @Test fun planningNotificationStillOpensTheEditorAndDoesNotCreateATaskOnTap() {
        val plays = setup()
        Reminders.notifyPlanning(app, "One tiny plan.")
        val intent = shadowOf(manager.activeNotifications.single { it.tag == "plan_day" }.notification.contentIntent).savedIntent
        deliverIntent(intent)
        compose.onNodeWithText("Add task").assertExists()
        compose.waitUntil(10_000) { plays.size == 1 }
        assertTrue(runBlocking { app.repository.dao.allTasks().isEmpty() })
    }
    @Test fun swipingPlaysClearingRecordingDespiteClearAllAndPendingReminderAlarm() {
        val plays = setup()
        ShadowToast.reset()
        val task = Task(title = "A dismissal is not completion", dueAt = System.currentTimeMillis() + 3_600_000)
        val other = Task(title = "Clear all once", dueAt = task.dueAt)
        runBlocking { app.repository.dao.put(task); app.repository.dao.put(other) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val n = requireNotNull(Reminders.notify(app, task, "Still pending."))
        Reminders.notify(app, other, "Also pending.")
        val notification = manager.activeNotifications.single { it.tag == task.id }.notification
        val second = manager.activeNotifications.single { it.tag == other.id }.notification
        compose.runOnUiThread { notification.deleteIntent.send(); second.deleteIntent.send() }
        val started = mutableListOf<Intent>()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            var next = shadowOf(app).nextStartedService
            while (next != null) {
                if (next.component?.className == SpokenReminderService::class.java.name) started.add(next)
                next = shadowOf(app).nextStartedService
            }
            started.size == 2
        }
        // No database/audio preparation runs before the service-start request.
        assertNull(ReminderAudio.peek(app))
        assertEquals(n.token, started.first().getStringExtra(NotificationReactions.TOKEN))
        assertEquals(NotificationReactions.DISMISS, started.first().action)
        val controller = Robolectric.buildService(SpokenReminderService::class.java, started.first()).create()
        compose.runOnUiThread {
            controller.startCommand(0, 1)
            controller.get().onStartCommand(started.last(), 0, 2)
            // A previously scheduled reminder alarm can arrive just after the swipe.
            controller.get().onStartCommand(Intent(app, SpokenReminderService::class.java), 0, 3)
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            plays.size == 1 && DeliveryStatus.report(app).any { it.contains("Recording played") } && shadowOf(controller.get()).isStoppedBySelf
        }
        val played = VoiceLibrary(app).bundled(RoastKind.NOTIFICATION_CLEARED).single {
            it.pcm(app).samples.contentHashCode() == plays.single()
        }
        assertEquals(played.caption, ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
        assertTrue(DeliveryStatus.report(app).any { it.startsWith("Last clearing reaction") && it.contains("Recording played") })
        assertEquals(3, shadowOf(controller.get()).stopSelfId) // Ignored requests must not leave an idle service running.
        assertNull(ReminderAudio.peek(app))
        assertEquals("dismissed", NotificationReactions.find(app, n.token)!!.state)
        assertEquals(task, runBlocking { app.repository.dao.task(task.id) })
        compose.runOnUiThread { controller.destroy() }
    }
    @Test fun mutedClearingShowsMatchingCaptionAndKeepsItsOwnFailureReport() {
        val plays = setup()
        ShadowToast.reset()
        app.preferences.apply { alertVolume = 0; backgroundVoice = false }
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val n = requireNotNull(Reminders.notifyPlanning(app, "An empty list."))
        val notice = manager.activeNotifications.single { it.tag == n.tag }.notification
        val intent = shadowOf(notice.deleteIntent).savedIntent
        val controller = Robolectric.buildService(SpokenReminderService::class.java, intent).create()
        compose.runOnUiThread { controller.startCommand(0, 1) }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            shadowOf(controller.get()).isStoppedBySelf && ShadowToast.shownToastCount() == 1
        }
        assertTrue(plays.isEmpty())
        assertTrue(ShadowToast.getTextOfLatestToast() in VoiceLibrary(app).bundled(RoastKind.NOTIFICATION_CLEARED).map { it.caption })
        val result = DeliveryStatus.report(app).single { it.startsWith("Last clearing reaction") }
        assertTrue(result.contains("Spoken alerts are muted"))
        DeliveryStatus.audio(app, "A later reminder played")
        assertTrue(result in DeliveryStatus.report(app))
        assertEquals("dismissed", NotificationReactions.find(app, n.token)!!.state)
        assertTrue(runBlocking { app.repository.dao.allTasks().isEmpty() })
        assertTrue(runBlocking { app.repository.dao.allEvents().isEmpty() })
        compose.runOnUiThread { controller.destroy() }
    }
    @Test fun backgroundTestIsReachableAndQueuesWithoutAddingSampleTasks() {
        setup()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Test & troubleshoot").performClick()
        compose.onNodeWithTag("testing_tools").performScrollToNode(hasText("Send test notification"))
        compose.onNodeWithText("Send test notification").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { NotificationTest.nextAt(app) != null }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val at = requireNotNull(NotificationTest.nextAt(app))
        runBlocking { assertTrue(NotificationTest.deliver(app, false, at)) }
        assertTrue(manager.activeNotifications.any { it.tag == "delivery_test" })
        assertTrue(runBlocking { app.repository.dao.allTasks().isEmpty() })
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(5_000) // Let the test-queued snackbar finish before tapping a control underneath it.
        compose.onNodeWithTag("testing_tools").performScrollToNode(hasText("Test notification sent"))
        compose.onNodeWithText("Test notification sent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Notification reactions"))
        compose.onNodeWithContentDescription("Notification reactions").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val target = java.io.File("build/reports/ui-previews/notification-controls.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithContentDescription("Notification reactions").performClick()
        compose.onNodeWithContentDescription("Notification reactions").assertIsOff()
        assertFalse(Preferences(app).notificationReactions)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Notification reactions"))
        compose.onNodeWithContentDescription("Notification reactions").assertIsOff()
    }
    @Test @Config(qualifiers = "w320dp-h600dp")
    fun compactTestToolsKeepPendingStatusAcrossRotationAndCancelWithoutChangingTasks() {
        setup()
        val real = Task(title = "Keep my actual task", dueAt = System.currentTimeMillis() + 86_400_000)
        runBlocking { app.repository.save(real) }
        val tasks = runBlocking { app.repository.dao.allTasks() }
        val history = runBlocking { app.repository.dao.allEvents() }
        val gap = app.preferences.awayGapMinutes
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Test & troubleshoot").performClick()
        captureTesting("testing-tools-compact")
        compose.onNodeWithTag("testing_tools").performScrollToNode(hasText("Send test notification"))
        compose.onNodeWithText("Send test notification").performScrollTo().performClick()
        compose.waitUntil(10_000) { NotificationTest.state(app).pending }
        val due = NotificationTest.nextAt(app)
        compose.activityRule.scenario.recreate()
        assertEquals(due, NotificationTest.nextAt(app))
        compose.onNodeWithTag("testing_tools").performScrollToNode(hasText("Test in progress"))
        compose.onNodeWithText("Test in progress").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel test").performScrollTo().performClick()
        compose.waitUntil(10_000) { NotificationTest.state(app).phase == TestDeliveryPhase.CANCELED }
        compose.onNodeWithTag("testing_tools").performScrollToNode(hasText("Test canceled"))
        compose.onNodeWithText("Test canceled").performScrollTo().assertIsDisplayed()
        captureTesting("testing-canceled-compact")
        assertNull(NotificationTest.nextAt(app))
        assertEquals(tasks, runBlocking { app.repository.dao.allTasks() })
        assertEquals(history, runBlocking { app.repository.dao.allEvents() })
        assertEquals(gap, app.preferences.awayGapMinutes)
        assertTrue(manager.activeNotifications.none { it.tag == "delivery_test" })
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNodeWithText("Voice & settings").assertIsDisplayed()
        compose.onNodeWithText("Send test notification").assertDoesNotExist()
    }
    private fun captureTesting(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File("build/reports/ui-previews/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
