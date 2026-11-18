package com.dracula.taskbite

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun finishOnboarding() {
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Less later.\nMore done.").assertExists()
    }
    @Test fun onboardingAndAllFourScreensOpen() {
        compose.onNodeWithText("Made By Dracula ❤️").performScrollTo().assertExists()
        finishOnboarding()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Someday, scheduled.").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Receipts of progress.").assertIsDisplayed()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Manage the attitude.").assertIsDisplayed()
    }
    @Test fun addCompleteRecreateAndReopenTask() {
        finishOnboarding()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Smoke mission")
        compose.onNodeWithText("Add mission").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add mission").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Smoke mission"))
        compose.onNodeWithContentDescription("Complete Smoke mission").performClick()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Completed").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Smoke mission").assertExists()
        compose.onNodeWithText("Reopen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing finished yet.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun unfinishedDraftSurvivesActivityRecreation() {
        finishOnboarding()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Keep this draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Keep this draft").assertExists()
    }
}
