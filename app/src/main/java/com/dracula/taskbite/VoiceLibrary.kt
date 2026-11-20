package com.dracula.taskbite

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Audio is recorded by humans. No text is synthesized and no task text leaves the phone. */
data class VoiceClip(val label: String, val asset: String? = null, val file: File? = null, val caption: String? = null)
data class VoiceActor(val id: String, val name: String, val description: String = "")

class VoiceLibrary(private val context: Context) {
    private val root get() = File(context.filesDir, "voice_clips")
    private val preferences = Preferences(context)
    private val baseCatalog by lazy { readCatalog("voices/catalog.json") }
    private fun readCatalog(path: String) = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
    private fun JSONObject.actorRecords(): List<JSONObject> = getJSONArray("actors").let { list ->
        (0 until list.length()).map { list.getJSONObject(it) }
    }
    private val records by lazy {
        val base = baseCatalog.actorRecords()
        val personal = listOf("voices/high_on_life/catalog.json", "voices/bite_original/catalog.json", "voices/bite_sweet/catalog.json").flatMap { path ->
            runCatching { readCatalog(path).actorRecords() }.getOrDefault(emptyList())
        }
        base + personal + PersonalVoicePacks.records(context).filter { saved -> (base + personal).none { it.getString("id") == saved.getString("id") } }
    }
    private fun JSONObject.style() = optString("style", if (optString("id") == "bite_sweet" || optString("name") == "Bite Sweet") "SWEET" else "CLASSIC")
    fun actors(style: TeasingStyle? = null): List<VoiceActor> = records.filter { style == null || it.style() == style.name }
        .map { VoiceActor(it.getString("id"), it.getString("name"), it.optString("description")) }
    fun bundled(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle): List<VoiceClip> {
        val matching = records.filter { it.style() == style.name }
        val actor = matching.firstOrNull { it.getString("id") == preferences.actorFor(style) } ?: matching.firstOrNull() ?: return emptyList()
        val clips = actor.getJSONObject("categories").optJSONArray(kind.name)
            ?.takeIf { it.length() > 0 } ?: if (style == TeasingStyle.CLASSIC)
                actor.getJSONObject("categories").optJSONArray(kind.baseKind().name)?.takeIf { it.length() > 0 } else null
        return clips?.let { (0 until it.length()).mapNotNull { index -> it.getJSONObject(index).let { c ->
            val asset = c.getString("asset")
            if (actor.optBoolean("savedOnPhone")) PersonalVoicePacks.file(context, asset)?.let { VoiceClip(c.getString("line"), file = it, caption = c.getString("line")) }
            else VoiceClip(c.getString("line"), asset = asset, caption = c.getString("line"))
        } } }.orEmpty()
    }
    private fun metadata(file: File): JSONObject? = runCatching {
        JSONObject(File(file.parentFile, "${file.nameWithoutExtension}.json").readText())
    }.getOrNull()
    fun custom(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle): List<VoiceClip> = File(root, kind.name).listFiles().orEmpty()
        .filter { it.extension == "audio" && it.isFile && (metadata(it)?.optString("style") ?: "CLASSIC") == style.name }
        .sortedBy { it.name }.map { file ->
            val label = runCatching { File(file.parentFile, "${file.nameWithoutExtension}.txt").readText().take(80) }.getOrDefault("Custom recording")
            VoiceClip(label, file = file, caption = metadata(file)?.optString("caption")?.takeIf { it.isNotBlank() })
        }
    // Before clearing had its own slot, it used the user's leaving recordings.
    // Keep those working until they add a dedicated clearing recording.
    fun customForReaction(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle): List<VoiceClip> =
        custom(kind, style).ifEmpty {
            if (kind == RoastKind.NOTIFICATION_CLEARED) custom(RoastKind.ABANDON, style) else emptyList()
        }
    fun available(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle): List<VoiceClip> =
        (if (preferences.customVoice) customForReaction(kind, style) else emptyList()).ifEmpty { bundled(kind, style) }
            .distinctBy { it.asset ?: it.file?.absolutePath }

    fun choose(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle, allowCustom: Boolean = preferences.customVoice): VoiceClip? {
        val custom = if (allowCustom) customForReaction(kind, style) else emptyList()
        val clips = custom.ifEmpty { bundled(kind, style) }
        val source = if (custom.isNotEmpty()) "custom" else preferences.actorFor(style)
        return VoiceRotation(context).choose("${style.name}.$source.${kind.name}", style, clips)
    }
    fun reaction(kind: RoastKind, style: TeasingStyle = preferences.teasingStyle): VoiceReaction {
        val clip = choose(kind, style)
        val reason = if (clip == null && !preferences.customVoice && customForReaction(kind, style).isNotEmpty())
            "Text only · turn on Use my recordings in Voice settings to hear your saved clip." else null
        return VoiceReaction(kind, style, clip?.caption ?: clip?.let { "Recording: ${it.label}" } ?: Roasts.pick(kind, style), clip, reason)
    }
    fun importAudio(kind: RoastKind, uri: Uri, label: String, style: TeasingStyle = preferences.teasingStyle): VoiceClip =
        context.contentResolver.openInputStream(uri)?.use { importStream(kind, it, label, style) } ?: error("Could not read the selected file.")

