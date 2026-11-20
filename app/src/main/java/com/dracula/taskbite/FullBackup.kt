package com.dracula.taskbite

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable user preferences only: never restore permissions, pending work, or delivery timestamps. */
object BackupSettings {
    private val booleans = setOf("actionVoice", "backgroundVoice", "customVoice", "reminders", "awayNudges",
        "notificationReactions", "planningNudge", "quietEnabled", "deadlinesDuringPause")
    private val ranges = mapOf("alertVolume" to 0..100, "awayGapMinutes" to 15..1440,
        "planningHour" to 0..23, "planningMinute" to 0..59, "quietStart" to 0..23, "quietEnd" to 0..23)
    private val strings = setOf("voiceActor", "sweetVoiceActor", "teasingStyle")
    fun capture(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("taskbite", Context.MODE_PRIVATE)
        return JSONObject().put("taskbite", JSONObject().apply {
            (booleans + ranges.keys + strings).forEach { key -> prefs.all[key]?.let { put(key, it) } }
        }).put("automaticUpdates", context.getSharedPreferences("update_preferences", Context.MODE_PRIVATE).getBoolean("automatic", true))
    }
    fun validate(json: JSONObject) {
        require(json.keys().asSequence().toSet() == setOf("taskbite", "automaticUpdates"))
        require(json.get("automaticUpdates") is Boolean)
        val prefs = json.getJSONObject("taskbite")
        prefs.keys().forEach { key ->
            val value = prefs.get(key)
            require(when (key) {
                in booleans -> value is Boolean
                in ranges -> value is Int && value in ranges.getValue(key)
                "teasingStyle" -> value is String && value in TeasingStyle.entries.map { it.name }
                in strings -> value is String && value.matches(Regex("[a-z0-9_]{1,64}"))
                else -> false
            }) { "Invalid backup setting: $key" }
        }
    }
    fun apply(context: Context, json: JSONObject) {
        validate(json)
        val values = json.getJSONObject("taskbite")
        val edit = context.getSharedPreferences("taskbite", Context.MODE_PRIVATE).edit()
        (booleans + ranges.keys + strings).forEach { edit.remove(it) }
        values.keys().forEach { key -> when (val value = values.get(key)) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
        } }
        // A restored configuration starts fresh; a temporary break is not a portable preference.
        edit.remove("teasingPausedUntil")
        check(edit.commit()) { "Could not save restored settings. Tasks and recordings remain safe; retry the restore." }
        check(context.getSharedPreferences("update_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic", json.getBoolean("automaticUpdates")).commit()) { "Could not save the update preference." }
    }
}

