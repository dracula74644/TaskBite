package com.dracula.taskbite

import android.Manifest
import android.app.NotificationManager
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AwayFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskBiteApp
    private fun open() {
        app.preferences.apply { actionVoice = false; reminders = false; planningNudge = false; quietEnabled = false }
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
    }
    @Test fun awaySwitchClearsExistingNudgeAndPersistsAcrossRecreation() {
        open()
        val p = app.preferences
        p.reminders = true
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val task = Task(title = "Reminder example", dueAt = System.currentTimeMillis() + 3_600_000)
        Reminders.notify(app, task, "One small step.", awayTitle = AwayScenario.NO_PROGRESS.title(task))
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == "away_task" })
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("While I'm away"))
        compose.onNodeWithContentDescription("While I'm away").assertIsOn().performClick()
        assertFalse(Preferences(app).awayNudges)
        assertTrue(manager.activeNotifications.none { it.tag == "away_task" })
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("While I'm away"))
        compose.onNodeWithContentDescription("While I'm away").assertIsOff()
        compose.onNodeWithContentDescription("While I'm away").performClick()
        assertTrue(Preferences(app).awayNudges)
    }
    @Test fun homeAndReturnTrackInactivityButTaskActionsAndExternalPickersDoNotCountAsAbandonment() {
        open()
        val p = app.preferences
        val now = System.currentTimeMillis()
        val task = Task(title = "Work for later", dueAt = now + 43_200_000, updatedAt = now - 3_600_000)
        runBlocking { app.repository.dao.put(task) }
        compose.waitUntil(10_000) { compose.activity.pendingCount == 1 }
        p.lastTaskActivity = now - 3_600_000
        compose.runOnUiThread { ReflectionHelpers.setField(compose.activity, "sessionStart", now - 20_000) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(p.backgroundSince >= now)
        assertTrue(p.abandonedAt >= now)
        assertEquals(p.backgroundSince, Preferences(app).backgroundSince)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(0L, p.backgroundSince)
        assertTrue(app.foreground)

        p.abandonedAt = 0
        runBlocking { app.repository.save(task.copy(title = "Updated work")) }
        compose.runOnUiThread { ReflectionHelpers.setField(compose.activity, "sessionStart", now - 20_000) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(p.backgroundSince >= now)
        assertEquals(0L, p.abandonedAt)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { compose.activity.externalAction() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(p.backgroundSince >= now)
        assertEquals(0L, p.abandonedAt)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(0L, p.backgroundSince)
        assertEquals(1, runBlocking { app.repository.dao.allTasks().size })
    }
    @Test fun gapCanBeCustomizedValidatedCanceledAndRestoredAfterRotation() {
        open()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nudge gap · 30 min"))
        compose.onNodeWithText("Nudge gap · 30 min").performClick()
        compose.onNode(hasSetTextAction() and hasText("Minutes")).performTextReplacement("0")
        compose.onNodeWithText("Save gap").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Minutes")).performTextReplacement("1441")
        compose.onNodeWithText("Save gap").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Minutes")).performTextReplacement("47")
        compose.onNodeWithText("Save gap").assertIsEnabled().performClick()
        assertEquals(47, Preferences(app).awayGapMinutes)
        compose.activityRule.scenario.recreate()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Nudge gap · 47 min"))
        compose.onNodeWithText("Nudge gap · 47 min").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(47, Preferences(app).awayGapMinutes)
        compose.onNodeWithText("Nudge gap · 47 min").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Save gap").performClick()
        assertEquals(15, Preferences(app).awayGapMinutes)
    }
}