    fun importStream(kind: RoastKind, input: InputStream, label: String, style: TeasingStyle = preferences.teasingStyle): VoiceClip {
        val temporary = File.createTempFile("voice-import-", ".audio", context.cacheDir)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CLIP_BYTES) { "Choose an audio clip under 5 MB." }
                    output.write(buffer, 0, count)
                }
            }
            return importFile(kind, temporary, label, style)
        } finally { temporary.delete() }
    }
    fun importFile(kind: RoastKind, input: File, label: String, style: TeasingStyle = preferences.teasingStyle, caption: String? = null): VoiceClip {
        validateAudio(input)
        return synchronized(storageLock) {
            require(custom(kind, style).size < 5) { "Keep up to five recordings per reaction and style. Remove one first." }
            val bytes = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0
            require(bytes + input.length() <= 20 * 1024 * 1024) { "Custom voices are limited to 20 MB. Remove a recording first." }
            val folder = File(root, kind.name).apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val target = File(folder, "$id.audio")
            val partial = File(folder, "$id.partial")
            val name = File(folder, "$id.txt")
            val meta = File(folder, "$id.json")
            try {
                input.copyTo(partial)
                name.writeText(label.trim().take(80).ifBlank { "Custom recording" })
                meta.writeText(JSONObject().put("style", style.name).put("caption", caption.orEmpty().trim().take(500)).toString())
                check(partial.renameTo(target)) { "Couldn't save the recording." }
                VoiceClip(name.readText(), file = target, caption = caption?.trim()?.take(500)?.takeIf { it.isNotEmpty() })
            } catch (e: Exception) { partial.delete(); target.delete(); name.delete(); meta.delete(); throw e }
        }
    }
    fun updateCaption(clip: VoiceClip, kind: RoastKind, style: TeasingStyle, caption: String) = synchronized(storageLock) {
        val file = requireNotNull(clip.file)
        require(file.canonicalFile.parentFile == File(root, kind.name).canonicalFile && file.isFile)
        if ((metadata(file)?.optString("style") ?: "CLASSIC") != style.name)
            require(custom(kind, style).size < 5) { "This style already has five recordings for this situation." }
        val target = File(file.parentFile, "${file.nameWithoutExtension}.json")
        val atomic = android.util.AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("style", style.name).put("caption", caption.trim().take(500)).toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun remove(clip: VoiceClip) = synchronized(storageLock) {
        val file = clip.file ?: return@synchronized
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        check(!file.exists() || file.delete()) { "Couldn't remove the recording." }
        File(file.parentFile, "${file.nameWithoutExtension}.txt").delete()
        File(file.parentFile, "${file.nameWithoutExtension}.json").delete()
        Unit
    }
    companion object {
        const val MAX_CLIP_BYTES = 5 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
        internal val storageLock = Any()
        internal fun validateAudio(input: File) {
            require(input.length() in 1..MAX_CLIP_BYTES.toLong()) { "Choose an audio clip under 5 MB." }
            val header = ByteArray(12)
            val headerSize = input.inputStream().use { stream ->
                var read = 0
                while (read < header.size) { val count = stream.read(header, read, header.size - read); if (count < 0) break; read += count }
                read
            }
            if (headerSize == 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") {
                val pcm = input.inputStream().use(PcmAudio::read)
                require(pcm.frameCount > 0 && pcm.frameCount.toLong() * 1000 / pcm.sampleRate <= 30_000) { "Use a recording up to 30 seconds long." }
            } else {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(input.path)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    require(duration != null && duration in 1..30_000 && metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") {
                        "Use a playable audio recording up to 30 seconds long."
                    }
                } finally { metadata.release() }
            }
        }
    }
}

fun RoastKind.displayName(): String = when (this) {
    RoastKind.OVERDUE -> "Task overdue"
    RoastKind.SNOOZE -> "Postponing a task"
    RoastKind.ABANDON -> "Leaving without progress"
    RoastKind.IDLE -> "Stalling"
    RoastKind.TINY -> "Avoiding a tiny task"
    RoastKind.CREATED -> "Task added"
    RoastKind.COMPLETED -> "Task completed"
    RoastKind.STARTED -> "Task started"
    RoastKind.SNOOZE_REPEAT -> "Repeated postponing"
    RoastKind.COMPLETED_LATE -> "Overdue task completed"
    RoastKind.PLAN_DAY -> "An empty day"
    RoastKind.NOTIFICATION_CLEARED -> "Notification cleared"
}

fun RoastKind.triggerDescription(): String = when (this) {
    RoastKind.CREATED -> "After saving a new task."
    RoastKind.STARTED -> "When you tap Start on a pending task."
    RoastKind.COMPLETED -> "When you check a task off."
    RoastKind.SNOOZE -> "When you move a task to Tomorrow."
    RoastKind.OVERDUE -> "When a deadline passes, in Today or a spoken reminder."
    RoastKind.IDLE -> "Idle in Today, unfinished work, small lists, or low-priority-only days while away."
    RoastKind.TINY -> "For overdue or idle tasks estimated under five minutes."
    RoastKind.ABANDON -> "After leaving without progress, or when you return."
    RoastKind.SNOOZE_REPEAT -> "When you postpone the same task for the third time or more."
    RoastKind.COMPLETED_LATE -> "When you finish a task after its deadline."
    RoastKind.PLAN_DAY -> "The daily planning prompt, or repeated empty-list nudges at your chosen gap."
    RoastKind.NOTIFICATION_CLEARED -> "When you swipe away a Bite reminder or use Clear all. One reaction; tasks stay unchanged."
}
