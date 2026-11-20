package com.dracula.taskbite

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RecurringUndoTest {
    private lateinit var db: TaskDatabase
    private lateinit var repo: TaskRepository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).build(); repo = TaskRepository(db) }
    @After fun close() { db.close() }
    private fun task(rule: RepeatRule = RepeatRule.NONE) = Task(title = "Water plants", dueAt = System.currentTimeMillis() + 60_000, repeatRule = rule.name)

    @Test fun completionKeepsHistoryAndCreatesOnlyOneNextOccurrence() = runBlocking<Unit> {
        val t = task(RepeatRule.DAILY); repo.save(t)
        assertTrue(repo.complete(t.id, true)); assertFalse(repo.complete(t.id, true))
        val completed = repo.dao.task(t.id)!!
        val next = repo.dao.task(completed.nextOccurrenceId!!)!!
        assertEquals(2, repo.dao.allTasks().size)
        assertNotNull(completed.completedAt)
        assertNull(next.completedAt); assertNull(next.startedAt)
        assertEquals(RepeatRule.DAILY.name, next.repeatRule)
        assertEquals(0, next.snoozeCount)
        assertTrue(next.dueAt > t.dueAt)
        assertEquals(3, repo.dao.allEvents().size)
    }
    @Test fun recurrenceKeepsLocalTimeAcrossDstAndSkipsWeekendsAndMissedWeeks() {
        val zone = ZoneId.of("America/New_York")
        fun time(date: String) = LocalDate.parse(date).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(time("2024-03-10"), Recurrence.next(time("2024-03-09"), RepeatRule.DAILY, time("2024-03-09"), zone))
        assertEquals(23 * 3_600_000L, time("2024-03-10") - time("2024-03-09"))
        assertEquals(time("2024-03-11"), Recurrence.next(time("2024-03-08"), RepeatRule.WEEKDAYS, time("2024-03-08"), zone))
        assertEquals(time("2024-04-01"), Recurrence.next(time("2024-03-04"), RepeatRule.WEEKLY, time("2024-03-25") + 1000, zone))
    }
    @Test fun undoCompletionRemovesUneditedNextOccurrenceAndDoesNotResurrectFromBackup() = runBlocking<Unit> {
        val t = task(RepeatRule.WEEKLY); repo.save(t)
        val token = repo.actWithUndo(t.id, TaskAction.COMPLETE)!!
        val oldBackup = Backup.export(repo)
        val spawned = repo.dao.task(t.id)!!.nextOccurrenceId!!
        assertTrue(repo.undo(token)); assertFalse(repo.undo(token))
        assertNull(repo.dao.task(t.id)!!.completedAt)
        assertNull(repo.dao.task(t.id)!!.nextOccurrenceId)
        assertNull(repo.dao.task(spawned))
        assertEquals(1, repo.dao.allEvents().size)
        Backup.restore(repo, oldBackup)
        assertNull(repo.dao.task(spawned)); assertNull(repo.dao.task(t.id)!!.completedAt)
        assertTrue(repo.complete(t.id, true))
        assertNotEquals(spawned, repo.dao.task(t.id)!!.nextOccurrenceId)
    }
    @Test fun undoCannotOverwriteNewerEditsOrChangedNextOccurrence() = runBlocking<Unit> {
        val t = task(RepeatRule.DAILY); repo.save(t)
        val token = repo.actWithUndo(t.id, TaskAction.COMPLETE)!!
        val next = repo.dao.task(repo.dao.task(t.id)!!.nextOccurrenceId!!)!!
        repo.save(next.copy(title = "Keep this change"))
        assertFalse(repo.undo(token))
        assertNotNull(repo.dao.task(t.id)!!.completedAt)
        assertEquals("Keep this change", repo.dao.task(next.id)!!.title)
        try { repo.complete(t.id, false); fail("Reopen must not discard next occurrence edits") } catch (_: TaskChangeRejected) { }
        val other = task(); repo.save(other)
        val snooze = repo.actWithUndo(other.id, TaskAction.SNOOZE)!!
        repo.save(repo.dao.task(other.id)!!.copy(notes = "New note"))
        assertFalse(repo.undo(snooze))
        assertEquals("New note", repo.dao.task(other.id)!!.notes)
    }
    @Test fun undoSnoozeRestoresDeadlineStartAndCount() = runBlocking<Unit> {
        val t = task(); repo.save(t); repo.start(t.id)
        val before = repo.dao.task(t.id)!!
        val token = repo.actWithUndo(t.id, TaskAction.SNOOZE)!!
        assertEquals(1, repo.dao.task(t.id)!!.snoozeCount)
        assertTrue(repo.undo(token))
        val after = repo.dao.task(t.id)!!
        assertEquals(before.dueAt, after.dueAt); assertEquals(before.startedAt, after.startedAt)
        assertEquals(0, after.snoozeCount)
        assertEquals(2, repo.dao.taskEvents(t.id).size)
    }
    @Test fun undoDeleteRestoresTaskAndHistoryButOldTokenCannotUndoAnotherDeletion() = runBlocking<Unit> {
        val t = task(); repo.save(t); repo.start(t.id)
        val events = repo.dao.taskEvents(t.id)
        val token = repo.actWithUndo(t.id, TaskAction.DELETE)!!
        assertNull(repo.dao.task(t.id)); assertNotNull(repo.dao.deletion(t.id))
        assertTrue(repo.undo(token))
        assertEquals(t.title, repo.dao.task(t.id)!!.title)
        assertEquals(events, repo.dao.taskEvents(t.id)); assertNull(repo.dao.deletion(t.id))
        repo.delete(t.id)
        assertFalse(repo.undo(token)); assertNull(repo.dao.task(t.id))
    }
    @Test fun expiredUndoDoesNotRestoreDeletedData() = runBlocking<Unit> {
        val t = task(); repo.save(t)
        val token = repo.actWithUndo(t.id, TaskAction.DELETE)!!
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertFalse(repo.undo(token)); assertNull(repo.dao.task(t.id))
    }
    @Test fun contextualRoastsDistinguishRepeatedPostponingAndLateCompletion() = runBlocking<Unit> {
        val t = task(); repo.save(t)
        assertEquals(RoastKind.SNOOZE, Roasts.forAction(RoastKind.SNOOZE, t))
        repo.snooze(t.id); repo.snooze(t.id)
        assertEquals(RoastKind.SNOOZE_REPEAT, Roasts.forAction(RoastKind.SNOOZE, repo.dao.task(t.id)!!))
        assertEquals(RoastKind.COMPLETED_LATE, Roasts.forAction(RoastKind.COMPLETED, t, t.dueAt + 1))
        assertEquals(RoastKind.COMPLETED, Roasts.forAction(RoastKind.COMPLETED, t, t.dueAt - 1))
    }
    @Test fun backupRoundTripsRecurrenceAndContextWhileOlderBackupsUseSafeDefaults() = runBlocking<Unit> {
        val t = task(RepeatRule.WEEKDAYS); repo.save(t); repo.snooze(t.id); repo.complete(t.id, true)
        val text = Backup.export(repo)
        val decoded = Backup.decode(text)
        val completed = decoded.tasks.first { it.id == t.id }
        assertEquals(RepeatRule.WEEKDAYS.name, completed.repeatRule)
        assertEquals(1, completed.snoozeCount); assertNotNull(completed.nextOccurrenceId)
        val other = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TaskDatabase::class.java).build()
        try { Backup.restore(TaskRepository(other), text); assertEquals(decoded.tasks.toSet(), other.dao().allTasks().toSet()) } finally { other.close() }
        val legacy = org.json.JSONObject(Backup.encode(Backup.Contents(listOf(task()), emptyList()))).put("formatVersion", 2)
        val fields = legacy.getJSONArray("tasks").getJSONObject(0)
        fields.remove("repeatRule"); fields.remove("nextOccurrenceId"); fields.remove("snoozeCount")
        assertEquals(RepeatRule.NONE.name, Backup.decode(legacy.toString()).tasks.single().repeatRule)
        fields.put("repeatRule", "INVALID"); fields.put("nextOccurrenceId", org.json.JSONObject.NULL); fields.put("snoozeCount", 0); legacy.put("formatVersion", 3)
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(legacy.toString()) }
    }
}
