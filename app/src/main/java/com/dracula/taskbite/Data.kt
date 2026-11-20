package com.dracula.taskbite

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
    @ColumnInfo(defaultValue = "'NONE'") val repeatRule: String = RepeatRule.NONE.name,
    val nextOccurrenceId: String? = null,
    @ColumnInfo(defaultValue = "0") val snoozeCount: Int = 0,
    @ColumnInfo(defaultValue = "'[]'") val checklist: String = "[]",
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

@Entity(tableName = "deleted_tasks")
data class TaskDeletion(@PrimaryKey val id: String, val deletedAt: Long = System.currentTimeMillis())

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY dueAt, priority DESC") fun watchTasks(): Flow<List<Task>>
    @Query("SELECT * FROM events ORDER BY at DESC, id") fun watchEvents(): Flow<List<TaskEvent>>
    @Query("SELECT * FROM tasks ORDER BY dueAt") suspend fun allTasks(): List<Task>
    @Query("SELECT * FROM events ORDER BY at DESC") suspend fun allEvents(): List<TaskEvent>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: String): Task?
    @Query("SELECT * FROM deleted_tasks WHERE id = :id") suspend fun deletion(id: String): TaskDeletion?
    @Query("SELECT * FROM deleted_tasks ORDER BY deletedAt, id") suspend fun allDeletions(): List<TaskDeletion>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun rememberDeletion(deletion: TaskDeletion)
    @Query("DELETE FROM events WHERE taskId = :id") suspend fun deleteEvents(id: String)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM deleted_tasks WHERE id = :id") suspend fun forgetDeletion(id: String)
    @Query("SELECT * FROM events WHERE taskId = :id ORDER BY at, id") suspend fun taskEvents(id: String): List<TaskEvent>
    @Query("DELETE FROM events WHERE id IN (:ids)") suspend fun deleteEventIds(ids: Set<String>)
    @Upsert suspend fun put(task: Task)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun event(event: TaskEvent)
    @Query("SELECT * FROM tasks WHERE archived = 0 AND completedAt IS NULL ORDER BY dueAt") suspend fun pending(): List<Task>
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE at >= :start AND at < :end) OR EXISTS(SELECT 1 FROM tasks WHERE (createdAt >= :start AND createdAt < :end) OR (completedAt >= :start AND completedAt < :end) OR (startedAt >= :start AND startedAt < :end))")
    suspend fun hasActivityBetween(start: Long, end: Long): Boolean
}

@Database(entities = [Task::class, TaskEvent::class, TaskDeletion::class], version = 4, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `deleted_tasks` (`id` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatRule TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE tasks ADD COLUMN nextOccurrenceId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN snoozeCount INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN checklist TEXT NOT NULL DEFAULT '[]'")
            }
        }
        fun open(context: Context, name: String = "taskbite.db"): TaskDatabase =
            Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Never use destructive fallback. Future schema changes require a tested migration.
                .build()
    }
}

class TaskRepository(val database: TaskDatabase, private val onChanged: () -> Unit = {}) {
    val dao = database.dao()
    fun notifyChanged() = onChanged()
    suspend fun save(draft: Task, expectedRevision: Long? = null): Boolean = database.withTransaction {
        require(draft.title.isNotBlank() && draft.title.length <= 200)
        require(draft.notes.length <= 4000 && draft.minutes in 1..1440 && draft.priority in 0..2)
        require(draft.repeatRule in RepeatRule.entries.map { it.name })
        Checklist.decode(draft.checklist)
        if (dao.deletion(draft.id) != null) return@withTransaction false
        val existing = dao.task(draft.id)
        if (existing?.archived == true) return@withTransaction false
        if (expectedRevision != null && existing?.updatedAt != expectedRevision)
            throw TaskChangeRejected("This task changed elsewhere. Close and reopen its editor before saving.")
        val now = System.currentTimeMillis()
        val saved = draft.copy(
            title = draft.title.trim(), updatedAt = maxOf(now, (existing?.updatedAt ?: -1) + 1),
            createdAt = existing?.createdAt ?: draft.createdAt,
            completedAt = existing?.completedAt, startedAt = existing?.startedAt,
            nextOccurrenceId = existing?.nextOccurrenceId, snoozeCount = existing?.snoozeCount ?: 0,
            remindAfter = if (existing?.dueAt == draft.dueAt) existing.remindAfter else draft.dueAt,
        )
        dao.put(saved)
        dao.event(TaskEvent(taskId = saved.id, title = saved.title,
            kind = if (existing == null) "CREATED" else "EDITED", at = now))
        true
    }.also { if (it) notifyChanged() }

