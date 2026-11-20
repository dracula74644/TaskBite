package com.dracula.taskbite

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class FullBackupTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val repo get() = app.repository
    private fun record(kind: RoastKind, style: TeasingStyle, caption: String): VoiceClip {
        val sample = VoiceLibrary(app).bundled(RoastKind.CREATED, TeasingStyle.CLASSIC).first()
        val file = File.createTempFile("real-voice", ".wav", app.cacheDir)
        try {
            app.assets.open(sample.asset!!).use { input -> file.outputStream().use(input::copyTo) }
            return VoiceLibrary(app).importFile(kind, file, caption, style, caption)
        } finally { file.delete() }
    }
    private suspend fun exported(): ByteArray = ByteArrayOutputStream().also { FullBackup.export(app, repo, it) }.toByteArray()
    @Test fun fullRoundTripKeepsNewerTasksDeletionMarkersSettingsAndHumanAudio() = runBlocking<Unit> {
        val task = Task(title = "Original task", dueAt = System.currentTimeMillis() + 60_000,
            checklist = Checklist.encode(listOf(ChecklistItem(text = "Saved step", done = true))))
        val gone = Task(title = "Delete after exporting", dueAt = task.dueAt)
        repo.save(task); repo.save(gone)
        val clip = record(RoastKind.OVERDUE, TeasingStyle.SWEET, "Your task misses you, love.")
        val audio = clip.file!!.readBytes()
        app.preferences.apply { alertVolume = 35; teasingStyle = TeasingStyle.SWEET; customVoice = true; awayGapMinutes = 45 }
        val bytes = exported()
        val prepared = FullBackup.read(app, bytes.inputStream())
        assertEquals(2, prepared.contents.tasks.size); assertEquals(1, prepared.recordings.size)
        VoiceLibrary(app).remove(clip)
        app.preferences.apply { alertVolume = 80; awayGapMinutes = 15; backgroundSince = 987654L }
        repo.save(repo.dao.task(task.id)!!.copy(title = "Newer local task")); repo.delete(gone.id)
        FullBackup.restore(app, prepared)
        assertEquals("Newer local task", repo.dao.task(task.id)!!.title)
        assertNull(repo.dao.task(gone.id))
        assertEquals(35, app.preferences.alertVolume); assertEquals(45, app.preferences.awayGapMinutes)
        assertEquals(987654L, app.preferences.backgroundSince)
        val restored = VoiceLibrary(app).custom(RoastKind.OVERDUE, TeasingStyle.SWEET).single()
        assertArrayEquals(audio, restored.file!!.readBytes()); assertEquals(clip.caption, restored.caption)
        FullBackup.restore(app, prepared)
        assertEquals(1, VoiceLibrary(app).custom(RoastKind.OVERDUE, TeasingStyle.SWEET).size)
    }
    @Test fun optingOutOfSettingsRestoreKeepsCurrentPreferences() = runBlocking<Unit> {
        app.preferences.alertVolume = 25
        val prepared = FullBackup.read(app, exported().inputStream())
        app.preferences.alertVolume = 75
        FullBackup.restore(app, prepared, restoreSettings = false)
        assertEquals(75, app.preferences.alertVolume)
    }
    private fun entries(bytes: ByteArray): MutableMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            put(entry.name, zip.readBytes()); zip.closeEntry()
        } }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    @Test fun damagedTraversalAndOversizedArchivesAreRejectedBeforeAnyDataChanges() = runBlocking<Unit> {
        val task = Task(title = "Keep me safe", dueAt = 1000); repo.save(task)
        val before = repo.dao.allTasks()
        val clean = entries(exported())
        val damaged = clean.toMutableMap().apply { put("tasks.json", "{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { FullBackup.read(app, zip(damaged).inputStream()) }
        val traversal = clean.toMutableMap().apply { put("../taskbite.xml", "injected".toByteArray()) }
        assertThrows(IllegalStateException::class.java) { FullBackup.read(app, zip(traversal).inputStream()) }
        val expanded = clean.toMutableMap().apply { put("settings.json", ByteArray(20_000)) }
        assertThrows(IllegalArgumentException::class.java) { FullBackup.read(app, zip(expanded).inputStream()) }
        assertEquals(before, repo.dao.allTasks())
        assertFalse(File(app.filesDir, "taskbite.xml").exists())
    }
    @Test fun runtimeSettingsAndInvalidPreferenceTypesCannotBeImported() {
        val json = BackupSettings.capture(app)
        json.getJSONObject("taskbite").put("lastReminder", 1234)
        assertThrows(IllegalArgumentException::class.java) { BackupSettings.validate(json) }
        json.getJSONObject("taskbite").remove("lastReminder")
        json.getJSONObject("taskbite").put("alertVolume", 101)
        assertThrows(IllegalArgumentException::class.java) { BackupSettings.validate(json) }
        json.getJSONObject("taskbite").put("alertVolume", "50")
        assertThrows(IllegalArgumentException::class.java) { BackupSettings.validate(json) }
    }
    @Test fun recordingScriptsHaveFourDistinctShortLinesForEverySituationAndStyle() {
        val scripts = VoiceScripts(app)
        for (style in TeasingStyle.entries) for (kind in RoastKind.entries) {
            val lines = scripts.lines(kind, style)
            assertEquals(4, lines.size); assertEquals(4, lines.toSet().size)
            assertTrue(lines.all { it.trim().split(Regex("\\s+")).size < 12 })
        }
    }
    @Test fun personalPackIsRetainedAndIncludedInCompleteBackupWhenInstalled() = runBlocking<Unit> {
        val bundled = app.assets.list("voices/high_on_life").orEmpty().contains("catalog.json")
        PersonalVoicePacks.keepBundled(app)
        val saved = PersonalVoicePacks.snapshot(app)
        if (bundled) assertTrue("The installed game pack must be preserved", "packs/high_on_life/catalog.json" in saved)
        if (saved.isEmpty()) return@runBlocking // Public builds intentionally have no personal audio.
        assertTrue(PersonalVoicePacks.records(app).isNotEmpty())
        val prepared = FullBackup.read(app, exported().inputStream())
        assertTrue(prepared.packs.isNotEmpty())
        prepared.packs.forEach { (id, files) ->
            PersonalVoicePacks.validate(id, files)
            val actor = PersonalVoicePacks.records(app).single { it.getString("id") == id }
            val asset = actor.getJSONObject("categories").getJSONArray("CREATED").getJSONObject(0).getString("asset")
            assertTrue(PersonalVoicePacks.file(app, asset)!!.isFile)
        }
    }
    @Test fun locallySavedPackRemainsSelectableWithoutBeingInTheApk() = runBlocking<Unit> {
        val clip = VoiceLibrary(app).bundled(RoastKind.CREATED, TeasingStyle.CLASSIC).first()
        val bytes = app.assets.open(clip.asset!!).use { it.readBytes() }
        val files = mapOf("test.wav" to bytes, "CREDITS.txt" to "Human voice fixture from included CC BY pack".toByteArray(),
            "catalog.json" to JSONObject().put("actors", org.json.JSONArray().put(JSONObject()
                .put("id", "bite_original").put("name", "Saved pack").put("style", "CLASSIC")
                .put("categories", JSONObject().put("CREATED", org.json.JSONArray().put(JSONObject()
                    .put("asset", "voices/bite_original/test.wav").put("line", clip.caption)))))).toString().toByteArray())
        PersonalVoicePacks.install(app, "bite_original", files)
        app.preferences.voiceActor = "bite_original"
        val selected = VoiceLibrary(app).choose(RoastKind.CREATED, allowCustom = false)!!
        assertNull(selected.asset); assertNotNull(selected.file)
        assertArrayEquals(bytes, selected.file!!.readBytes()); assertEquals(clip.caption, selected.caption)
        val backup = FullBackup.read(app, exported().inputStream())
        assertTrue("bite_original" in backup.packs)
        assertNull(PersonalVoicePacks.file(app, "voices/bite_original/../../taskbite.db"))
    }
}
