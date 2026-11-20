package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class NudgeVarietyTest {
    @Test fun emptyListAndRepeatedDismissalsRotateAcrossFifteenMinuteCheckIns() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as TaskBiteApp
        installTestVoicePack(app)
        val p = app.preferences
        val manager = app.getSystemService(NotificationManager::class.java)
        val start = LocalDate.now().atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        app.foreground = false
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        p.apply { onboarded = true; reminders = true; awayNudges = true; awayGapMinutes = 15
            planningNudge = false; quietEnabled = false; backgroundSince = start; lastTaskActivity = 0
            backgroundVoice = false; notificationReactions = true }
        var actorStart = start
        for (actor in listOf("bite_original", "meme_sidekick", "meme_unfiltered").filter { id -> VoiceLibrary(app).actors().any { it.id == id } }) {
            p.voiceActor = actor
            p.backgroundSince = actorStart
            p.lastAwayNudge = 0
            val size = VoiceLibrary(app).available(RoastKind.PLAN_DAY).size
            val nudges = mutableListOf<String>()
            val clears = mutableListOf<String>()
            repeat(size * 2) { index ->
                val now = actorStart + (index + 1) * 15 * 60_000L
                ReminderDelivery.deliver(app, false, false, now)
                val notice = manager.activeNotifications.single { it.tag == "away_plan" }.notification
                val line = notice.extras.getString(Notification.EXTRA_TEXT)!!
                nudges += line
                assertTrue(line in VoiceLibrary(app).bundled(RoastKind.PLAN_DAY).map { it.caption })
                val token = notice.extras.getString(NotificationReactions.TOKEN)!!
                // Only clear the notification. Never add, start, or finish a task.
                val clear = NotificationReactions.dismissed(app, token, now + 1_000)
                assertNotNull(clear)
                assertEquals(clear!!.reaction.caption, clear.reaction.clip!!.caption)
                clears += clear.reaction.caption
                ReminderAudio.clear(app)
                manager.cancel("away_plan", 3)
                assertEquals(now + 15 * 60_000L, Reminders.nextAt(app, now))
            }
            nudges.chunked(size).forEach { assertEquals(size, it.toSet().size) }
            nudges.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            clears.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            actorStart += (size * 2 + 1) * 15 * 60_000L
        }
        assertEquals(0L, p.lastTaskActivity)
        assertTrue(app.repository.dao.allTasks().isEmpty())
        assertTrue(app.repository.dao.allEvents().isEmpty())
    }
}
