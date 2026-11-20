package com.dracula.taskbite

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecurringUndoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repo get() = (compose.activity.application as TaskBiteApp).repository
    private fun start() { compose.onNodeWithText("Let's get something done").performScrollTo().performClick() }
    @Test fun createRepeatingTaskCompleteAndUndoThroughTheScreen() {
        start()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Daily reading")
        compose.onNodeWithText("Does not repeat").performScrollTo().performClick()
        compose.onNodeWithText("Every day").performClick()
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add task").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasContentDescription("Complete Daily reading"))
        compose.onNodeWithContentDescription("Complete Daily reading").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val target = java.io.File("build/reports/ui-previews/quick-undo.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Undo").assertIsDisplayed()
        runBlocking { assertEquals(2, repo.dao.allTasks().size) }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty() }
        runBlocking { val t = repo.dao.allTasks().single(); assertEquals(RepeatRule.DAILY.name, t.repeatRule); assertNull(t.completedAt) }
    }
    @Test fun deleteCanBeUndoneImmediatelyWithHistoryRestored() {
        start()
        val t = Task(title = "Undo my deletion", dueAt = System.currentTimeMillis() + 60_000)
        runBlocking { repo.save(t) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(t.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText(t.title))
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val target = java.io.File("build/reports/ui-previews/quick-undo.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(t.title).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertNull(repo.dao.deletion(t.id)); assertEquals(1, repo.dao.taskEvents(t.id).size) }
    }
}
