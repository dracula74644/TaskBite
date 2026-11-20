package com.dracula.taskbite

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeleteFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = compose.activity.application as TaskBiteApp

    private fun finishOnboarding() {
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Your tasks").assertExists()
    }
    private fun seedTask(title: String): Task {
        val task = Task(title = title, dueAt = System.currentTimeMillis() + 60_000)
        runBlocking { app.repository.save(task) }
        compose.waitForIdle()
        return task
    }

    @Test fun deleteFromTaskMenuAsksFirstAndCancelKeepsEverything() {
        finishOnboarding()
        val task = seedTask("Keep me around")
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Keep me around"))
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.onNodeWithText("Delete \"Keep me around\"?").assertIsDisplayed()
        compose.onNodeWithText("Removes the task and its history. Undo is available for 10 seconds. After that, older backups won't bring it back.").assertIsDisplayed()
        compose.onNodeWithText("Keep task").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Keep me around").assertExists()
        runBlocking {
            assertNotNull(app.repository.dao.task(task.id))
            assertNull(app.repository.dao.deletion(task.id))
        }
    }

    @Test fun deleteFromTaskMenuRemovesTaskHistoryAndRecordsTombstone() {
        finishOnboarding()
        val task = seedTask("Erase me fully")
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Erase me fully"))
        compose.onNodeWithContentDescription("Task options").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Erase me fully").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            assertNull(app.repository.dao.task(task.id))
            assertTrue(app.repository.dao.allEvents().none { it.taskId == task.id })
            assertNotNull(app.repository.dao.deletion(task.id))
        }
    }

    @Test fun deleteFromEditorClosesTheEditorForThatTask() {
        finishOnboarding()
        val task = seedTask("Edited then deleted")
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("Edited then deleted"))
        compose.onNodeWithText("Edited then deleted").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete task").performClick()
        compose.onNodeWithText("Delete task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit task").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertNull(app.repository.dao.task(task.id)) }
    }

    @Test fun deleteCompletedTaskFromHistory() {
        finishOnboarding()
        val task = seedTask("Done and gone")
        runBlocking { app.repository.complete(task.id, true) }
        compose.waitForIdle()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Completed").performClick()
        compose.onNodeWithContentDescription("Delete Done and gone").performClick()
        compose.onNodeWithText("Delete \"Done and gone\"?").assertIsDisplayed()
        compose.onNodeWithText("Delete task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing finished yet.").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertNull(app.repository.dao.task(task.id))
            assertNotNull(app.repository.dao.deletion(task.id))
        }
    }
}
