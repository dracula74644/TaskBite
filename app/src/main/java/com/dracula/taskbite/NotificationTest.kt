package com.dracula.taskbite

import android.content.Context
import androidx.work.*
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class TestDeliveryPhase { IDLE, QUEUED, SENDING, SENT, BLOCKED, CANCELED, EXPIRED }
data class TestDeliveryState(val phase: TestDeliveryPhase, val dueAt: Long = 0) {
    val pending get() = phase == TestDeliveryPhase.QUEUED || phase == TestDeliveryPhase.SENDING
}

/** An explicit, real background delivery test. It never creates or changes a task. */
object NotificationTest {
    private const val WORK = "bite_notification_test"
    private fun prefs(context: Context) = context.getSharedPreferences("notification_test", Context.MODE_PRIVATE)
    suspend fun schedule(app: TaskBiteApp, now: Long = System.currentTimeMillis()): Boolean {
        if (!Reminders.allowed(app)) return false
        synchronized(this) {
            // Repeated taps keep the original countdown instead of postponing the test.
            if (state(app, now).pending) return true
            val token = UUID.randomUUID().toString()
            prefs(app).edit().clear().putString("token", token).putString("run", token)
                .putLong("at", now + 10_000).putString("phase", TestDeliveryPhase.QUEUED.name).apply()
            DeliveryStatus.event(app, "Test queued for 10 seconds. Press Home or lock your phone to check background delivery.")
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("test" to token))
                    .setInitialDelay(10, TimeUnit.SECONDS).build())
        }
        SpokenAlarms.schedule(app, now)
        return true
    }
    @Synchronized fun state(context: Context, now: Long = System.currentTimeMillis()): TestDeliveryState {
        nextAt(context, now)
        val p = prefs(context)
        var phase = runCatching { TestDeliveryPhase.valueOf(p.getString("phase", "")!!) }.getOrDefault(
            if (p.getString("token", null) != null) TestDeliveryPhase.QUEUED else TestDeliveryPhase.IDLE)
        val at = p.getLong("at", 0)
        if (phase == TestDeliveryPhase.SENDING && now - at > 15 * 60_000) {
            phase = TestDeliveryPhase.EXPIRED
            p.edit().putString("phase", phase.name).apply()
        }
        return TestDeliveryState(phase, at)
    }
    suspend fun cancel(app: TaskBiteApp): Boolean {
        synchronized(this) {
            if (prefs(app).getString("token", null) == null) return false
            prefs(app).edit().remove("token").putString("phase", TestDeliveryPhase.CANCELED.name).apply()
            WorkManager.getInstance(app).cancelUniqueWork(WORK)
            DeliveryStatus.event(app, "Test canceled. Your regular reminders are unchanged.")
        }
        // Keep the next real reminder scheduled; never cancel the shared alarm blindly.
        SpokenAlarms.schedule(app)
        return true
    }
    @Synchronized fun nextAt(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val p = prefs(context)
        if (p.getString("token", null) == null) return null
        val at = p.getLong("at", 0)
        if (now - at > 15 * 60_000) {
            p.edit().remove("token").putString("phase", TestDeliveryPhase.EXPIRED.name).apply()
            DeliveryStatus.event(context, "The test expired before Android ran it. Check background battery restrictions.")
            return null
        }
        return at
    }
    @Synchronized private fun claim(context: Context, now: Long): String? {
        val at = nextAt(context, now) ?: return null
        if (at > now) return null
        val p = prefs(context)
        val token = p.getString("token", null) ?: return null
        p.edit().remove("token").putString("run", token).putString("phase", TestDeliveryPhase.SENDING.name).apply()
        return token
    }
    @Synchronized private fun finish(context: Context, token: String, phase: TestDeliveryPhase) {
        val p = prefs(context)
        if (p.getString("run", null) == token) p.edit().putString("phase", phase.name).apply()
    }
    suspend fun deliver(app: TaskBiteApp, allowVoice: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val token = claim(app, now) ?: return false
        val reaction = VoiceLibrary(app).reaction(RoastKind.IDLE)
        val notice = Reminders.notifyPlanning(app, reaction.caption, reaction.clip == null, test = true, now = now)
        if (notice == null) {
            finish(app, token, TestDeliveryPhase.BLOCKED)
            DeliveryStatus.event(app, "Test blocked: Android notifications or the Task reminders channel are disabled.")
            return true
        }
        finish(app, token, TestDeliveryPhase.SENT)
        DeliveryStatus.event(app, "Test posted. Tap it, swipe it away, or clear all to test notification reactions.")
        if (!app.preferences.backgroundVoice) DeliveryStatus.audio(app, "Spoken task reactions is off. The notification is silent.")
        else if (allowVoice) ReminderAudio.play(app, reaction, preview = true)
        else ReminderAudio.enqueue(app, reaction, notice.token, preview = true, now = now)
        return true
    }
}
