package com.dracula.taskbite

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, qualifiers = "w320dp-h780dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductivityFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }
    private val app get() = compose.activity.application as TaskBiteApp
    private fun onboard() { compose.onNodeWithText("Let's get something done").performScrollTo().performClick() }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val target = File("build/reports/ui-previews/$name.png").apply { parentFile!!.mkdirs() }
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun searchFindsChecklistTextAndCheckingAStepDoesNotFinishTheTask() {
        onboard()
        val step = ChecklistItem(text = "Bring water")
        val task = Task(title = "Plan picnic", dueAt = System.currentTimeMillis() + 60_000,
            checklist = Checklist.encode(listOf(step)))
        runBlocking {
            app.repository.save(task)
            app.repository.save(Task(title = "Unrelated task", dueAt = task.dueAt))
        }
        compose.onNodeWithContentDescription("Search tasks").performClick()
        compose.onNodeWithText("Search this task view").performTextInput("water")
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Plan picnic"))
        compose.onNodeWithText("Plan picnic").assertIsDisplayed()
        compose.onNodeWithText("Unrelated task").assertDoesNotExist()
        compose.onNodeWithText("Checklist · 0/1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Checklist: Bring water").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { Checklist.decode(app.repository.dao.task(task.id)!!.checklist).single().done } }
        assertNull(runBlocking { app.repository.dao.task(task.id)!!.completedAt })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Search this task view").assertTextContains("water")
        capture("checklist-search-compact")
        runBlocking { app.repository.complete(task.id, true) }
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithContentDescription("Search history").performClick()
        compose.onNodeWithText("Search history").performTextInput("water")
        compose.onAllNodesWithText("Plan picnic").onFirst().assertExists()
    }
    @Test fun editorSavesAnUnsubmittedChecklistStepAndRetainsItAfterRecreation() {
        onboard()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Packing")
        compose.onNodeWithText(" Checklist (optional)").performScrollTo().performClick()
        compose.onNodeWithText("New checklist step").performScrollTo().performTextInput("Bring charger")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Add task", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { app.repository.dao.allTasks().any { it.title == "Packing" } } }
        val task = runBlocking { app.repository.dao.allTasks().single { it.title == "Packing" } }
        assertEquals("Bring charger", Checklist.decode(task.checklist).single().text)
        compose.onNodeWithText("Later today").performScrollTo().assertIsDisplayed()
    }
    @Test fun pauseAndBackupControlsStayReachableWithLargeText() {
        onboard()
        compose.runOnUiThread { RuntimeEnvironment.setFontScale(1.8f) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Pause teasing"))
        compose.onNodeWithText("Pause teasing").performScrollTo().performClick()
        compose.onNodeWithText("For one hour").assertIsDisplayed().performClick()
        assertTrue(app.preferences.teasingPaused())
        assertTrue(app.preferences.deadlinesDuringPause)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Resume teasing"))
        compose.onNodeWithText("Resume teasing").assertIsDisplayed()
        capture("reminder-pause-large-text")
        compose.onNodeWithText("Resume teasing").performClick()
        assertFalse(app.preferences.teasingPaused())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(" Widgets & backups"))
        compose.onNodeWithText(" Widgets & backups").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Back up everything"))
        compose.onNodeWithText("Back up everything").assertIsDisplayed()
        capture("complete-backup-large-text")
    }
}
