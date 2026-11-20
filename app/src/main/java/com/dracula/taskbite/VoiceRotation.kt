package com.dracula.taskbite

import android.content.Context
import org.json.JSONArray

/** Each situation gets a full cycle. Persist it because Android can kill Bite between nudges. */
class VoiceRotation(context: Context) {
    private val prefs = context.getSharedPreferences("voice_rotation", Context.MODE_PRIVATE)

    fun choose(group: String, style: TeasingStyle, clips: List<VoiceClip>): VoiceClip? = synchronized(lock) {
        fun VoiceClip.id() = asset?.let { "asset:$it" } ?: file?.let { "file:${it.absolutePath}" } ?: "label:$label"
        val unique = clips.distinctBy { it.id() }
        if (unique.isEmpty()) return@synchronized null
        val ids = unique.map { it.id() }.toSet()
        val usedKey = "used.$group"
        val lastKey = "last.$group"
        val recentKey = "recent.${style.name}"
        val used = read(usedKey).filter { it in ids }.toMutableSet()
        if (used.containsAll(ids)) used.clear()
        val last = prefs.getString(lastKey, null)
        val recent = read(recentKey)
        val remaining = unique.filter { it.id() !in used }
        val different = remaining.filter { it.id() != last }.ifEmpty { remaining }
        // Prefer a different clip from the preceding nudge/dismissal, but never
        // reset this situation's cycle just because another situation used a clip.
        val fresh = different.filter { it.id() !in recent }.ifEmpty {
            different.filter { it.id() != recent.firstOrNull() }.ifEmpty { different }
        }
        val chosen = fresh.random()
        val id = chosen.id()
        used.add(id)
        prefs.edit().putString(usedKey, JSONArray(used.toList()).toString())
            .putString(lastKey, id)
            .putString(recentKey, JSONArray((listOf(id) + recent.filter { it != id }).take(2)).toString())
            .apply()
        chosen
    }

    private fun read(key: String): List<String> = runCatching {
        val values = JSONArray(prefs.getString(key, "[]"))
        (0 until values.length()).map { values.getString(it) }
    }.getOrDefault(emptyList())

    companion object { private val lock = Any() }
}
