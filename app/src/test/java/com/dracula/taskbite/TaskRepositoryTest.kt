package com.dracula.taskbite

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TaskRepositoryTest {
    private lateinit var db: TaskDatabase
    private lateinit var repository: TaskRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).allowMainThreadQueries().build()
        repository = TaskRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun task(title: String = "Take a short walk") = Task(title = title, dueAt = System.currentTimeMillis() + 60_000)

    @Test fun completeIsIdempotentAndPreservesHistory() = runBlocking {
        val t = task()
        repository.save(t)
        assertTrue(repository.complete(t.id, true))
        assertFalse(repository.complete(t.id, true))
        assertNotNull(repository.dao.task(t.id)!!.completedAt)
        assertEquals(1, repository.dao.allEvents().count { it.kind == "COMPLETED" })
        repository.complete(t.id, false)
        assertNull(repository.dao.task(t.id)!!.completedAt)
        assertEquals(setOf("CREATED", "COMPLETED", "REOPENED"), repository.dao.allEvents().map { it.kind }.toSet())
    }
    @Test fun staleEditorCannotEraseCompletion() = runBlocking {
        val t = task()
        repository.save(t)
        repository.complete(t.id, true)
        repository.save(t.copy(title = "Edited after completion"))
        assertNotNull(repository.dao.task(t.id)!!.completedAt)
    }
    @Test fun archivedTasksAreRestorableAndExcludedFromReminders() = runBlocking {
        val t = task()
        repository.save(t)
        repository.archive(t.id, true)
        assertTrue(repository.dao.pending().isEmpty())
        assertFalse(repository.save(t.copy(title = "Stale draft")))
        assertFalse(repository.complete(t.id, true))
        repository.archive(t.id, false)
        assertEquals(1, repository.dao.pending().size)
        assertEquals(3, repository.dao.allEvents().size)
    }
    @Test fun snoozeUsesNextCalendarDayAcrossDaylightSavings() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val due = LocalDateTime.of(2030, 3, 9, 9, 0).atZone(ZoneId.systemDefault())
            val t = task().copy(dueAt = due.toInstant().toEpochMilli())
            repository.save(t)
            repository.snooze(t.id)
            val shifted = Instant.ofEpochMilli(repository.dao.task(t.id)!!.dueAt).atZone(ZoneId.systemDefault())
            assertEquals(LocalDate.of(2030, 3, 10), shifted.toLocalDate())
            assertEquals(9, shifted.hour)
            assertEquals(23, Duration.between(due, shifted).toHours())
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun databaseReopenRetainsTaskAndAllHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "continuity-${System.nanoTime()}.db"
        var fileDb = TaskDatabase.open(context, name)
        try {
            var repo = TaskRepository(fileDb)
            val t = task()
            repo.save(t)
            repo.start(t.id)
            repo.complete(t.id, true)
            fileDb.close()
            fileDb = TaskDatabase.open(context, name)
            repo = TaskRepository(fileDb)
            assertEquals(t.title, repo.dao.task(t.id)!!.title)
            assertNotNull(repo.dao.task(t.id)!!.completedAt)
            assertEquals(3, repo.dao.allEvents().size)
        } finally { fileDb.close(); context.deleteDatabase(name) }
    }
    @Test fun exportRestoreIsLosslessAndIdempotent() = runBlocking {
        val t = task("Unicode: café 🦇")
        repository.save(t.copy(notes = "Line one\nLine two", minutes = 3))
        repository.complete(t.id, true)
        repository.archive(t.id, true)
        val json = Backup.export(repository)
        val other = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).build()
        try {
            val target = TaskRepository(other)
            assertEquals(1, Backup.restore(target, json))
            assertEquals(repository.dao.allTasks(), target.dao.allTasks())
            assertEquals(repository.dao.allEvents().toSet(), target.dao.allEvents().toSet())
            assertEquals(0, Backup.restore(target, json))
            assertEquals(3, target.dao.allEvents().size)
        } finally { other.close() }
    }
    @Test fun olderBackupDoesNotOverwriteNewerTask() = runBlocking {
        val t = task()
        repository.save(t)
        val old = Backup.export(repository)
        val updated = repository.dao.task(t.id)!!.copy(title = "New title", updatedAt = System.currentTimeMillis() + 1000)
        repository.dao.put(updated)
        assertEquals(0, Backup.restore(repository, old))
        assertEquals("New title", repository.dao.task(t.id)!!.title)
    }
    @Test fun malformedBackupCannotPartiallyImport() = runBlocking {
        val original = task()
        repository.save(original)
        val valid = task("Another task")
        val invalid = task(" ")
        val json = Backup.encode(Backup.Contents(listOf(valid, invalid), emptyList()))
        try { Backup.restore(repository, json); fail("Invalid backup accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(original.id), repository.dao.allTasks().map { it.id })
    }
    @Test fun duplicateBackupIdsAreRejected() {
        val t = task()
        val json = Backup.encode(Backup.Contents(listOf(t, t), emptyList()))
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(json) }
    }
    @Test fun deleteRemovesOnlySelectedTaskAndHistoryAndRejectsStaleWrites() = runBlocking {
        val removed = task("Delete this task")
        val kept = task("Keep this task")
        repository.save(removed); repository.complete(removed.id, true); repository.save(kept)
        var refreshes = 0
        val observed = TaskRepository(db) { refreshes++ }
        assertTrue(observed.delete(removed.id))
        assertFalse(observed.delete(removed.id))
        assertEquals(1, refreshes)
        assertNull(repository.dao.task(removed.id))
        assertTrue(repository.dao.allEvents().all { it.taskId == kept.id })
        assertNotNull(repository.dao.task(kept.id))
        assertNotNull(repository.dao.deletion(removed.id))
        assertFalse(repository.save(removed.copy(title = "Stale editor")))
        assertFalse(repository.complete(removed.id, true))
    }
    @Test fun oldBackupsCannotResurrectDeletedTasksAndDeletionRoundTrips() = runBlocking {
        val removed = task()
        repository.save(removed)
        val before = Backup.export(repository)
        repository.delete(removed.id)
        assertEquals(0, Backup.restore(repository, before))
        assertTrue(repository.dao.allTasks().isEmpty())
        assertTrue(repository.dao.allEvents().isEmpty())
        val after = Backup.export(repository)
        val other = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).build()
        try {
            val target = TaskRepository(other)
            assertEquals(1, Backup.restore(target, before))
            Backup.restore(target, after)
            assertTrue(target.dao.allTasks().isEmpty())
            assertTrue(target.dao.allEvents().isEmpty())
            assertEquals(0, Backup.restore(target, before))
            assertEquals(repository.dao.allDeletions(), target.dao.allDeletions())
        } finally { other.close() }
    }
    @Test fun malformedDeletionBackupCannotPartiallyRemoveLocalTasks() = runBlocking {
        val kept = task("Keep during failed restore")
        repository.save(kept)
        val contents = Backup.Contents(emptyList(), emptyList(), listOf(TaskDeletion(kept.id), TaskDeletion("invalid-id")))
        try { Backup.restore(repository, Backup.encode(contents)); fail("Malformed deletion marker accepted") }
        catch (_: IllegalArgumentException) { }
        assertNotNull(repository.dao.task(kept.id))
        assertEquals(1, repository.dao.allEvents().size)
        assertTrue(repository.dao.allDeletions().isEmpty())
    }
    @Test fun versionOneBackupStillImports() = runBlocking {
        val t = task()
        val json = org.json.JSONObject(Backup.encode(Backup.Contents(listOf(t), emptyList())))
        json.put("formatVersion", 1); json.remove("deletions")
        assertEquals(1, Backup.restore(repository, json.toString()))
        assertEquals(t.title, repository.dao.task(t.id)!!.title)
    }
    @Test fun versionOneDatabaseMigratesWithoutLosingExistingTasksOrHistory() { assertMigration(1) }
    @Test fun versionTwoDatabaseMigratesWithTasksHistoryAndDeletionMarkersIntact() { assertMigration(2) }
    private fun assertMigration(version: Int) = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val name = "real-v$version-${System.nanoTime()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = org.json.JSONObject(java.io.File("schemas/com.dracula.taskbite.TaskDatabase/$version.json").readText()).getJSONObject("database")
        val t = task("Existing v1 task")
        val eventId = java.util.UUID.randomUUID().toString()
        val deletedId = java.util.UUID.randomUUID().toString()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) raw.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
            raw.execSQL("INSERT INTO tasks VALUES(?, ?, '', ?, 15, 1, ?, ?, NULL, NULL, 0, ?)", arrayOf<Any>(t.id, t.title, t.dueAt, t.createdAt, t.updatedAt, t.remindAfter))
            raw.execSQL("INSERT INTO events VALUES(?, ?, ?, 'CREATED', ?, '')", arrayOf<Any>(eventId, t.id, t.title, t.createdAt))
            if (version == 2) raw.execSQL("INSERT INTO deleted_tasks VALUES(?, ?)", arrayOf<Any>(deletedId, t.createdAt))
            raw.version = version
        }
        val migrated = TaskDatabase.open(context, name)
        try {
            assertEquals(t.title, migrated.dao().task(t.id)!!.title)
            assertEquals(eventId, migrated.dao().allEvents().single().id)
            assertEquals(RepeatRule.NONE.name, migrated.dao().task(t.id)!!.repeatRule)
            assertEquals(0, migrated.dao().task(t.id)!!.snoozeCount)
            assertNull(migrated.dao().task(t.id)!!.nextOccurrenceId)
            if (version == 1) assertTrue(migrated.dao().allDeletions().isEmpty())
            else assertEquals(deletedId, migrated.dao().allDeletions().single().id)
            assertTrue(TaskRepository(migrated).delete(t.id))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun missingMigrationFailsWithoutErasingData() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-guard-${System.nanoTime()}.db"
        val first = TaskDatabase.open(context, name)
        val t = task()
        TaskRepository(first).save(t)
        first.close()
        val path = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, 0).use { it.version = 5 }
        val reopened = TaskDatabase.open(context, name)
        try {
            try { reopened.dao().allTasks(); fail("Missing migration should fail closed") }
            catch (_: IllegalStateException) { }
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, 0).use { raw ->
                raw.rawQuery("SELECT title FROM tasks", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(t.title, cursor.getString(0))
                }
            }
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
