package com.dracula.taskbite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** PCM playback fixture for public CI, which intentionally excludes the personal meme audio.
 * These assignments test transport/caption identity, not the semantic curation of production packs. */
internal fun installTestVoicePack(context: Context) {
    val source = context.assets.open("voices/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        .getJSONArray("actors").getJSONObject(0).getJSONObject("categories")
    val files = mutableMapOf<String, ByteArray>()
    val categories = JSONObject()
    RoastKind.entries.forEach { kind ->
        val pool = source.optJSONArray(kind.name) ?: source.getJSONArray("CREATED")
        categories.put(kind.name, JSONArray().apply {
            repeat(minOf(pool.length(), 2)) { index ->
                val clip = pool.getJSONObject(index)
                val asset = clip.getString("asset")
                val name = asset.substringAfterLast('/')
                files[name] = context.assets.open(asset).use { it.readBytes() }
                put(JSONObject().put("asset", "voices/bite_original/$name").put("line", clip.getString("line")))
            }
        })
    }
    files["catalog.json"] = JSONObject().put("actors", JSONArray().put(JSONObject()
        .put("id", "bite_original").put("name", "Test playback fixture").put("style", "CLASSIC")
        .put("categories", categories))).toString().toByteArray()
    PersonalVoicePacks.install(context, "bite_original", files)
    Preferences(context).voiceActor = "bite_original"
}

internal fun VoiceClip.pcm(context: Context): PcmAudio =
    (file?.inputStream() ?: context.assets.open(requireNotNull(asset))).use(PcmAudio::read)
