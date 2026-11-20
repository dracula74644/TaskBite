package com.dracula.taskbite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class QueuedAudio(val token: String, val notice: String, val reaction: VoiceReaction, val at: Long,
    val expires: Long, val interaction: Boolean, val preview: Boolean)

/** One short-lived, persistent handoff. Posting a notification never depends on starting audio. */
object ReminderAudio {
    const val TOKEN = "bite_audio_token"
    const val INTERACTION_LIFETIME = 5_000L
    private fun prefs(context: Context) = context.getSharedPreferences("reminder_audio", Context.MODE_PRIVATE)
    @Synchronized fun peek(context: Context, now: Long = System.currentTimeMillis()): QueuedAudio? {
        val saved = prefs(context).getString("pending", null) ?: return null
        val entry = runCatching { JSONObject(saved).let {
            QueuedAudio(it.getString("token"), it.getString("notice"), requireNotNull(VoiceReaction.restore(it.getString("reaction"))),
                it.getLong("at"), it.getLong("expires"), it.optBoolean("interaction"), it.optBoolean("preview"))
        } }.getOrNull()
        if (entry == null || entry.expires < now) {
            clear(context)
            DeliveryStatus.audio(context, if (entry?.interaction == true) "Dismissal voice skipped because Android couldn't start it promptly."
                else "Background voice expired before Android could play it. The notification was still posted.", entry?.interaction == true)
            return null
        }
        return entry
    }
    @Synchronized fun enqueue(context: Context, reaction: VoiceReaction, notice: String,
        interaction: Boolean = false, preview: Boolean = false, now: Long = System.currentTimeMillis()): QueuedAudio? {
        if (reaction.clip == null) { DeliveryStatus.audio(context, reaction.explanation ?: "No matching recording", interaction); return null }
        val item = QueuedAudio(UUID.randomUUID().toString(), notice, reaction,
            if (interaction) now else now + 5_000,
            now + if (interaction) INTERACTION_LIFETIME else 90_000, interaction, preview)
        prefs(context).edit().putString("pending", JSONObject().apply {
            put("token", item.token); put("notice", notice); put("reaction", reaction.saved()); put("at", item.at)
            put("expires", item.expires); put("interaction", interaction); put("preview", preview)
        }.toString()).apply()
        DeliveryStatus.audio(context, "Voice queued for background playback", interaction)
        return item
    }
    @Synchronized fun clear(context: Context) { prefs(context).edit().remove("pending").apply() }
    @Synchronized fun cancel(context: Context, notice: String) {
        val saved = prefs(context).getString("pending", null) ?: return
        if (runCatching { JSONObject(saved).optString("notice") == notice }.getOrDefault(true)) clear(context)
    }
    @Synchronized private fun take(context: Context, token: String?, now: Long): QueuedAudio? {
        val item = peek(context, now) ?: return null
        if (token != null && token != item.token) return null
        if (token == null && item.at > now) return null
        clear(context)
        return item
    }
    suspend fun playPending(app: TaskBiteApp, token: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        val item = take(app, token, now) ?: return false
        val p = app.preferences
        val n = NotificationReactions.find(app, item.notice)
        val enabled = if (item.interaction) p.notificationReactions else p.backgroundVoice
        val expectedState = if (item.interaction) "dismissed" else "posted"
        if (!enabled || !Reminders.allowed(app) || n == null || (!n.test && !p.reminders) || n.state != expectedState || !NotificationReactions.stillRelevant(app, n) ||
            (!item.interaction && !item.preview && app.foreground)) {
            DeliveryStatus.audio(app, "Voice canceled because the notification or settings changed", item.interaction)
            return true
        }
        if (!item.preview && p.isQuiet()) {
            DeliveryStatus.audio(app, "Quiet hours paused the voice", item.interaction)
            return true
        }
        play(app, item.reaction, item.preview, item.interaction)
        return true
    }
    suspend fun play(app: TaskBiteApp, reaction: VoiceReaction, preview: Boolean = false, interaction: Boolean = false) = withContext(Dispatchers.Main) {
        val voice = Voice(app)
        try {
            val result = voice.play(reaction, preview)
            DeliveryStatus.audio(app, if (result == VoiceResult.PLAYED) "Recording played" else result.message ?: reaction.explanation ?: result.name, interaction)
            result
        } finally { voice.close() }
    }
}

object DeliveryStatus {
    private fun prefs(context: Context) = context.getSharedPreferences("reminder_status", Context.MODE_PRIVATE)
    fun posted(context: Context, title: String) { prefs(context).edit().putLong("postedAt", System.currentTimeMillis()).putString("posted", title).apply() }
    fun audio(context: Context, message: String, interaction: Boolean = false) {
        prefs(context).edit().putLong("audioAt", System.currentTimeMillis()).putString("audio", message).apply()
        if (interaction) dismissal(context, message)
    }
    fun dismissal(context: Context, message: String) {
        prefs(context).edit().putLong("dismissalAt", System.currentTimeMillis()).putString("dismissal", message).apply()
    }
    fun event(context: Context, message: String) { prefs(context).edit().putString("event", message).apply() }
    fun worker(context: Context) { prefs(context).edit().putLong("workerAt", System.currentTimeMillis()).apply() }
    fun alarm(context: Context) { prefs(context).edit().putLong("alarmAt", System.currentTimeMillis()).apply() }
    fun report(context: Context): List<String> {
        val p = prefs(context)
        fun time(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
        return buildList {
            if (p.getLong("dismissalAt", 0) > 0) add("Last clearing reaction (${time(p.getLong("dismissalAt", 0))}): ${p.getString("dismissal", "")}")
            if (p.getLong("postedAt", 0) > 0) add("Last notification: ${time(p.getLong("postedAt", 0))} · ${p.getString("posted", "")}")
            p.getString("audio", null)?.let { add("Voice: $it") }
            p.getString("event", null)?.let { add(it) }
            if (p.getLong("workerAt", 0) > 0) add("Background check: ${time(p.getLong("workerAt", 0))}")
            if (p.getLong("alarmAt", 0) > 0) add("Alarm received: ${time(p.getLong("alarmAt", 0))}")
        }
    }
}
