package com.dracula.taskbite

import org.json.JSONObject
import java.io.File

/** Select once: the visible caption and every replay refer to this exact recording. */
data class VoiceReaction(val kind: RoastKind, val style: TeasingStyle, val caption: String, val clip: VoiceClip?, val missingReason: String? = null) {
    val explanation: String? get() = when {
        clip == null -> missingReason ?: "Text only · no ${style.label} recording for this situation. Add one in Voice settings."
        clip.caption == null -> "No transcript supplied. Edit this clip's caption in Voice settings."
        else -> null
    }
    fun saved(): String = JSONObject().apply {
        put("kind", kind.name); put("style", style.name); put("caption", caption)
        put("missingReason", missingReason)
        clip?.let { c -> put("clip", JSONObject().apply {
            put("label", c.label); put("asset", c.asset); put("file", c.file?.path); put("caption", c.caption)
        }) }
    }.toString()
    companion object {
        fun restore(saved: String?): VoiceReaction? = saved?.let { runCatching {
            val json = JSONObject(it)
            val clip = json.optJSONObject("clip")?.let { c ->
                VoiceClip(c.getString("label"), c.optString("asset").takeIf(String::isNotBlank),
                    c.optString("file").takeIf(String::isNotBlank)?.let(::File), c.optString("caption").takeIf(String::isNotBlank))
            }
            VoiceReaction(RoastKind.valueOf(json.getString("kind")), TeasingStyle.valueOf(json.getString("style")), json.getString("caption"), clip,
                json.optString("missingReason").takeIf(String::isNotBlank))
        }.getOrNull() }
    }
}
