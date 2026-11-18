package com.dracula.taskbite

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val dueAt: Long,
    val minutes: Int = 15,
    val priority: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
    val startedAt: Long? = null,
    val archived: Boolean = false,
    val remindAfter: Long = dueAt,
)

@Entity(tableName = "events", indices = [Index("taskId")])
data class TaskEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val title: String,
    val kind: String,
    val at: Long = System.currentTimeMillis(),
    val detail: String = "",
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY dueAt, priority DESC") fun watchTasks(): Flow<List<Task>>
    @Query("SELECT * FROM events ORDER BY at DESC, id") fun watchEvents(): Flow<List<TaskEvent>>
    @Query("SELECT * FROM tasks ORDER BY dueAt") suspend fun allTasks(): List<Task>
    @Query("SELECT * FROM events ORDER BY at DESC") suspend fun allEvents(): List<TaskEvent>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): Task?
    @Upsert suspend fun put(task: Task)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun event(event: TaskEvent)
    @Query("SELECT * FROM tasks WHERE archived = 0 AND completedAt IS NULL ORDER BY dueAt") suspend fun pending(): List<Task>
}

@Database(entities = [Task::class, TaskEvent::class], version = 1, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao
    companion object {
        fun open(context: Context, name: String = "taskbite.db"): TaskDatabase =
            Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, name)
                // Never use destructive fallback. Future schema changes require a tested migration.
                .build()
    }
}

class TaskRepository(val database: TaskDatabase) {
    val dao = database.dao()
    suspend fun save(draft: Task): Boolean = database.withTransaction {
        require(draft.title.isNotBlank() && draft.title.length <= 200)
        require(draft.notes.length <= 4000 && draft.minutes in 1..1440 && draft.priority in 0..2)
        val existing = dao.task(draft.id)
        if (existing?.archived == true) return@withTransaction false
        val now = System.currentTimeMillis()
        val saved = draft.copy(
            title = draft.title.trim(), updatedAt = now,
            createdAt = existing?.createdAt ?: draft.createdAt,
            completedAt = existing?.completedAt, startedAt = existing?.startedAt,
            remindAfter = if (existing?.dueAt == draft.dueAt) existing.remindAfter else draft.dueAt,
        )
        dao.put(saved)
        dao.event(TaskEvent(taskId = saved.id, title = saved.title,
            kind = if (existing == null) "CREATED" else "EDITED", at = now))
        true
    }

    suspend fun complete(id: String, complete: Boolean): Boolean = change(id) { task, now ->
        if (task.archived || (task.completedAt != null) == complete) null
        else task.copy(completedAt = if (complete) now else null, remindAfter = maxOf(now, task.dueAt)) to
            if (complete) "COMPLETED" else "REOPENED"
    }
    suspend fun start(id: String): Boolean = change(id) { task, now ->
        if (task.archived || task.completedAt != null || task.startedAt != null) null
        else task.copy(startedAt = now) to "STARTED"
    }
    suspend fun snooze(id: String): Boolean = change(id) { task, now ->
        if (task.archived || task.completedAt != null) null else {
            val zone = ZoneId.systemDefault()
            val currentDue = Instant.ofEpochMilli(task.dueAt).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val nextDay = maxOf(today, currentDue.toLocalDate()).plusDays(1)
            val next = nextDay.atTime(currentDue.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            task.copy(dueAt = next, remindAfter = next, startedAt = null) to "SNOOZED"
        }
    }
    suspend fun archive(id: String, archived: Boolean): Boolean = change(id) { task, now ->
        if (task.archived == archived) null else
            task.copy(archived = archived, remindAfter = maxOf(now, task.dueAt)) to
                if (archived) "ARCHIVED" else "RESTORED"
    }
    private suspend fun change(id: String, transform: (Task, Long) -> Pair<Task, String>?): Boolean =
        database.withTransaction {
            val task = dao.task(id) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            val (updated, kind) = transform(task, now) ?: return@withTransaction false
            dao.put(updated.copy(updatedAt = now))
            dao.event(TaskEvent(taskId = id, title = task.title, kind = kind, at = now))
            true
        }
}

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("taskbite", Context.MODE_PRIVATE)
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }
    var actionVoice: Boolean
        get() = prefs.getBoolean("actionVoice", false)
        set(value) { prefs.edit().putBoolean("actionVoice", value).apply() }
    var backgroundVoice: Boolean
        get() = prefs.getBoolean("backgroundVoice", false)
        set(value) { prefs.edit().putBoolean("backgroundVoice", value).apply() }
    var reminders: Boolean
        get() = prefs.getBoolean("reminders", true)
        set(value) { prefs.edit().putBoolean("reminders", value).apply() }
    var quietEnabled: Boolean
        get() = prefs.getBoolean("quietEnabled", true)
        set(value) { prefs.edit().putBoolean("quietEnabled", value).apply() }
    var quietStart: Int
        get() = prefs.getInt("quietStart", 22)
        set(value) { prefs.edit().putInt("quietStart", value).apply() }
    var quietEnd: Int
        get() = prefs.getInt("quietEnd", 8)
        set(value) { prefs.edit().putInt("quietEnd", value).apply() }
    var lastReminder: Long
        get() = prefs.getLong("lastReminder", 0)
        set(value) { prefs.edit().putLong("lastReminder", value).apply() }
    fun isQuiet(hour: Int = java.time.LocalTime.now().hour): Boolean =
        quietEnabled && ReminderPolicy.inQuietHours(hour, quietStart, quietEnd)
}

class TaskBiteApp : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    val database by lazy { TaskDatabase.open(this) }
    val repository by lazy { TaskRepository(database) }
    val preferences by lazy { Preferences(this) }
    @Volatile var foreground = false
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
        Reminders.start(this)
    }
}
