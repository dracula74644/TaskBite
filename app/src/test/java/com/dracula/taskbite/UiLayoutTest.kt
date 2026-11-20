package com.dracula.taskbite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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

    @Test fun populatedScreensAndVoiceControlsRemainReachable() {
        capture("onboarding")
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        val repository = (compose.activity.application as TaskBiteApp).repository
        val now = System.currentTimeMillis()
        runBlocking {
            val task = Task(title = "Finish the project proposal", dueAt = now - 60_000, minutes = 100, priority = 2)
            repository.save(task)
            repository.start(task.id)
            repository.save(Task(title = "Reply to that email", dueAt = now + 60_000, minutes = 3))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reply to that email").fetchSemanticsNodes().isNotEmpty() }
        capture("today")
        compose.onNodeWithText("Calendar").performClick()
        capture("calendar")
        compose.onNodeWithText("History").performClick()
        capture("history")
        compose.onNodeWithText("Sidekick").performClick()
        capture("sidekick")
        compose.onNodeWithText("Test & troubleshoot").performClick()
        capture("testing-tools")
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("While I'm away"))
        compose.onNodeWithContentDescription("While I'm away").assertIsDisplayed()
        capture("away-controls")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nudge gap · 30 min"))
        compose.onNodeWithText("Nudge gap · 30 min").performClick()
        compose.onNodeWithText("Save gap").assertIsDisplayed()
        capture("nudge-gap")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(" Test voice"))
        capture("voice-controls")
        compose.onNodeWithText(" Test voice").assertIsDisplayed()
        compose.onNodeWithText("Voice settings").performClick()
        compose.onNodeWithText("Give Bite a voice.").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w320dp-h600dp")
    fun compactScreenCanScrollToVoiceControlsAndWrapTaskStatuses() {
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        val repository = (compose.activity.application as TaskBiteApp).repository
        runBlocking {
            val task = Task(title = "Compact screen task", dueAt = System.currentTimeMillis() - 60_000, minutes = 100, priority = 2)
            repository.save(task)
            repository.start(task.id)
        }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("100 min"))
        compose.onNodeWithText("100 min").performScrollTo().assertIsDisplayed()
        capture("today-compact")
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("While I'm away"))
        compose.onNodeWithContentDescription("While I'm away").assertIsDisplayed()
        capture("away-controls-compact")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nudge gap · 30 min"))
        compose.onNodeWithText("Nudge gap · 30 min").performClick()
        compose.onNodeWithText("Save gap").assertIsDisplayed()
        capture("nudge-gap-compact")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Voice settings"))
        compose.onNodeWithText("Voice settings").performScrollTo().assertIsDisplayed()
        capture("voice-controls-compact")
        compose.onNodeWithText("Voice settings").performClick()
        compose.onNodeWithText("Give Bite a voice.").assertIsDisplayed()
    }
}
