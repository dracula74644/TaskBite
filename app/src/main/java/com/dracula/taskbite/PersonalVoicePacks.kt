package com.dracula.taskbite

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Preserve personal voices on this phone across later public APK updates. */
object PersonalVoicePacks {
    val ids = setOf("high_on_life", "bite_original", "bite_sweet", "meme_sidekick", "meme_unfiltered")
    /** The update introduces a preloaded meme pack without changing Sweet or custom takes. */
    fun activateMemesOnce(context: Context) {
        val prefs = context.getSharedPreferences("taskbite", Context.MODE_PRIVATE)
        if (prefs.getBoolean("memePackV1", false)) return
        val installed = context.assets.list("voices/meme_sidekick").orEmpty().contains("catalog.json") ||
            File(root(context), "meme_sidekick/catalog.json").isFile
        if (!installed) return
        val p = Preferences(context)
        if (p.teasingStyle == TeasingStyle.CLASSIC && p.voiceActor in VoiceLibrary.SHORT_REACTION_ACTORS)
            p.voiceActor = "meme_sidekick"
        prefs.edit().putBoolean("memePackV1", true).apply()
    }
    private fun root(context: Context) = File(context.filesDir, "personal_voice_packs")
    fun file(context: Context, asset: String): File? {
        val parts = asset.split('/')
        if (parts.size != 3 || parts[0] != "voices" || parts[1] !in ids || !parts[2].matches(Regex("[A-Za-z0-9_-]+\\.wav"))) return null
        return File(root(context), "${parts[1]}/${parts[2]}").takeIf { it.isFile }
    }
    fun records(context: Context): List<JSONObject> = ids.flatMap { id ->
        runCatching {
            val catalog = File(root(context), "$id/catalog.json")
            require(catalog.length() in 1..131_072)
            val actors = JSONObject(catalog.readText()).getJSONArray("actors")
            List(actors.length()) { actors.getJSONObject(it).also { actor ->
                require(actor.getString("id") == id); actor.put("savedOnPhone", true)
            } }
        }.getOrDefault(emptyList())
    }
    fun snapshot(context: Context): Map<String, ByteArray> = synchronized(VoiceLibrary.storageLock) {
        buildMap { ids.forEach { id ->
            val folder = File(root(context), id)
            if (folder.isDirectory) folder.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                put("packs/$id/${file.name}", file.readBytes())
            }
        } }
    }
    fun validate(id: String, files: Map<String, ByteArray>) {
        require(id in ids && files.isNotEmpty() && files.size <= 70 && files.values.sumOf { it.size.toLong() } <= 20 * 1024 * 1024)
        require(files.keys.all { it == "catalog.json" || it == "CREDITS.txt" || it.matches(Regex("[A-Za-z0-9_-]+\\.wav")) })
        val catalog = requireNotNull(files["catalog.json"])
        require(catalog.size <= 131_072)
        val actors = JSONObject(catalog.toString(Charsets.UTF_8)).getJSONArray("actors")
        require(actors.length() == 1)
        val actor = actors.getJSONObject(0)
        require(actor.getString("id") == id && actor.getString("name").length in 1..80)
        require(actor.optString("style", if (id == "bite_sweet") "SWEET" else "CLASSIC") ==
            if (id == "bite_sweet") "SWEET" else "CLASSIC")
        val expected = mutableSetOf("catalog.json")
        if ("CREDITS.txt" in files) expected.add("CREDITS.txt")
        val categories = actor.getJSONObject("categories")
        categories.keys().forEach { kind ->
            RoastKind.valueOf(kind)
            val clips = categories.getJSONArray(kind)
            require(clips.length() in 1..5)
            repeat(clips.length()) { index ->
                val clip = clips.getJSONObject(index)
                val asset = clip.getString("asset")
                val name = asset.removePrefix("voices/$id/")
                require(asset == "voices/$id/$name" && name.matches(Regex("[A-Za-z0-9_-]+\\.wav")))
                require(clip.getString("line").length in 1..500)
                val bytes = requireNotNull(files[name])
                require(bytes.size in 1..VoiceLibrary.MAX_CLIP_BYTES)
                val pcm = bytes.inputStream().use(PcmAudio::read)
                require(pcm.frameCount > 0 && pcm.frameCount.toLong() * 1000 / pcm.sampleRate <= 30_000)
                expected.add(name)
            }
        }
        require(expected == files.keys)
    }
    fun install(context: Context, id: String, files: Map<String, ByteArray>): File? = synchronized(VoiceLibrary.storageLock) {
        validate(id, files)
        val destination = File(root(context), id)
        if (destination.isDirectory) return@synchronized null
        val temporary = File(root(context), ".${UUID.randomUUID()}")
        try {
            check(temporary.mkdirs())
            files.forEach { (name, bytes) -> File(temporary, name).writeBytes(bytes) }
            check(temporary.renameTo(destination)) { "Could not save the personal voice pack." }
            VoiceLibrary.catalogRevision.incrementAndGet()
            destination
        } finally { temporary.deleteRecursively() }
    }
    fun keepBundled(context: Context) {
        ids.forEach { id ->
            if (!File(root(context), id).isDirectory) runCatching {
                val prefix = "voices/$id"
                val names = context.assets.list(prefix).orEmpty()
                if ("catalog.json" in names) install(context, id, names.associateWith { name -> context.assets.open("$prefix/$name").use { it.readBytes() } })
            }.onFailure { android.util.Log.w("BiteVoices", "Could not preserve personal pack $id", it) }
        }
    }
}
