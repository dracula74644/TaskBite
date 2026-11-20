package com.dracula.taskbite

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DaySimulationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskBiteApp
    private fun open(voices: Boolean = false) {
        installTestVoicePack(app)
        app.preferences.apply { planningNudge = false; actionVoice = false; reminders = false; teasingStyle = TeasingStyle.CLASSIC }
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        runBlocking { app.repository.save(Task(title = "My real task", dueAt = System.currentTimeMillis() + 86_400_000)) }
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Test & troubleshoot").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Simulate a day"))
        compose.onNodeWithText("Simulate a day").performScrollTo().performClick()
        if (!voices) {
            options()
            compose.onNodeWithTag("day_simulation").performScrollToNode(hasContentDescription("Simulation voices"))
            compose.onNodeWithContentDescription("Simulation voices").performClick()
        }
        compose.onNodeWithTag("day_simulation").performScrollToIndex(0)
    }
    private fun options() {
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Simulation options"))
        val button = compose.onNodeWithText("Simulation options").performScrollTo()
        if (button.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] == "Collapsed") button.performClick()
    }
    private fun jump(title: String) {
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Jump to situation"))
        compose.onNodeWithText("Jump to situation").performClick()
        compose.onNodeWithTag("simulation_scenarios").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
    }
    private fun capture(name: String) {
        compose.runOnIdle {
            // Include a dialog window when one is open, rather than capturing its covered activity.
            val windows = org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(
                Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val views = org.robolectric.util.ReflectionHelpers.getField<ArrayList<android.view.View>>(windows, "mViews")
            val view = views.lastOrNull() ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val target = File("build/reports/ui-previews/$name.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun wholeDayFastForwardJumpUndoAndStyleNeverChangeRealDataOrPreferences() {
        open()
        val dao = app.repository.dao
        val tasks = runBlocking { dao.allTasks() }
        val events = runBlocking { dao.allEvents() }
        val deletions = runBlocking { dao.allDeletions() }
        val preferences = app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all.toMap()
        val notifications = app.getSystemService(NotificationManager::class.java).activeNotifications.toList()
        compose.onNodeWithText("+5 min").performClick()
        compose.onNodeWithTag("simulation_clock").assertTextEquals("09:00:00")
        compose.onNodeWithText("+1 hour").performClick()
        compose.onNodeWithTag("simulation_clock").assertTextEquals("10:00:00")
        compose.onNodeWithText("Reset").performClick()
        repeat(DaySimulation.scenes.size) { compose.onNodeWithText("Next event").performClick() }
        compose.onNodeWithText("Next event").assertIsNotEnabled()
        compose.onNodeWithText("+1 hour").assertIsNotEnabled()
        jump("Complete and repeat")
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Undo sample action"))
        compose.onNodeWithText("Undo sample action").performScrollTo().performClick()
        compose.onNodeWithText("Sample action undone").assertExists()
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Sweet teasing"))
        compose.onNodeWithText("Sweet teasing").performScrollTo().performClick()
        jump("Third postponement")
        compose.onNodeWithTag("day_simulation").performScrollToIndex(0)
        capture("simulator-third-postponement")
        assertTrue(Roasts.options(RoastKind.SNOOZE_REPEAT, TeasingStyle.SWEET).any { line ->
            compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()
        })
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithContentDescription("Close simulator").performClick()
        compose.onNodeWithText("Test & troubleshoot").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNodeWithText("Voice & settings").assertExists()
        assertEquals(tasks, runBlocking { dao.allTasks() })
        assertEquals(events, runBlocking { dao.allEvents() })
        assertEquals(deletions, runBlocking { dao.allDeletions() })
        assertEquals(preferences, app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all)
        assertEquals(notifications, app.getSystemService(NotificationManager::class.java).activeNotifications.toList())
    }

    @Test @Config(qualifiers = "w320dp-h600dp")
    fun compactControlsRunPauseAndKeepProgressAcrossRotationAndBackgrounding() {
        open()
        compose.onNodeWithText("Run day").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2 / ${DaySimulation.scenes.size} events").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pause").assertIsDisplayed().performClick()
        compose.onNodeWithText("+1 hour").assertIsDisplayed().performClick()
        compose.onNodeWithTag("day_simulation").performScrollToIndex(0)
        val time = compose.onNodeWithTag("simulation_clock").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        capture("simulator-compact")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("simulation_clock").assertTextEquals(time)
        compose.onNodeWithText("Run day").assertIsDisplayed().performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Run day").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithTag("simulation_clock").assertTextEquals("08:55:00")
    }

    @Test fun previewPlaysRealSelectedRecordingAndQuietHoursDoNotPlay() {
        open(voices = true)
        app.preferences.apply { voiceActor = "bite_original"; alertVolume = 60 }
        val audio = app.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val plays = mutableListOf<Int>()
        ShadowAudioTrack.addAudioDataListener { _, data, _ -> plays.add(data.contentHashCode()) }
        compose.onNodeWithText("Next event").performClick()
        compose.waitUntil(10_000) { plays.size == 1 }
        val expected = VoiceLibrary(app).bundled(RoastKind.PLAN_DAY).map { clip ->
            clip.pcm(app).samples.contentHashCode()
        }
        assertTrue(plays.single() in expected)
        val original = plays.single()
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Hear clip"))
        compose.onNodeWithText("Hear clip").performScrollTo().performClick()
        compose.waitUntil(10_000) { plays.size == 2 }
        assertEquals(original, plays.last())
        options()
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Sweet teasing"))
        compose.onNodeWithText("Sweet teasing").performScrollTo().performClick()
        compose.onNodeWithTag("day_simulation").performScrollToIndex(0)
        compose.onNodeWithText("Hear clip").assertDoesNotExist()
        capture("simulator-planning")
        jump("Quiet hours")
        compose.onNodeWithText("Next event").assertIsEnabled()
        compose.waitForIdle()
        assertEquals(2, plays.size)
        compose.onNodeWithTag("day_simulation").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Close simulator").performClick()
        assertEquals(2, plays.size)
    }
    @Test fun customSimulationGapAndThreeTaskPresetLeaveRealSettingsUntouched() {
        open()
        val preferences = app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all.toMap()
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Sample nudge gap · 30 min"))
        compose.onNodeWithText("Sample nudge gap · 30 min").performScrollTo().assertIsDisplayed()
        capture("simulator-before-gap")
        compose.onNodeWithText("Sample nudge gap · 30 min").performClick()
        capture("simulator-gap")
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Save gap").performClick()
        jump("Just three tasks")
        repeat(3) { compose.onNodeWithText("+5 min").performClick() }
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("3 tasks. Your move: Read ten pages"))
        compose.onNodeWithText("3 tasks. Your move: Read ten pages").assertExists()
        capture("simulator-small-list")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("day_simulation").performScrollToNode(hasText("Sample nudge gap · 15 min"))
        compose.onNodeWithText("Sample nudge gap · 15 min").assertExists()
        assertEquals(preferences, app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all)
        assertEquals(1, runBlocking { app.repository.dao.allTasks().size })
    }
}
