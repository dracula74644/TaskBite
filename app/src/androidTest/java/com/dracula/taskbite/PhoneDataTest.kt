package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs only in the isolated Bite Test Lab package. Never opens the user's production database. */
@RunWith(AndroidJUnit4::class)
class PhoneDataTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TaskBiteApp
    @get:Rule val notificationPermission: TestRule = if (Build.VERSION.SDK_INT >= 33)
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else TestRule { base, _ -> base }
    @Before fun isolatedOnly() {
        check(app.packageName == "com.dracula.taskbite.devcheck") { "Phone checks require -PdeviceChecks=true." }
        app.getSharedPreferences("update_preferences", 0).edit().putBoolean("automatic", false).commit()
        AppUpdates.start(app)
        app.preferences.apply { onboarded = true; quietEnabled = false; reminders = true;
            actionVoice = false; backgroundVoice = false; planningNudge = false; awayNudges = false; teasingPausedUntil = 0 }
    }
    @Test fun databaseSurvivesClosingReopeningAndBackupRestore() = runBlocking<Unit> {
        val name = "instrumented-${System.nanoTime()}.db"
        var database = TaskDatabase.open(app, name)
        val task = Task(title = "Phone persistence test", dueAt = System.currentTimeMillis() + 60_000,
            checklist = Checklist.encode(listOf(ChecklistItem(text = "Check database", done = true))))
        try {
            TaskRepository(database).save(task)
            val exported = Backup.export(TaskRepository(database))
            database.close(); database = TaskDatabase.open(app, name)
            val repo = TaskRepository(database)
            assertEquals(task.checklist, repo.dao.task(task.id)!!.checklist)
            repo.complete(task.id, true)
            Backup.restore(repo, exported)
            assertNotNull(repo.dao.task(task.id)!!.completedAt)
            assertTrue(repo.dao.allEvents().any { it.kind == "COMPLETED" })
        } finally { database.close(); app.deleteDatabase(name) }
    }
    @Test fun completeBackupRestoresARealRecordingAndItsMatchingCaption() = runBlocking<Unit> {
        val library = VoiceLibrary(app)
        val source = library.bundled(RoastKind.STARTED, TeasingStyle.CLASSIC).first()
        val temporary = File.createTempFile("instrumented-voice-", ".wav", app.cacheDir)
        val words = source.caption!!
        app.assets.open(source.asset!!).use { input -> temporary.outputStream().use { input.copyTo(it) } }
        val original = try { library.importFile(RoastKind.STARTED, temporary, "Phone test", TeasingStyle.SWEET, words) }
            finally { temporary.delete() }
        try {
            val bytes = ByteArrayOutputStream().also { FullBackup.export(app, app.repository, it) }.toByteArray()
            val prepared = FullBackup.read(app, bytes.inputStream())
            val audio = original.file!!.readBytes()
            library.remove(original)
            FullBackup.restore(app, prepared, restoreSettings = false)
            val restored = library.custom(RoastKind.STARTED, TeasingStyle.SWEET).first { it.caption == words }
            assertArrayEquals(audio, restored.file!!.readBytes())
            assertEquals(words, restored.caption)
        } finally { library.custom(RoastKind.STARTED, TeasingStyle.SWEET).filter { it.label == "Phone test" }.forEach(library::remove) }
    }
    @Test fun deadlineNotificationIsPostedAndCanBeDismissedWithoutChangingTask() = runBlocking<Unit> {
        val task = Task(title = "Phone reminder test", dueAt = System.currentTimeMillis() - 60_000)
        app.repository.dao.put(task)
        app.preferences.lastReminder = 0
        try {
            ReminderDelivery.deliver(app, false, false)
            val manager = app.getSystemService(NotificationManager::class.java)
            val notification = manager.activeNotifications.first { it.tag == task.id }
            assertTrue(notification.notification.extras.getString(Notification.EXTRA_TITLE)!!.contains(task.title))
            val token = notification.notification.extras.getString(NotificationReactions.TOKEN)!!
            // This verifies the app's state transition. Hearing a physical swipe still needs the manual check.
            app.preferences.notificationReactions = false
            assertNull(NotificationReactions.claimDismissal(app, token, System.currentTimeMillis()))
            assertNull(app.repository.dao.task(task.id)!!.completedAt)
            assertTrue(app.repository.dao.taskEvents(task.id).isEmpty())
        } finally { Reminders.clear(app, task.id); app.repository.delete(task.id) }
    }
}
