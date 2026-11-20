package com.dracula.taskbite

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
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
        compose.onNodeWithText("Your tasks").assertExists()
    }
    @Test fun onboardingAndAllFourScreensOpen() {
        compose.onNodeWithText("Made By Dracula ❤️").performScrollTo().assertExists()
        finishOnboarding()
        compose.onNodeWithText("Calendar").performClick()
        compose.onNodeWithText("Your calendar").assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Task history").assertIsDisplayed()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Voice & settings").assertIsDisplayed()
    }
    @Test fun addCompleteRecreateAndReopenTask() {
        finishOnboarding()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Smoke mission")
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add task").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("today_tasks").performScrollToNode(hasText("All pending"))
        compose.onNodeWithText("All pending").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Complete Smoke mission").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(VoiceResult.VOICE_DISABLED.message!!).fetchSemanticsNodes().isNotEmpty() }
        // Intentional silence is explained inline, without a second popup obscuring task actions.
        compose.onAllNodesWithText(VoiceResult.VOICE_DISABLED.message!!).assertCountEquals(1)
        compose.onNodeWithContentDescription("Complete Smoke mission").performScrollTo()
        compose.onNodeWithContentDescription("Complete Smoke mission").assertIsDisplayed().performClick()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Completed").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Smoke mission").assertExists()
        compose.onNodeWithText("Reopen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reopen").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing finished yet.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun unfinishedDraftSurvivesActivityRecreation() {
        finishOnboarding()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Keep this draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Keep this draft").assertExists()
    }
    @Test fun alertVolumePersistsAndMuteExplainsWhyPreviewIsSilent() {
        finishOnboarding()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Spoken alert volume"))
        compose.onNodeWithContentDescription("Spoken alert volume")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(35f) }
        assertEquals(35, Preferences(compose.activity).alertVolume)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Spoken alert volume"))
        compose.onNodeWithText("35%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Spoken alert volume")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Muted").assertExists()
        assertEquals(0, Preferences(compose.activity).alertVolume)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(" Test voice"))
        compose.onNodeWithText(" Test voice").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Spoken alerts are muted. Raise the volume slider to test the voice.").assertExists()
    }
    @Test fun humanVoiceSettingsPersistAndExposeRecordingAndImport() {
        finishOnboarding()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Voice settings"))
        compose.onNodeWithText("Voice settings").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Give Bite a voice.").assertExists()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasContentDescription("Meghan Christian voice"))
        compose.onNodeWithContentDescription("Meghan Christian voice").performScrollTo().performClick()
        assertEquals("meghan", Preferences(compose.activity).voiceActor)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("voice_library").assertExists()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasContentDescription("Meghan Christian voice"))
        compose.onNodeWithContentDescription("Meghan Christian voice").assertIsSelected()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText("Use my recordings"))
        compose.onNodeWithContentDescription("Use my recordings").performClick()
        assertTrue(Preferences(compose.activity).customVoice)
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText(" Record a roast"))
        compose.onNodeWithText(" Record a roast").assertExists()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText(" Import audio"))
        compose.onNodeWithText(" Import audio").performClick()
        val picker = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertEquals("audio/*", picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.single())
    }
    @Test fun planningAndSweetStylePersistAndProvideAffectionateRecordingPrompts() {
        finishOnboarding()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sweet teasing"))
        compose.onNodeWithText("Sweet teasing").performScrollTo().performClick()
        assertEquals(TeasingStyle.SWEET, Preferences(compose.activity).teasingStyle)
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Plan my day"))
        compose.onNodeWithContentDescription("Plan my day").performScrollTo().assertIsOn().performClick()
        assertFalse(Preferences(compose.activity).planningNudge)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Plan my day"))
        compose.onNodeWithContentDescription("Plan my day").performScrollTo().assertIsOff()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sweet teasing"))
        compose.onNodeWithText("Sweet teasing").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Voice settings").performScrollTo().performClick()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText("Sweet teasing · your own voice"))
        compose.onNodeWithText("Sweet teasing · your own voice").assertExists()
        val line = Roasts.sweetLines.getValue(RoastKind.OVERDUE).first()
        compose.onNodeWithTag("voice_library").performScrollToNode(hasText(line))
        compose.onNodeWithText(line).assertExists()
    }
    private fun deliverWidgetIntent(action: String, id: String? = null) {
        compose.runOnUiThread {
            val scenarioIntent = compose.activity.intent
            ReflectionHelpers.callInstanceMethod<Any?>(compose.activity, "onNewIntent",
                ClassParameter.from(Intent::class.java, WidgetNavigation.intent(compose.activity, action, id)))
            // ActivityScenario identifies its activity by the original launch intent.
            compose.activity.intent = scenarioIntent
        }
        compose.waitForIdle()
    }
    @Test fun widgetQuickAddOpensEditorFromSettingsAndDoesNotReplayAfterRotation() {
        finishOnboarding()
        compose.onNodeWithText("Sidekick").performClick()
        deliverWidgetIntent(WidgetNavigation.ADD)
        compose.onNodeWithText("New task").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Widget draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Widget draft").assertExists()
        assertNull(compose.activity.widgetRequest)
    }
    @Test fun widgetTaskTapLoadsTheExistingTask() {
        finishOnboarding()
        val app = compose.activity.application as TaskBiteApp
        val task = Task(title = "Edit from widget", dueAt = System.currentTimeMillis() + 1000)
        runBlocking { app.repository.save(task) }
        deliverWidgetIntent(WidgetNavigation.EDIT, task.id)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Edit task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Edit from widget")).assertExists()
    }
    @Test fun widgetVoiceLaunchesRecognitionAndReviewsTranscriptBeforeSaving() {
        finishOnboarding()
        deliverWidgetIntent(WidgetNavigation.VOICE)
        val recognition = shadowOf(compose.activity).nextStartedActivity
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, recognition.action)
        compose.runOnUiThread {
            shadowOf(compose.activity).receiveResult(recognition, Activity.RESULT_OK,
                Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("A spoken widget task")))
        }
        compose.onNodeWithText("A spoken widget task").assertExists()
        val app = compose.activity.application as TaskBiteApp
        assertTrue(runBlocking { app.repository.dao.allTasks().isEmpty() })
        assertNull(compose.activity.widgetRequest)
    }
    @Test fun canceledVoiceCaptureDoesNotCreateATaskAndBlankDraftCannotSave() {
        finishOnboarding()
        deliverWidgetIntent(WidgetNavigation.VOICE)
        val launched = shadowOf(compose.activity).nextStartedActivity
        compose.runOnUiThread { shadowOf(compose.activity).receiveResult(launched, Activity.RESULT_CANCELED, null) }
        assertTrue(runBlocking { (compose.activity.application as TaskBiteApp).repository.dao.allTasks().isEmpty() })
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add task").performScrollTo().assertIsNotEnabled()
    }
    @Test fun calendarExportContainsTaskDetailsAndLeavesTaskAndHistoryUntouched() {
        finishOnboarding()
        val repo = (compose.activity.application as TaskBiteApp).repository
        val task = Task(title = "Calendar meeting", notes = "Bring notes", dueAt = System.currentTimeMillis() + 60_000, minutes = 25)
        runBlocking { repo.save(task) }
        val before = runBlocking { repo.dao.task(task.id) }
        val events = runBlocking { repo.dao.allEvents() }
        compose.onNodeWithText("Calendar").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Add to phone calendar"))
        compose.onNodeWithContentDescription("Add to phone calendar").performScrollTo().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(android.provider.CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals(task.title, intent.getStringExtra(android.provider.CalendarContract.Events.TITLE))
        assertEquals(task.notes, intent.getStringExtra(android.provider.CalendarContract.Events.DESCRIPTION))
        assertEquals(task.dueAt, intent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(task.dueAt + 25 * 60_000, intent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertEquals(before, runBlocking { repo.dao.task(task.id) })
        assertEquals(events, runBlocking { repo.dao.allEvents() })
    }
    @Test fun widgetAddClosesVoiceSettingsBeforeOpeningTheDraft() {
        finishOnboarding()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Voice settings"))
        compose.onNodeWithText("Voice settings").performScrollTo().performClick()
        compose.onNodeWithTag("voice_library").assertExists()
        deliverWidgetIntent(WidgetNavigation.ADD)
        compose.onNodeWithTag("voice_library").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Task")).assertExists()
    }
}