    /** Permanent removal is explicit; its marker prevents stale drafts/backups from recreating it. */
    suspend fun delete(id: String, notify: Boolean = true): Boolean = database.withTransaction {
        val task = dao.task(id) ?: return@withTransaction false
        dao.rememberDeletion(TaskDeletion(id, maxOf(System.currentTimeMillis(), task.updatedAt + 1)))
        dao.deleteEvents(id)
        dao.deleteTask(id)
        true
    }.also { if (it && notify) notifyChanged() }

    suspend fun complete(id: String, complete: Boolean, notify: Boolean = true): Boolean = database.withTransaction {
        val task = dao.task(id) ?: return@withTransaction false
        if (task.archived || (task.completedAt != null) == complete) return@withTransaction false
        val now = maxOf(System.currentTimeMillis(), task.updatedAt + 1)
        if (!complete) task.nextOccurrenceId?.let { nextId ->
            dao.task(nextId)?.let { next ->
                if (next.updatedAt != next.createdAt || next.startedAt != null || next.completedAt != null || next.archived)
                    throw TaskChangeRejected("The next occurrence has changed. Keep it and create a separate task instead.")
                delete(nextId, notify = false)
            }
        }
        val rule = RepeatRule.valueOf(task.repeatRule)
        val next = if (complete && rule != RepeatRule.NONE) Task(title = task.title, notes = task.notes,
            dueAt = Recurrence.next(task.dueAt, rule, now), minutes = task.minutes, priority = task.priority,
            createdAt = now, updatedAt = now, repeatRule = task.repeatRule, checklist = Checklist.reset(task.checklist)) else null
        if (next != null) {
            dao.put(next)
            dao.event(TaskEvent(taskId = next.id, title = next.title, kind = "CREATED", at = now, detail = "Repeats from ${task.id}"))
        }
        dao.put(task.copy(completedAt = if (complete) now else null, updatedAt = now,
            nextOccurrenceId = next?.id, remindAfter = maxOf(now, task.dueAt)))
        dao.event(TaskEvent(taskId = id, title = task.title, kind = if (complete) "COMPLETED" else "REOPENED", at = now))
        true
    }.also { if (it && notify) notifyChanged() }

    suspend fun actWithUndo(id: String, action: TaskAction, laterAt: Long? = null): TaskUndo? = database.withTransaction {
        val before = dao.task(id) ?: return@withTransaction null
        val events = dao.taskEvents(id)
        val changed = when (action) {
            TaskAction.COMPLETE -> complete(id, true, notify = false)
            TaskAction.SNOOZE -> snooze(id, notify = false)
            TaskAction.LATER -> later(id, requireNotNull(laterAt), notify = false)
            TaskAction.DELETE -> delete(id, notify = false)
        }
        if (!changed) return@withTransaction null
        val after = dao.task(id)
        TaskUndo(action, before, after, events,
            dao.taskEvents(id).map { it.id }.toSet() - events.map { it.id }.toSet(),
            if (action == TaskAction.COMPLETE) after?.nextOccurrenceId?.let { dao.task(it) } else null,
            if (action == TaskAction.DELETE) dao.deletion(id) else null,
            android.os.SystemClock.elapsedRealtime() + 10_000)
    }.also { if (it != null) notifyChanged() }

