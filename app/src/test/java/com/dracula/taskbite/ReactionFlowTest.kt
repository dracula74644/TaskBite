package com.dracula.taskbite

import android.app.NotificationManager
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReactionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskBiteApp

    private fun setup(): MutableList<Int> {
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        app.preferences.apply { actionVoice = true; quietEnabled = false; planningNudge = false }
        val audio = app.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val notifications = app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        installTestVoicePack(app)
        val plays = mutableListOf<Int>()
        ShadowAudioTrack.addAudioDataListener { _, data, _ -> plays.add(data.contentHashCode()) }
        return plays
    }
    private fun expect(plays: List<Int>, count: Int, kind: RoastKind) {
        compose.waitUntil(10_000) { plays.size == count }
        val expected = VoiceLibrary(app).bundled(kind).map { clip ->
            clip.pcm(app).samples.contentHashCode()
        }
        assertTrue("Wrong recording for $kind", plays.last() in expected)
        val match = VoiceLibrary(app).bundled(kind).single { clip ->
            clip.pcm(app).samples.contentHashCode() == plays.last()
        }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasTestTag("reaction_caption"))
        compose.onNodeWithTag("reaction_caption").assertTextEquals(match.caption!!)
    }
    @Test fun addingStartingPostponingAndCompletingUseTheirOwnActualRecordings() {
        val plays = setup()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Voice action task")
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        expect(plays, 1, RoastKind.CREATED)
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Start"))
        compose.onNodeWithText("Start").performClick()
        expect(plays, 2, RoastKind.STARTED)
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Tomorrow"))
        compose.onNodeWithText("Tomorrow").performScrollTo().assertIsDisplayed().performClick()
        expect(plays, 3, RoastKind.SNOOZE)
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasContentDescription("Complete Voice action task"))
        compose.onNodeWithContentDescription("Complete Voice action task").performClick()
        expect(plays, 4, RoastKind.COMPLETED)
        val task = runBlocking { app.repository.dao.allTasks().single() }
        assertNotNull(task.completedAt)
    }
    @Test fun suppressedAutomaticAudioShowsWhyAndCanBePreviewed() {
        val plays = setup()
        app.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        runBlocking { app.repository.save(Task(title = "Muted reaction", dueAt = System.currentTimeMillis() + 60_000)) }
        compose.waitUntil(10_000) { compose.activity.pendingCount == 1 }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Start"))
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(VoiceResult.SILENT_MODE.message!!).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(plays.isEmpty())
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasContentDescription("Play reaction"))
        compose.onNodeWithContentDescription("Play reaction").performClick()
        expect(plays, 1, RoastKind.STARTED)
    }
    @Test fun overdueTaskActuallyPlaysWhileTodayIsOpen() {
        val plays = setup()
        runBlocking { app.repository.save(Task(title = "Due now", dueAt = System.currentTimeMillis() - 1000)) }
        expect(plays, 1, RoastKind.OVERDUE)
    }
    @Test fun missingSweetRecordingShowsTextOnlyWithoutPlayingAnUnrelatedClip() {
        val plays = setup()
        app.preferences.apply { planningNudge = true; planningHour = 0; teasingStyle = TeasingStyle.SWEET }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Text only · no Sweet teasing recording for an empty day. Choose a pack covering this situation or add a recording.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(plays.isEmpty())
        compose.onNodeWithContentDescription("Play reaction").assertDoesNotExist()
        assertTrue(Roasts.sweetLines.getValue(RoastKind.PLAN_DAY).any { line ->
            compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()
        })
        assertTrue(runBlocking { app.repository.dao.allTasks().isEmpty() })
        assertTrue(runBlocking { app.repository.dao.allEvents().isEmpty() })
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals(0, plays.size)
    }
    @Test fun replayUsesTheExactClipAndCaptionEvenAfterRotation() {
        val plays = setup()
        runBlocking { app.repository.save(Task(title = "Replay me", dueAt = System.currentTimeMillis() + 3_600_000)) }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Start"))
        compose.onNodeWithText("Start").performClick()
        expect(plays, 1, RoastKind.STARTED)
        val first = plays.single()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasContentDescription("Play reaction"))
        compose.onNodeWithContentDescription("Play reaction").performClick()
        compose.waitUntil(10_000) { plays.size == 2 }
        assertEquals(first, plays.last())
        expect(plays, 2, RoastKind.STARTED)
    }
    @Test fun returningAfterAbandonmentUsesTheReturnRecording() {
        val plays = setup()
        runBlocking { app.repository.save(Task(title = "Still waiting", dueAt = System.currentTimeMillis() + 60_000)) }
        app.preferences.abandonedAt = System.currentTimeMillis() - 11_000
        compose.activityRule.scenario.recreate()
        expect(plays, 1, RoastKind.ABANDON)
        assertEquals(0, app.preferences.abandonedAt)
    }
}
