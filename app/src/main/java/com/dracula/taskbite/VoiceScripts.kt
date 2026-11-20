package com.dracula.taskbite

import android.content.Context
import org.json.JSONObject

/** Scripts are recording prompts, not playable voices. Every saved take remains a real recording. */
class VoiceScripts(context: Context) {
    private val scripts = context.assets.open("voices/recording-scripts.json").bufferedReader().use { JSONObject(it.readText()) }
    fun lines(kind: RoastKind, style: TeasingStyle): List<String> = scripts.getJSONObject(style.name).getJSONArray(kind.name).let { array ->
        List(array.length()) { array.getJSONObject(it).getString("line") }
    }
    fun nextLine(kind: RoastKind, style: TeasingStyle, existing: List<VoiceClip>): String {
        val options = lines(kind, style)
        return options.firstOrNull { line -> existing.none { it.caption == line } } ?: options[existing.size % options.size]
    }
    companion object { const val TARGET_PER_SITUATION = 4 }
}
