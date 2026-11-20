package com.dracula.taskbite

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Tracks only Bite's own notices. There is no notification-listener/read-access permission. */
data class BiteNotice(val token: String, val tag: String, val id: Int, val taskId: String?, val revision: Long,
    val at: Long, val state: String = "posted", val openedAt: Long = 0, val test: Boolean = false)

object NotificationReactions {
    const val TOKEN = "bite_notice_token"
    const val DISMISS = "com.dracula.taskbite.NOTICE_DISMISSED"
    const val DISMISSED_AT = "bite_dismissed_at"
    private fun prefs(context: Context) = context.getSharedPreferences("notification_runtime", Context.MODE_PRIVATE)
    private fun read(context: Context): List<BiteNotice> = runCatching {
        val array = JSONArray(prefs(context).getString("notices", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            BiteNotice(it.getString("token"), it.getString("tag"), it.getInt("id"), it.optString("task").takeIf(String::isNotBlank),
                it.optLong("revision"), it.getLong("at"), it.optString("state", "posted"), it.optLong("opened"), it.optBoolean("test"))
        } }
    }.getOrDefault(emptyList())
    private fun write(context: Context, notices: List<BiteNotice>) {
        val array = JSONArray()
        notices.takeLast(40).forEach { n -> array.put(JSONObject().apply {
            put("token", n.token); put("tag", n.tag); put("id", n.id); put("task", n.taskId.orEmpty())
            put("revision", n.revision); put("at", n.at); put("state", n.state); put("opened", n.openedAt); put("test", n.test)
        }) }
        prefs(context).edit().putString("notices", array.toString()).apply()
    }
    @Synchronized fun register(context: Context, tag: String, id: Int, task: Task?, now: Long, test: Boolean = false): BiteNotice {
        val n = BiteNotice(UUID.randomUUID().toString(), tag, id, task?.id, task?.updatedAt ?: 0, now, test = test)
        write(context, read(context).filter { (it.tag != tag || it.id != id) && now - it.at < 2 * 86_400_000L } + n)
        return n
    }
    @Synchronized fun find(context: Context, token: String) = read(context).firstOrNull { it.token == token }
    @Synchronized fun forget(context: Context, tag: String, id: Int) {
        val removed = read(context).filter { it.tag == tag && it.id == id }
        write(context, read(context) - removed.toSet())
        removed.forEach { ReminderAudio.cancel(context, it.token) }
    }
    @Synchronized fun clear(context: Context) { write(context, emptyList()); ReminderAudio.clear(context) }
    @Synchronized private fun transition(context: Context, token: String, state: String, now: Long): BiteNotice? {
        val records = read(context)
        val n = records.firstOrNull { it.token == token && it.state == "posted" && now - it.at in 0..(2 * 86_400_000L) } ?: return null
        val changed = n.copy(state = state, openedAt = if (state == "opened") now else 0)
        write(context, records.map { if (it.token == token) changed else it })
        ReminderAudio.cancel(context, token)
        return changed
    }
    fun content(context: Context, n: BiteNotice, add: Boolean = false): PendingIntent {
        val intent = if (add) WidgetNavigation.intent(context, WidgetNavigation.ADD) else Intent(context, MainActivity::class.java)
        intent.setData(Uri.parse("taskbite://notice/${n.token}/open")).putExtra(TOKEN, n.token)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun delete(context: Context, n: BiteNotice) = PendingIntent.getForegroundService(context, 0,
        Intent(context, SpokenReminderService::class.java).setAction(DISMISS)
            .setData(Uri.parse("taskbite://notice/${n.token}/dismiss")).putExtra(TOKEN, n.token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    suspend fun stillRelevant(app: TaskBiteApp, n: BiteNotice): Boolean {
        if (n.test) return true
        if (n.taskId == null) return app.repository.dao.pending().isEmpty() && app.preferences.lastTaskActivity <= n.at
        val task = app.repository.dao.task(n.taskId) ?: return false
        return !task.archived && task.completedAt == null && task.updatedAt == n.revision
    }
    suspend fun opened(app: TaskBiteApp, token: String, now: Long = System.currentTimeMillis()): VoiceReaction? {
        val n = transition(app, token, "opened", now) ?: return null
        app.getSystemService(NotificationManager::class.java).cancel(n.tag, n.id)
        if (!app.preferences.notificationReactions || (!n.test && app.preferences.teasingPaused(now)) || !stillRelevant(app, n)) return null
        DeliveryStatus.event(app, "Notification opened")
        return VoiceLibrary(app).reaction(RoastKind.NOTIFICATION_OPENED)
    }
    /** Fast claim before starting a playback job. Clear-all must not restart the first reaction. */
    @Synchronized fun claimDismissal(context: Context, token: String, now: Long): BiteNotice? {
        val n = transition(context, token, "dismissed", now) ?: return null
        if (!n.test && Preferences(context).teasingPaused(now)) {
            DeliveryStatus.dismissal(context, "Teasing is paused")
            return null
        }
        if (!Preferences(context).notificationReactions) {
            DeliveryStatus.dismissal(context, "Notification reactions is off. Enable it in Sidekick.")
            return null
        }
        if (!claimInteraction(context, now)) return null
        DeliveryStatus.event(context, "Notification cleared")
        DeliveryStatus.dismissal(context, "Clear received; preparing the recorded reaction")
        return n
    }
    suspend fun prepareDismissal(app: TaskBiteApp, n: BiteNotice, requestedAt: Long): QueuedAudio? {
        if (!app.preferences.notificationReactions || (!n.test && app.preferences.teasingPaused()) || !stillRelevant(app, n)) {
            DeliveryStatus.dismissal(app, "Clearing reaction canceled because the task or settings changed")
            return null
        }
        val reaction = VoiceLibrary(app).reaction(RoastKind.NOTIFICATION_CLEARED)
        // Show the actual recorded words, never a different written roast over audio.
        // Text-only Sweet reactions still provide feedback without another notification.
        Handler(Looper.getMainLooper()).post {
            if (app.preferences.notificationReactions && (n.test || !app.preferences.teasingPaused()) &&
                System.currentTimeMillis() - requestedAt in 0..ReminderAudio.INTERACTION_LIFETIME) {
                val caption = if (reaction.clip == null) "Text only: ${reaction.caption}" else reaction.caption
                Toast.makeText(app, caption, Toast.LENGTH_SHORT).show()
            }
        }
        return ReminderAudio.enqueue(app, reaction, n.token,
            interaction = true, preview = n.test, now = requestedAt)
    }
    suspend fun dismissed(app: TaskBiteApp, token: String, now: Long = System.currentTimeMillis()): QueuedAudio? {
        val n = claimDismissal(app, token, now) ?: return null
        return prepareDismissal(app, n, now)
    }
    @Synchronized private fun claimInteraction(context: Context, now: Long): Boolean {
        val p = prefs(context)
        if (now - p.getLong("lastInteraction", 0) < 5_000) return false
        p.edit().putLong("lastInteraction", now).apply()
        return true
    }
    /** Unanswered means no task action; Android provides no reliable notification read receipt. */
    suspend fun followUp(app: TaskBiteApp, nudge: AwayNudge, now: Long): Pair<String, RoastKind>? {
        if (!app.preferences.notificationReactions) return null
        val gap = AwayPolicy.gapMillis(app.preferences.awayGapMinutes)
        val records = synchronized(this) { read(app) }.filter { !it.test && it.taskId == nudge.task?.id &&
            it.state in listOf("posted", "opened") && now - maxOf(it.at, it.openedAt) >= gap &&
            app.preferences.lastTaskActivity <= maxOf(it.at, it.openedAt) }.sortedByDescending { maxOf(it.at, it.openedAt) }
        val active = app.getSystemService(NotificationManager::class.java).activeNotifications
        for (n in records) {
            if (!stillRelevant(app, n)) continue
            if (n.state == "posted" && active.none { it.tag == n.tag && it.id == n.id && it.notification.extras.getString(TOKEN) == n.token }) continue
            val title = if (n.state == "opened") "Opened, still waiting: ${nudge.task?.title ?: "one small plan"}"
                else "No response yet: ${nudge.task?.title ?: "your empty list"}"
            // A reminder being opened or left visible does not change the task situation.
            // In particular, an empty list must never get a pending-task recording.
            return title to nudge.scenario.kind
        }
        return null
    }
}

class NoticeDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationReactions.DISMISS) return
        val token = intent.getStringExtra(NotificationReactions.TOKEN) ?: return
        val app = context.applicationContext as TaskBiteApp
        val requestedAt = System.currentTimeMillis()
        // Start within the user-interaction callback; database and clip loading happen
        // after service promotion, rather than delaying this background-start request.
        try {
            ContextCompat.startForegroundService(app, Intent(app, SpokenReminderService::class.java)
                .setAction(NotificationReactions.DISMISS)
                .putExtra(NotificationReactions.TOKEN, token)
                .putExtra(NotificationReactions.DISMISSED_AT, requestedAt))
        } catch (_: RuntimeException) {
            val pending = goAsync()
            app.backgroundScope.launch {
                try {
                    if (NotificationReactions.dismissed(app, token, requestedAt) != null) {
                        DeliveryStatus.audio(app, "Android blocked direct dismissal playback. Trying an immediate alarm; late reactions are skipped.")
                        SpokenAlarms.schedule(app)
                    }
                } finally { pending.finish() }
            }
        }
    }
}
