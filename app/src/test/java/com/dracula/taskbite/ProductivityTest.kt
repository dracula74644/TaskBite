package com.dracula.taskbite

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class ProductivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    private val repo get() = app.repository
    private val p get() = app.preferences
    @Test fun checklistPersistsRecursUncheckedAndUndoPreservesCheckedSteps() = runBlocking<Unit> {
        val one = ChecklistItem(text = "Buy green tea", done = true)
        val two = ChecklistItem(text = "Wash mug")
        val task = Task(title = "Tea break", dueAt = System.currentTimeMillis() + 60_000,
            repeatRule = RepeatRule.DAILY.name, checklist = Checklist.encode(listOf(one, two)))
        repo.save(task)
        assertTrue(Checklist.matches(task, "GREEN")); assertFalse(Checklist.matches(task, "coffee"))
        assertTrue(repo.checkItem(task.id, two.id, true))
        assertFalse(repo.checkItem(task.id, two.id, true))
        val before = repo.dao.task(task.id)!!
        val token = repo.actWithUndo(task.id, TaskAction.COMPLETE)!!
        val next = repo.dao.task(repo.dao.task(task.id)!!.nextOccurrenceId!!)!!
        assertTrue(Checklist.decode(next.checklist).none { it.done })
        assertTrue(repo.undo(token))
        assertEquals(before.checklist, repo.dao.task(task.id)!!.checklist)
        val backup = Backup.export(repo)
        assertEquals(before.checklist, Backup.decode(backup).tasks.single().checklist)
        repo.delete(task.id); Backup.restore(repo, backup)
        assertNull(repo.dao.task(task.id))
    }
    @Test fun checklistRejectsDuplicateIdsAndLegacyBackupsDefaultToEmpty() {
        val step = ChecklistItem(text = "Small step")
        assertThrows(IllegalArgumentException::class.java) { Checklist.encode(listOf(step, step)) }
        assertThrows(IllegalArgumentException::class.java) { Checklist.encode(List(31) { ChecklistItem(text = "Step") }) }
        val json = JSONObject(Backup.encode(Backup.Contents(listOf(Task(title = "Old task", dueAt = 1000)), emptyList())))
        json.put("formatVersion", 3)
        json.getJSONArray("tasks").getJSONObject(0).remove("checklist")
        assertEquals("[]", Backup.decode(json.toString()).tasks.single().checklist)
    }
    @Test fun staleEditorCannotEraseChecklistProgress() = runBlocking<Unit> {
        val step = ChecklistItem(text = "Keep this progress")
        val task = Task(title = "Shared task", dueAt = 1000, checklist = Checklist.encode(listOf(step)))
        repo.save(task)
        val draft = repo.dao.task(task.id)!!
        repo.checkItem(task.id, step.id, true)
        try { repo.save(draft.copy(title = "Old draft"), expectedRevision = draft.updatedAt); fail("Stale draft should be rejected") }
        catch (_: TaskChangeRejected) { }
        assertTrue(Checklist.decode(repo.dao.task(task.id)!!.checklist).single().done)
        assertEquals("Shared task", repo.dao.task(task.id)!!.title)
    }
    @Test fun laterTodayRejectsPastTimesAndUndoRestoresOriginalDeadline() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val end = local.toLocalDate().atTime(23, 59, 59).atZone(local.zone).toInstant().toEpochMilli()
        val task = Task(title = "Move this", dueAt = now - 1000)
        repo.save(task); repo.start(task.id)
        val original = repo.dao.task(task.id)!!
        val token = repo.actWithUndo(task.id, TaskAction.LATER, end)!!
        val postponed = repo.dao.task(task.id)!!
        assertEquals(end, postponed.dueAt); assertNull(postponed.startedAt)
        assertEquals(1, postponed.snoozeCount)
        assertTrue(repo.undo(token)); assertEquals(original.dueAt, repo.dao.task(task.id)!!.dueAt)
        assertEquals(original.startedAt, repo.dao.task(task.id)!!.startedAt)
        try { repo.later(task.id, now - 1); fail("Past time must be rejected") } catch (_: IllegalArgumentException) { }
        assertThrows(IllegalArgumentException::class.java) { LaterToday.at(LocalTime.MIDNIGHT, now) }
        assertTrue(Backup.decode(Backup.export(repo)).events.isNotEmpty())
    }
    @Test fun pauseDefersNudgesButKeepsPlainDeadlineNotificationAndResumes() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = System.currentTimeMillis()
        p.apply { onboarded = true; reminders = true; backgroundVoice = true; quietEnabled = false;
            planningNudge = false; backgroundSince = now - 3_600_000; teasingPausedUntil = now + 3_600_000 }
        val task = Task(title = "Real deadline", dueAt = now - 1000)
        repo.dao.put(task)
        ReminderDelivery.deliver(app, false, false, now)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.tag == task.id }
        assertEquals("Your task is due. Open Bite to review it.", notification.notification.extras.getString(Notification.EXTRA_TEXT))
        assertNull(ReminderAudio.peek(app))
        val token = notification.notification.extras.getString(NotificationReactions.TOKEN)!!
        assertNull(NotificationReactions.opened(app, token, now))
        val away = AwayPolicy.next(emptyList(), AwayState(since = now - 3_600_000, quietEnabled = false,
            pauseUntil = p.teasingPausedUntil), now, false)!!
        assertEquals(p.teasingPausedUntil, away.at)
        p.deadlinesDuringPause = false
        repo.dao.put(task.copy(remindAfter = now))
        assertTrue(Reminders.nextAt(app, now)!! >= p.teasingPausedUntil)
        Reminders.clear(app, task.id)
        ReminderDelivery.deliver(app, false, false, now)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(task.copy(remindAfter = now), repo.dao.task(task.id))
        p.teasingPausedUntil = now - 1; p.lastReminder = 0
        ReminderDelivery.deliver(app, false, false, now)
        assertTrue(manager.activeNotifications.any { it.tag == task.id })
    }
    @Test fun reminderOverviewExplainsNotificationVolumeAndMissingSweetRecordings() = runBlocking<Unit> {
        p.apply { onboarded = true; reminders = false; quietEnabled = false }
        assertEquals(ReminderFix.ENABLE, ReminderOverview.read(app).fix)
        p.reminders = true
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        p.backgroundVoice = true; p.alertVolume = 0
        assertEquals(ReminderFix.VOLUME, ReminderOverview.read(app).fix)
        p.alertVolume = 55
        val audio = app.getSystemService(AudioManager::class.java)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 8, 0)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        p.teasingStyle = TeasingStyle.SWEET
        assertEquals(ReminderFix.RECORDINGS, ReminderOverview.read(app).fix)
        p.teasingPausedUntil = System.currentTimeMillis() + 60_000
        assertTrue(ReminderOverview.read(app).next.startsWith("Teasing paused"))
    }
    @Test fun realVersionThreeDatabaseMigratesWithoutChangingTasksHistoryOrDeletions() = runBlocking<Unit> {
        val name = "v3-${System.nanoTime()}.db"
        val path = app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(File("schemas/com.dracula.taskbite.TaskDatabase/3.json").readText()).getJSONObject("database")
        val task = Task(title = "Keep my old task", dueAt = 1000)
        val event = TaskEvent(taskId = task.id, title = task.title, kind = "CREATED")
        val deleted = java.util.UUID.randomUUID().toString()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { raw ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { j -> raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
            }
            raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
            raw.execSQL("INSERT INTO tasks VALUES(?, ?, '', ?, 15, 1, ?, ?, NULL, NULL, 0, ?, 'DAILY', NULL, 2)",
                arrayOf<Any>(task.id, task.title, task.dueAt, task.createdAt, task.updatedAt, task.remindAfter))
            raw.execSQL("INSERT INTO events VALUES(?, ?, ?, 'CREATED', ?, '')", arrayOf<Any>(event.id, task.id, task.title, event.at))
            raw.execSQL("INSERT INTO deleted_tasks VALUES(?, ?)", arrayOf<Any>(deleted, 1000L))
            raw.version = 3
        }
        val migrated = TaskDatabase.open(app, name)
        try {
            val actual = migrated.dao().task(task.id)!!
            assertEquals(task.title, actual.title); assertEquals("[]", actual.checklist)
            assertEquals("DAILY", actual.repeatRule); assertEquals(2, actual.snoozeCount)
            assertEquals(event.id, migrated.dao().allEvents().single().id)
            assertEquals(deleted, migrated.dao().allDeletions().single().id)
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