object FullBackup {
    const val MAX_BYTES = 36 * 1024 * 1024
    private const val MAX_EXPANDED = 32 * 1024 * 1024
    private val mutex = Mutex()
    data class Recording(val id: String, val kind: RoastKind, val style: TeasingStyle, val label: String,
        val caption: String, val audio: ByteArray)
    data class Package(val tasks: String, val contents: Backup.Contents, val settings: JSONObject, val recordings: List<Recording>,
        val packs: Map<String, Map<String, ByteArray>> = emptyMap())
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun limited(input: InputStream, max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= max) { "Backup exceeds its size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    suspend fun export(context: Context, repository: TaskRepository, output: OutputStream) = mutex.withLock {
        PersonalVoicePacks.keepBundled(context)
        val entries = linkedMapOf<String, ByteArray>()
        entries["tasks.json"] = Backup.export(repository).toByteArray().also { require(it.size <= Backup.MAX_BYTES) }
        synchronized(VoiceLibrary.storageLock) {
            entries["settings.json"] = BackupSettings.capture(context).toString().toByteArray()
            val library = VoiceLibrary(context)
            RoastKind.entries.forEach { kind -> TeasingStyle.entries.forEach { style ->
                library.custom(kind, style).forEach { clip ->
                    val file = requireNotNull(clip.file)
                    val base = "voices/${kind.name}/${file.nameWithoutExtension}"
                    entries["$base.audio"] = file.readBytes().also { require(it.size in 1..VoiceLibrary.MAX_CLIP_BYTES) }
                    entries["$base.json"] = JSONObject().put("style", style.name).put("label", clip.label)
                        .put("caption", clip.caption.orEmpty()).toString().toByteArray()
                }
            } }
        }
        entries.putAll(PersonalVoicePacks.snapshot(context))
        require(entries.values.sumOf { it.size.toLong() } <= MAX_EXPANDED)
        val manifest = JSONObject().put("app", "TaskBite").put("formatVersion", 1)
            .put("files", JSONObject().apply { entries.forEach { (name, bytes) ->
                put(name, JSONObject().put("size", bytes.size).put("sha256", hash(bytes)))
            } })
        ZipOutputStream(output).use { zip ->
            (entries + ("manifest.json" to manifest.toString().toByteArray())).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    fun read(context: Context, input: InputStream): Package {
        // Bound both compressed input and expanded entries; no archive path is used as a disk path.
        val source = limited(input, MAX_BYTES)
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(source.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entries.size < 500 && entry.name !in entries) { "Duplicate or unsupported backup entry." }
                val name = entry.name
                val max = when {
                    name == "tasks.json" -> Backup.MAX_BYTES
                    name == "settings.json" -> 16_384
                    name == "manifest.json" -> 128 * 1024
                    name.matches(Regex("voices/[A-Z_]+/[0-9a-f-]{36}\\.audio")) -> VoiceLibrary.MAX_CLIP_BYTES
                    name.matches(Regex("voices/[A-Z_]+/[0-9a-f-]{36}\\.json")) -> 4096
                    name.matches(Regex("packs/[a-z_]+/[A-Za-z0-9_-]+\\.wav")) -> VoiceLibrary.MAX_CLIP_BYTES
                    name.matches(Regex("packs/[a-z_]+/(catalog\\.json|CREDITS\\.txt)")) -> 131_072
                    else -> error("Unsupported backup entry.")
                }
                val bytes = limited(zip, minOf(max, MAX_EXPANDED - total))
                total += bytes.size; entries[name] = bytes; zip.closeEntry()
            }
        }
        val manifest = JSONObject(requireNotNull(entries.remove("manifest.json")) { "Missing backup manifest." }.toString(Charsets.UTF_8))
        require(manifest.getString("app") == "TaskBite" && manifest.getInt("formatVersion") == 1)
        val files = manifest.getJSONObject("files")
        require(files.keys().asSequence().toSet() == entries.keys) { "Backup file list is incomplete." }
        entries.forEach { (name, bytes) ->
            val spec = files.getJSONObject(name)
            require(spec.getInt("size") == bytes.size && spec.getString("sha256") == hash(bytes)) { "Backup is damaged: $name" }
        }
        val tasks = requireNotNull(entries["tasks.json"]).toString(Charsets.UTF_8)
        val contents = Backup.decode(tasks)
        val settings = JSONObject(requireNotNull(entries["settings.json"]).toString(Charsets.UTF_8)).also(BackupSettings::validate)
        val audioEntries = entries.filterKeys { it.endsWith(".audio") }
        val packEntries = entries.filterKeys { it.startsWith("packs/") }
        val packs = packEntries.entries.groupBy { it.key.split('/')[1] }.mapValues { (_, files) ->
            files.associate { it.key.substringAfterLast('/') to it.value }
        }
        packs.forEach { (id, files) -> PersonalVoicePacks.validate(id, files) }
        require(entries.size == 2 + audioEntries.size * 2 + packEntries.size) { "Unmatched recording metadata." }
        val recordings = audioEntries.map { (name, bytes) ->
            val parts = name.split('/')
            val id = parts[2].removeSuffix(".audio")
            require(UUID.fromString(id).toString() == id)
            val kind = RoastKind.valueOf(parts[1])
            val metadata = JSONObject(requireNotNull(entries[name.removeSuffix(".audio") + ".json"]).toString(Charsets.UTF_8))
            val style = TeasingStyle.valueOf(metadata.getString("style"))
            val label = metadata.getString("label"); val caption = metadata.getString("caption")
            require(label.isNotBlank() && label.length <= 80 && caption.length <= 500)
            val temporary = File.createTempFile("backup-voice-", ".audio", context.cacheDir)
            try { temporary.writeBytes(bytes); VoiceLibrary.validateAudio(temporary) } finally { temporary.delete() }
            Recording(id, kind, style, label, caption, bytes)
        }
        require(recordings.groupBy { it.kind to it.style }.values.all { it.size <= 5 })
        require(recordings.sumOf { it.audio.size.toLong() } <= VoiceLibrary.MAX_TOTAL_BYTES)
        return Package(tasks, contents, settings, recordings, packs)
    }
    suspend fun restore(app: TaskBiteApp, backup: Package, restoreSettings: Boolean = true): Int =
        withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
            // Complete validation and disk writes before merging tasks. Existing recordings are never overwritten.
            Backup.decode(backup.tasks); BackupSettings.validate(backup.settings)
            val added = mutableListOf<File>()
            val installedPacks = mutableListOf<File>()
            var tasksMerged = false
            try {
                synchronized(VoiceLibrary.storageLock) {
                    val library = VoiceLibrary(app)
                    val additions = backup.recordings.filter { incoming ->
                        library.custom(incoming.kind, incoming.style).none { hash(requireNotNull(it.file).readBytes()) == hash(incoming.audio) }
                    }.distinctBy { Triple(it.kind, it.style, hash(it.audio)) }
                    additions.groupBy { it.kind to it.style }.forEach { (key, group) ->
                        require(library.custom(key.first, key.second).size + group.size <= 5) {
                            "${key.first.displayName()} would exceed five ${key.second.label} recordings. Remove an unwanted clip before restoring."
                        }
                    }
                    val root = File(app.filesDir, "voice_clips")
                    val used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    require(used + additions.sumOf { it.audio.size.toLong() + 4096 } <= VoiceLibrary.MAX_TOTAL_BYTES) { "Recordings would exceed the 20 MB voice limit." }
                    additions.forEach { item ->
                        val folder = File(root, item.kind.name).apply { check(mkdirs() || isDirectory) }
                        val id = UUID.randomUUID().toString()
                        val audio = File(folder, "$id.audio")
                        val label = File(folder, "$id.txt")
                        val meta = File(folder, "$id.json")
                        val partial = File(folder, "$id.partial")
                        added.addAll(listOf(audio, label, meta, partial))
                        partial.writeBytes(item.audio); label.writeText(item.label)
                        meta.writeText(JSONObject().put("style", item.style.name).put("caption", item.caption).toString())
                        check(partial.renameTo(audio)) { "Could not save restored recording." }
                    }
                }
                backup.packs.forEach { (id, files) -> PersonalVoicePacks.install(app, id, files)?.let(installedPacks::add) }
                val count = Backup.restore(app.repository, backup.tasks)
                tasksMerged = true
                if (restoreSettings) BackupSettings.apply(app, backup.settings)
                AppUpdates.start(app)
                Reminders.schedule(app, app.repository)
                count
            } catch (error: Exception) {
                if (!tasksMerged) synchronized(VoiceLibrary.storageLock) {
                    added.forEach { it.delete() }; installedPacks.forEach { it.deleteRecursively() }
                }
                throw error
            }
        } }
}
