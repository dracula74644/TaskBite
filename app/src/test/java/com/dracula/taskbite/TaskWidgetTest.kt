package com.dracula.taskbite

import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class TaskWidgetTest {
    @Test fun countsExcludeArchivedAndCompletedAndListKeepsOverdueTasks() {
        val now = Instant.parse("2030-05-10T10:00:00Z").toEpochMilli()
        val old = Task(title = "Overdue", dueAt = now - 86_400_000)
        val today = Task(title = "Today", dueAt = now + 1000)
        val future = Task(title = "Later", dueAt = now + 86_400_000)
        val completed = Task(title = "Done", dueAt = now - 1000, completedAt = now - 100)
        val archived = Task(title = "Archived", dueAt = now - 1000, archived = true)
        val model = TaskWidgetModel.from(listOf(future, completed, today, old, archived), now, ZoneOffset.UTC)
        assertEquals(listOf(old.id, today.id), model.today.map { it.id })
        assertEquals(3, model.pendingCount)
        assertEquals(1, model.overdueCount)
        assertEquals(1, model.doneToday)
    }
    @Test fun dayBoundaryUsesPhoneTimezoneAcrossDaylightSavings() {
        val zone = ZoneId.of("America/New_York")
        val now = LocalDate.of(2030, 3, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDay = LocalDate.of(2030, 3, 11).atStartOfDay(zone).toInstant().toEpochMilli()
        val lastMinute = Task(title = "Tonight", dueAt = nextDay - 60_000)
        val midnight = Task(title = "Tomorrow", dueAt = nextDay)
        val before = TaskWidgetModel.from(listOf(lastMinute, midnight), now, zone)
        assertEquals(listOf(lastMinute.id), before.today.map { it.id })
        val after = TaskWidgetModel.from(listOf(lastMinute, midnight), nextDay, zone)
        assertEquals(2, after.today.size)
        assertEquals(2, after.overdueCount)
    }
    @Test fun widgetCompletionIsIdempotentAndUpdatesHistory() = runBlocking {
        val app = (RuntimeEnvironment.getApplication() as TaskBiteApp)
        val task = Task(title = "Finish directly from widget", dueAt = System.currentTimeMillis() - 1000)
        app.repository.save(task)
        val callback = CompleteWidgetTaskAction()
        val parameters = actionParametersOf(CompleteWidgetTaskAction.TASK_ID to task.id)
        val id = object : GlanceId {}
        callback.onAction(app, id, parameters)
        callback.onAction(app, id, parameters)
        assertNotNull(app.repository.dao.task(task.id)!!.completedAt)
        assertEquals(1, app.repository.dao.allEvents().count { it.kind == "COMPLETED" })
        assertTrue(TaskWidgetModel.from(app.repository.dao.allTasks(), System.currentTimeMillis()).today.isEmpty())
    }
    @Test fun repositoryRefreshCallbackRunsAfterCommittedChangesIncludingRestore() = runBlocking {
        val app = (RuntimeEnvironment.getApplication() as TaskBiteApp)
        var refreshed = 0
        val repo = TaskRepository(app.database) {
            assertFalse("Refresh must not run within the database transaction", app.database.inTransaction())
            refreshed++
        }
        val task = Task(title = "Refresh me", dueAt = System.currentTimeMillis())
        repo.save(task)
        repo.snooze(task.id)
        repo.complete(task.id, true)
        repo.complete(task.id, true) // No-op must not generate another update.
        repo.archive(task.id, true)
        repo.archive(task.id, false)
        assertEquals(5, refreshed)
        Backup.restore(repo, Backup.export(repo))
        assertEquals(6, refreshed)
    }
    @Test fun widgetIntentsAreDistinctAndTargetMainActivityDirectly() {
        val app = (RuntimeEnvironment.getApplication() as TaskBiteApp)
        val add = WidgetNavigation.intent(app, WidgetNavigation.ADD)
        val voice = WidgetNavigation.intent(app, WidgetNavigation.VOICE)
        assertFalse(add.filterEquals(voice))
        assertEquals(MainActivity::class.java.name, add.component!!.className)
        assertEquals(WidgetNavigation.ADD, WidgetNavigation.parse(add)!!.action)
        assertEquals(WidgetNavigation.VOICE, WidgetNavigation.parse(voice)!!.action)
        assertNull(WidgetNavigation.parse(Intent().setAction(WidgetNavigation.EDIT)))
        assertNull(WidgetNavigation.parse(Intent().setAction("unknown")))
    }
    @Test fun undoableAndRecurringActionsRefreshWidgetsOnceAfterTheWholeTransactionCommits() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as TaskBiteApp
        var updates = 0
        val repo = TaskRepository(app.database) {
            assertFalse("Widget refresh observed uncommitted task data", app.database.inTransaction())
            updates++
        }
        val task = Task(title = "Recurring refresh", dueAt = System.currentTimeMillis() + 60_000, repeatRule = RepeatRule.DAILY.name)
        repo.save(task)
        val done = repo.actWithUndo(task.id, TaskAction.COMPLETE)!!
        assertEquals(2, updates)
        assertTrue(repo.undo(done)); assertEquals(3, updates)
        repo.complete(task.id, true); assertEquals(4, updates)
        repo.complete(task.id, false); assertEquals(5, updates)
        val snooze = repo.actWithUndo(task.id, TaskAction.SNOOZE)!!
        assertEquals(6, updates); assertTrue(repo.undo(snooze)); assertEquals(7, updates)
        val deleted = repo.actWithUndo(task.id, TaskAction.DELETE)!!
        assertEquals(8, updates); assertTrue(repo.undo(deleted)); assertEquals(9, updates)
    }
}
