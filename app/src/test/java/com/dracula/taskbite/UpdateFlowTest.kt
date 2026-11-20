package com.dracula.taskbite

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskBiteApp
    private fun begin() {
        app.preferences.actionVoice = false
        compose.onNodeWithText("Let's get something done").performScrollTo().performClick()
    }
    private fun open() {
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("App updates"))
        compose.onNodeWithText("App updates").performScrollTo().performClick()
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/reports/ui-previews/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun updateIntent() = compose.runOnUiThread {
        val activity = compose.activity
        val launch = activity.intent
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent(activity, MainActivity::class.java).setAction(AppUpdates.OPEN)))
        activity.intent = launch
    }
    @Test @Config(qualifiers = "w320dp-h600dp")
    fun compactUpdateControlsPersistAndKeepTasksAndHistory() {
        begin()
        runBlocking { app.repository.save(Task(title = "My existing task", dueAt = System.currentTimeMillis() + 86_400_000)) }
        val tasks = runBlocking { app.repository.dao.allTasks() }
        val history = runBlocking { app.repository.dao.allEvents() }
        open()
        compose.onNodeWithContentDescription("Automatic app updates").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Automatic app updates").assertIsOff()
        compose.onNodeWithText("Check now").assertIsDisplayed()
        capture("updates-compact")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Automatic app updates").assertIsOff()
        assertFalse(AppUpdates.automatic(app))
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNodeWithText("Voice & settings").assertExists()
        assertEquals(tasks, runBlocking { app.repository.dao.allTasks() })
        assertEquals(history, runBlocking { app.repository.dao.allEvents() })
    }
    @Suppress("DEPRECATION")
    @Test fun readyUpdateWaitsForInstallationPermissionAndAnExplicitInstallTap() {
        begin()
        AppUpdates.setAutomatic(app, false)
        val candidate = UpdateFixture.candidate()
        val own = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
        own.signingInfo = UpdateFixture.info(BuildConfig.VERSION_CODE.toLong()).signingInfo
        shadowOf(app.packageManager).installPackage(own)
        AppUpdates.saveCandidate(app, candidate)
        AppUpdates.apk(app, candidate).writeBytes(UpdateFixture.bytes)
        shadowOf(app.packageManager).setPackageArchiveInfo(AppUpdates.apk(app, candidate).path, UpdateFixture.info(candidate.versionCode))
        AppUpdates.ready(app, candidate)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        open()
        val activity = shadowOf(compose.activity)
        while (activity.nextStartedActivity != null) { /* Remove onboarding's external requests. */ }
        compose.onNodeWithTag("app_updates").performScrollToNode(hasText("Allow installation"))
        compose.onNodeWithText("Allow installation").performScrollTo().assertIsDisplayed()
        capture("updates-ready")
        assertNull(activity.nextStartedActivity)
        compose.onNodeWithText("Allow installation").performClick()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, activity.nextStartedActivity.action)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("app_updates").performScrollToNode(hasText("Install update"))
        assertNull(activity.nextStartedActivity)
        compose.onNodeWithText("Install update").performScrollTo().performClick()
        var installer: Intent? = null
        compose.waitUntil(10_000) { installer = installer ?: activity.nextStartedActivity; installer != null }
        assertEquals(Intent.ACTION_VIEW, installer!!.action)
        assertEquals("application/vnd.android.package-archive", installer!!.type)
        assertEquals("content", installer!!.data!!.scheme)
    }
    @Test fun updateNotificationOpensFromSimulatorAndWaitsForAnUnfinishedTaskDraft() {
        begin()
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNodeWithText("Test & troubleshoot").performClick()
        compose.onNodeWithText("Simulate a day").performClick()
        updateIntent()
        compose.onNodeWithTag("app_updates").assertExists()
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("New task", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Task")).performTextInput("Keep this update-time draft")
        updateIntent()
        compose.onNodeWithTag("app_updates").assertDoesNotExist()
        compose.onNodeWithText("Keep this update-time draft").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Keep this update-time draft").assertExists()
        compose.onNodeWithText("Add task").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("app_updates").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Keep this update-time draft", runBlocking { app.repository.dao.allTasks().single().title })
    }
    @Test fun turningOffTaskNotificationsKeepsTheSeparateUpdateNotice() {
        begin()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val candidate = UpdateFixture.candidate()
        AppUpdates.saveCandidate(app, candidate)
        AppUpdates.apk(app, candidate).writeBytes(UpdateFixture.bytes)
        AppUpdates.ready(app, candidate)
        Reminders.notifyPlanning(app, "A task reminder", test = true)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == "app_update" })
        assertTrue(manager.activeNotifications.any { it.notification.channelId == Reminders.CHANNEL })
        compose.onNodeWithText("Sidekick").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Task notifications"))
        compose.onNodeWithContentDescription("Task notifications").performScrollTo().performClick()
        assertFalse(app.preferences.reminders)
        assertTrue(manager.activeNotifications.any { it.tag == "app_update" })
        assertFalse(manager.activeNotifications.any { it.notification.channelId == Reminders.CHANNEL })
    }
}