    suspend fun undo(token: TaskUndo): Boolean = database.withTransaction {
        if (android.os.SystemClock.elapsedRealtime() > token.expiresAt) return@withTransaction false
        if (token.action == TaskAction.DELETE) {
            if (dao.task(token.before.id) != null || dao.deletion(token.before.id) != token.deletion) return@withTransaction false
        } else if (dao.task(token.before.id) != token.after) return@withTransaction false
        token.spawned?.let { if (dao.task(it.id) != it) return@withTransaction false }
        token.spawned?.let { delete(it.id, notify = false) }
        if (token.action == TaskAction.DELETE) {
            dao.forgetDeletion(token.before.id)
            token.oldEvents.forEach { dao.event(it) }
        } else dao.deleteEventIds(token.addedEventIds)
        val restoredAt = maxOf(System.currentTimeMillis(), (token.after?.updatedAt ?: token.deletion!!.deletedAt) + 1)
        dao.put(token.before.copy(updatedAt = restoredAt))
        true
    }.also { if (it) notifyChanged() }
    suspend fun start(id: String): Boolean = change(id) { task, now ->
        if (task.archived || task.completedAt != null || task.startedAt != null) null
        else task.copy(startedAt = now) to "STARTED"
    }
    suspend fun snooze(id: String, notify: Boolean = true): Boolean = change(id, notify) { task, now ->
        if (task.archived || task.completedAt != null) null else {
            val zone = ZoneId.systemDefault()
            val currentDue = Instant.ofEpochMilli(task.dueAt).atZone(zone)
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val nextDay = maxOf(today, currentDue.toLocalDate()).plusDays(1)
            val next = nextDay.atTime(currentDue.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            task.copy(dueAt = next, remindAfter = next, startedAt = null, snoozeCount = minOf(100_000, task.snoozeCount + 1)) to "SNOOZED"
        }
    }
    suspend fun archive(id: String, archived: Boolean): Boolean = change(id) { task, now ->
        if (task.archived == archived) null else
            task.copy(archived = archived, remindAfter = maxOf(now, task.dueAt)) to
                if (archived) "ARCHIVED" else "RESTORED"
    }
    suspend fun later(id: String, at: Long, notify: Boolean = true): Boolean = change(id, notify) { task, now ->
        require(at > now && Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate() ==
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()) { "Choose a later time today." }
        if (task.archived || task.completedAt != null) null else
            task.copy(dueAt = at, remindAfter = at, startedAt = null,
                snoozeCount = minOf(100_000, task.snoozeCount + 1)) to "RESCHEDULED"
    }
    suspend fun checkItem(id: String, itemId: String, checked: Boolean): Boolean = change(id) { task, _ ->
        val items = Checklist.decode(task.checklist)
        if (task.archived || task.completedAt != null || items.none { it.id == itemId && it.done != checked }) null
        else task.copy(checklist = Checklist.encode(items.map { if (it.id == itemId) it.copy(done = checked) else it })) to "CHECKLIST"
    }
    private suspend fun change(id: String, notify: Boolean = true, transform: (Task, Long) -> Pair<Task, String>?): Boolean =
        database.withTransaction {
            val task = dao.task(id) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            val (updated, kind) = transform(task, now) ?: return@withTransaction false
            dao.put(updated.copy(updatedAt = maxOf(now, task.updatedAt + 1)))
            dao.event(TaskEvent(taskId = id, title = task.title, kind = kind, at = now))
            true
        }.also { if (it && notify) notifyChanged() }
}

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("taskbite", Context.MODE_PRIVATE)
    var teasingPausedUntil: Long
        get() = prefs.getLong("teasingPausedUntil", 0)
        set(value) { prefs.edit().putLong("teasingPausedUntil", value).apply() }
    var deadlinesDuringPause: Boolean
        get() = prefs.getBoolean("deadlinesDuringPause", true)
        set(value) { prefs.edit().putBoolean("deadlinesDuringPause", value).apply() }
    fun teasingPaused(now: Long = System.currentTimeMillis()) = teasingPausedUntil > now
    var notificationReactions: Boolean
        get() = prefs.getBoolean("notificationReactions", true)
        set(value) { prefs.edit().putBoolean("notificationReactions", value).apply() }
    var awayNudges: Boolean
        get() = prefs.getBoolean("awayNudges", true)
        set(value) { prefs.edit().putBoolean("awayNudges", value).apply() }
    var awayGapMinutes: Int
        get() = prefs.getInt("awayGapMinutes", AwayPolicy.DEFAULT_GAP_MINUTES).coerceIn(AwayPolicy.MIN_GAP_MINUTES, AwayPolicy.MAX_GAP_MINUTES)
        set(value) { prefs.edit().putInt("awayGapMinutes", value.coerceIn(AwayPolicy.MIN_GAP_MINUTES, AwayPolicy.MAX_GAP_MINUTES)).apply() }
    var backgroundSince: Long
        get() = prefs.getLong("backgroundSince", 0)
        set(value) { prefs.edit().putLong("backgroundSince", value).apply() }
    var lastAwayNudge: Long
        get() = prefs.getLong("lastAwayNudge", 0)
        set(value) { prefs.edit().putLong("lastAwayNudge", value).apply() }
    var handledAwayAbandon: Long
        get() = prefs.getLong("handledAwayAbandon", 0)
        set(value) { prefs.edit().putLong("handledAwayAbandon", value).apply() }
    var awayCountDay: String
        get() = prefs.getString("awayCountDay", "").orEmpty()
        set(value) { prefs.edit().putString("awayCountDay", value).apply() }
    var awayCount: Int
        get() = prefs.getInt("awayCount", 0)
        set(value) { prefs.edit().putInt("awayCount", value).apply() }
    var awayFollowedDay: String
        get() = prefs.getString("awayFollowedDay", "").orEmpty()
        set(value) { prefs.edit().putString("awayFollowedDay", value).apply() }
    var lastPlanningAt: Long
        get() = prefs.getLong("lastPlanningAt", 0)
        set(value) { prefs.edit().putLong("lastPlanningAt", value).apply() }
    fun recordAway(now: Long, day: String, scenario: AwayScenario) {
        prefs.edit().putInt("awayCount", if (awayCountDay == day) awayCount + 1 else 1)
            .putString("awayCountDay", day).putLong("lastAwayNudge", now).apply {
                if (scenario == AwayScenario.LEFT_WITHOUT_PROGRESS) putLong("handledAwayAbandon", abandonedAt)
                if (scenario == AwayScenario.EMPTY_DAY) putString("awayFollowedDay", day)
            }.apply()
    }
    var planningNudge: Boolean
        get() = prefs.getBoolean("planningNudge", true)
        set(value) { prefs.edit().putBoolean("planningNudge", value).apply() }
    var planningHour: Int
        get() = prefs.getInt("planningHour", 9).coerceIn(0, 23)
        set(value) { prefs.edit().putInt("planningHour", value.coerceIn(0, 23)).apply() }
    var planningMinute: Int
        get() = prefs.getInt("planningMinute", 0).coerceIn(0, 59)
        set(value) { prefs.edit().putInt("planningMinute", value.coerceIn(0, 59)).apply() }
    var lastPlanningDay: String
        get() = prefs.getString("lastPlanningDay", "").orEmpty()
        set(value) { prefs.edit().putString("lastPlanningDay", value).apply() }
    var lastTaskActivity: Long
        get() = prefs.getLong("lastTaskActivity", 0)
        set(value) { prefs.edit().putLong("lastTaskActivity", value).apply() }
    var teasingStyle: TeasingStyle
        get() = runCatching { TeasingStyle.valueOf(prefs.getString("teasingStyle", "CLASSIC")!!) }.getOrDefault(TeasingStyle.CLASSIC)
        set(value) { prefs.edit().putString("teasingStyle", value.name).apply() }
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }
    var actionVoice: Boolean
        get() = prefs.getBoolean("actionVoice", false)
        set(value) { prefs.edit().putBoolean("actionVoice", value).apply() }
    var backgroundVoice: Boolean
        get() = prefs.getBoolean("backgroundVoice", false)
        set(value) { prefs.edit().putBoolean("backgroundVoice", value).apply() }
    var alertVolume: Int
        get() = prefs.getInt("alertVolume", 100).coerceIn(0, 100)
        set(value) { prefs.edit().putInt("alertVolume", value.coerceIn(0, 100)).apply() }
    var voiceActor: String
        get() = prefs.getString("voiceActor", "sean") ?: "sean"
        set(value) { prefs.edit().putString("voiceActor", value).apply() }
    fun actorFor(style: TeasingStyle): String = if (style == TeasingStyle.CLASSIC) voiceActor
        else prefs.getString("sweetVoiceActor", "bite_sweet") ?: "bite_sweet"
    fun selectActor(style: TeasingStyle, id: String) {
        if (style == TeasingStyle.CLASSIC) voiceActor = id else prefs.edit().putString("sweetVoiceActor", id).apply()
    }
    var customVoice: Boolean
        get() = prefs.getBoolean("customVoice", false)
        set(value) { prefs.edit().putBoolean("customVoice", value).apply() }
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
    var lastForegroundReaction: Long
        get() = prefs.getLong("lastForegroundReaction", 0)
        set(value) { prefs.edit().putLong("lastForegroundReaction", value).apply() }
    var abandonedAt: Long
        get() = prefs.getLong("abandonedAt", 0)
        set(value) { prefs.edit().putLong("abandonedAt", value).apply() }
    fun isQuiet(hour: Int = java.time.LocalTime.now().hour): Boolean =
        quietEnabled && ReminderPolicy.inQuietHours(hour, quietStart, quietEnd)
}

class TaskBiteApp : Application(), androidx.work.Configuration.Provider {
    val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    val database by lazy { TaskDatabase.open(this) }
    val repository by lazy { TaskRepository(database) {
        preferences.lastTaskActivity = System.currentTimeMillis()
        Reminders.clearPlanning(this)
        Reminders.clearAway(this)
        TaskWidgets.requestRefresh(this)
    } }
    val preferences by lazy { Preferences(this) }
    @Volatile var foreground = false
    override fun onCreate() {
        super.onCreate()
        PersonalVoicePacks.activateMemesOnce(this)
        Reminders.createChannel(this)
        Reminders.start(this)
        AppUpdates.start(this)
        backgroundScope.launch { PersonalVoicePacks.keepBundled(this@TaskBiteApp) }
    }
}
