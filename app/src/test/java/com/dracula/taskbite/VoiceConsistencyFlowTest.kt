package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, shadows = [InstantRecordedPlayer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceConsistencyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskBiteApp
    private fun setup() {
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        app.preferences.apply { planningNudge = false; actionVoice = true; quietEnabled = false; voiceActor = "sean"; reminders = true; backgroundVoice = true }
        val audio = app.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithText("Sidekick").performClick()
    }
    private fun backgroundReminder(abandon: Boolean = false, now: Long = System.currentTimeMillis()) {
        app.foreground = false
        var job: Job? = null
        compose.runOnUiThread { job = CoroutineScope(Dispatchers.Main).launch { ReminderDelivery.deliver(app, abandon, true, now) } }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            job?.isCompleted == true
        }
        assertFalse(job!!.isCancelled)
    }

    @Test fun editImportedCaptionAssignSweetStyleThenAddTaskUsesExactFileAndWords() {
        setup()
        val source = File.createTempFile("caption-test", ".m4a", app.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        ShadowMediaMetadataRetriever.addMetadata(source.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")
        ShadowMediaMetadataRetriever.addMetadata(source.path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        val clip = VoiceLibrary(app).importFile(RoastKind.CREATED, source, "Girlfriend recording")
        app.preferences.customVoice = true
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Voice settings"))
        compose.onNodeWithText("Voice settings").performScrollTo().performClick()
        compose.onNodeWithText("Task overdue").performClick()
        compose.onNodeWithText("Task added").performClick()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasContentDescription("Edit caption Girlfriend recording"))
        compose.onNodeWithContentDescription("Edit caption Girlfriend recording").performClick()
        val words = "Added, babe. Now comes the adorable part: doing it."
        compose.onNode(hasSetTextAction() and hasText("Spoken words")).performTextInput(words)
        compose.onNode(hasText("Sweet teasing") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Save caption").performClick()
        compose.onNodeWithTag("voice_library").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Close voice settings").performClick()
        compose.onNodeWithText("Sweet teasing").performScrollTo().performClick()
        compose.onNodeWithText("Today").performClick()
        var played: ShadowMediaPlayer? = null
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(1000, 0) }
        ShadowMediaPlayer.setCreateListener { _, player -> played = player }
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("A little mission")
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        compose.waitUntil(10_000) { played?.dataSource != null }
        compose.onNodeWithTag("reaction_caption").assertTextEquals(words)
        assertEquals(DataSource.toDataSource(clip.file!!.path), played!!.dataSource)
        assertEquals(1, runBlocking { app.repository.dao.allTasks().size })
    }

    @Test fun dueNotificationCaptionMatchesAudioAndNotificationActionsUpdateOnlyTargetTask() {
        setup()
        val hashes = mutableListOf<Int>()
        ShadowAudioTrack.addAudioDataListener { _, bytes, _ -> hashes.add(bytes.contentHashCode()) }
        val repo = app.repository
        val task = Task(title = "Due notification", dueAt = System.currentTimeMillis() - 1000)
        runBlocking { repo.save(task) }
        backgroundReminder()
        assertEquals(1, hashes.size)
        val clip = VoiceLibrary(app).bundled(RoastKind.OVERDUE).single {
            app.assets.open(it.asset!!).use(PcmAudio::read).samples.contentHashCode() == hashes.single()
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.tag == task.id }.notification
        assertEquals(clip.caption, notification.extras.getString(Notification.EXTRA_TEXT))
        compose.runOnUiThread { notification.actions.single { it.title == "Done" }.actionIntent.send() }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            runBlocking { repo.dao.task(task.id)?.completedAt != null }
        }
        assertEquals(1, runBlocking { repo.dao.allEvents().count { it.kind == "COMPLETED" } })
        val other = Task(title = "Postpone notification", dueAt = System.currentTimeMillis() - 1000)
        runBlocking { repo.save(other) }
        app.preferences.lastReminder = 0
        backgroundReminder()
        val next = manager.activeNotifications.single { it.tag == other.id }.notification
        compose.runOnUiThread {
            val postpone = next.actions.single { it.title == "Tomorrow" }.actionIntent
            postpone.send(); postpone.send() // A repeated tap on the same notification is harmless.
        }
        compose.waitUntil(10_000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            runBlocking { repo.dao.task(other.id)?.snoozeCount == 1 }
        }
        assertNotNull(runBlocking { repo.dao.task(task.id)!!.completedAt })
        assertTrue(runBlocking { repo.dao.task(other.id)!!.dueAt } > System.currentTimeMillis())
        assertEquals(1, runBlocking { repo.dao.taskEvents(other.id).count { it.kind == "SNOOZED" } })
    }

    @Test fun missingSweetBackgroundVoiceStaysTextOnlyAndUnqualifiedExitDoesNotSpeak() {
        setup()
        var plays = 0
        ShadowAudioTrack.addAudioDataListener { _, _, _ -> plays++ }
        app.preferences.teasingStyle = TeasingStyle.SWEET
        val task = Task(title = "Sweet overdue", dueAt = System.currentTimeMillis() - 1000)
        runBlocking { app.repository.save(task) }
        backgroundReminder()
        val notice = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == task.id }.notification
        assertTrue(notice.extras.getString(Notification.EXTRA_TEXT) in Roasts.options(RoastKind.OVERDUE, TeasingStyle.SWEET))
        assertEquals("Text only · no matching recording", notice.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertEquals(0, plays)
        app.preferences.apply { teasingStyle = TeasingStyle.CLASSIC; lastReminder = 0 }
        backgroundReminder(abandon = true)
        assertEquals(0, plays)
    }

    @Test fun outsideScenariosPlayTheirMatchingRecordingAndCaptionWithoutTouchingTasks() {
        setup()
        fun at(h: Int, m: Int = 0) = java.time.LocalDate.now().atTime(h, m)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hashes = mutableListOf<Int>()
        ShadowAudioTrack.addAudioDataListener { _, bytes, _ -> hashes.add(bytes.contentHashCode()) }
        val manager = app.getSystemService(NotificationManager::class.java)
        val cases = listOf(AwayScenario.LEFT_WITHOUT_PROGRESS, AwayScenario.TINY_TASK,
            AwayScenario.POSTPONED_TASK, AwayScenario.STILL_WORKING, AwayScenario.OVERDUE_TASK,
            AwayScenario.SMALL_LIST, AwayScenario.LOW_PRIORITY, AwayScenario.NO_PROGRESS)
        for (scenario in cases) {
            val count = when (scenario) { AwayScenario.SMALL_LIST -> 3; AwayScenario.NO_PROGRESS -> 4; else -> 1 }
            val item = Task(title = "Away ${scenario.name}", dueAt = if (scenario == AwayScenario.OVERDUE_TASK) at(10) else at(18),
                remindAfter = if (scenario == AwayScenario.OVERDUE_TASK) at(15) else at(18), createdAt = at(8), updatedAt = at(8),
                priority = if (scenario == AwayScenario.LOW_PRIORITY) 0 else 2,
                minutes = if (scenario == AwayScenario.TINY_TASK) 2 else 15,
                snoozeCount = if (scenario == AwayScenario.POSTPONED_TASK) 3 else 0,
                startedAt = if (scenario == AwayScenario.STILL_WORKING) at(9) else null)
            runBlocking {
                app.repository.dao.allTasks().forEach { app.repository.dao.put(it.copy(archived = true)) }
                app.repository.dao.put(item)
                repeat(count - 1) { index -> app.repository.dao.put(item.copy(id = "${item.id}-$index", priority = 1, dueAt = at(19), remindAfter = at(19))) }
            }
            app.preferences.apply {
                backgroundSince = at(9); lastTaskActivity = at(8); lastReminder = 0; lastAwayNudge = 0
                awayCount = 0; handledAwayAbandon = 0
                abandonedAt = if (scenario == AwayScenario.LEFT_WITHOUT_PROGRESS) at(9) else 0
            }
            hashes.clear()
            backgroundReminder(now = if (scenario == AwayScenario.LEFT_WITHOUT_PROGRESS) at(9, 30) else at(11))
            assertEquals("${scenario.name} must play once", 1, hashes.size)
            val clip = VoiceLibrary(app).bundled(scenario.kind).single {
                app.assets.open(it.asset!!).use(PcmAudio::read).samples.contentHashCode() == hashes.single()
            }
            val notice = manager.activeNotifications.single { it.tag == "away_task" }.notification
            assertEquals(scenario.title(item, count), notice.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(clip.caption, notice.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(item, runBlocking { app.repository.dao.task(item.id) })
            assertTrue(runBlocking { app.repository.dao.allEvents().isEmpty() })
        }
        // Sweet has no human pack yet: outside nudges must never borrow Classic's voice.
        app.preferences.apply { teasingStyle = TeasingStyle.SWEET; lastReminder = 0; lastAwayNudge = 0; awayCount = 0 }
        hashes.clear()
        backgroundReminder(now = at(11))
        val sweet = manager.activeNotifications.single { it.tag == "away_task" }.notification
        assertEquals("Text only · no matching recording", sweet.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertTrue(sweet.extras.getString(Notification.EXTRA_TEXT) in Roasts.options(RoastKind.IDLE, TeasingStyle.SWEET))
        assertTrue(hashes.isEmpty())
    }
}
