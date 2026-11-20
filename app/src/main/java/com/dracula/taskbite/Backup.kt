package com.dracula.taskbite

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Backup {
    const val MAX_BYTES = 10 * 1024 * 1024
    fun readLimited(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "Backup exceeds 10 MB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    data class Contents(val tasks: List<Task>, val events: List<TaskEvent>, val deletions: List<TaskDeletion> = emptyList())
    suspend fun export(repository: TaskRepository): String = repository.database.withTransaction {
        encode(Contents(repository.dao.allTasks(), repository.dao.allEvents(), repository.dao.allDeletions()))
    }
    fun encode(contents: Contents): String = JSONObject().apply {
        put("app", "TaskBite")
        put("formatVersion", 4)
        put("exportedAt", System.currentTimeMillis())
        put("tasks", JSONArray().apply { contents.tasks.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("notes", t.notes)
            put("dueAt", t.dueAt); put("minutes", t.minutes); put("priority", t.priority)
            put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
            put("completedAt", t.completedAt ?: JSONObject.NULL)
            put("startedAt", t.startedAt ?: JSONObject.NULL)
            put("archived", t.archived); put("remindAfter", t.remindAfter)
            put("repeatRule", t.repeatRule); put("nextOccurrenceId", t.nextOccurrenceId ?: JSONObject.NULL)
            put("snoozeCount", t.snoozeCount)
            put("checklist", JSONArray(t.checklist))
        }) } })
        put("deletions", JSONArray().apply { contents.deletions.forEach { d -> put(JSONObject().apply {
            put("id", d.id); put("deletedAt", d.deletedAt)
        }) } })
        put("events", JSONArray().apply { contents.events.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("taskId", e.taskId); put("title", e.title)
            put("kind", e.kind); put("at", e.at); put("detail", e.detail)
        }) } })
    }.toString(2)

    fun decode(text: String): Contents {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is larger than 10 MB." }
        val root = JSONObject(text)
        require(root.getString("app") == "TaskBite" && root.getInt("formatVersion") in 1..4) { "Unsupported backup format." }
        val version = root.getInt("formatVersion")
        val tasksJson = root.getJSONArray("tasks")
        val eventsJson = root.getJSONArray("events")
        require(tasksJson.length() <= 10_000 && eventsJson.length() <= 100_000) { "Backup has too many records." }
        fun JSONObject.timestamp(name: String): Long = getLong(name).also { require(it in 0..253402300799999L) { "Invalid date." } }
        fun JSONObject.optionalTime(name: String): Long? = if (isNull(name)) null else timestamp(name)
        fun JSONObject.id(name: String): String = getString(name).also {
            require(UUID.fromString(it).toString() == it) { "Invalid record identifier." }
        }
        val tasks = (0 until tasksJson.length()).map { index -> tasksJson.getJSONObject(index).let { t ->
            Task(id = t.id("id"), title = t.getString("title"), notes = t.getString("notes"),
                dueAt = t.timestamp("dueAt"), minutes = t.getInt("minutes"), priority = t.getInt("priority"),
                createdAt = t.timestamp("createdAt"), updatedAt = t.timestamp("updatedAt"),
                completedAt = t.optionalTime("completedAt"), startedAt = t.optionalTime("startedAt"),
                archived = t.getBoolean("archived"), remindAfter = t.timestamp("remindAfter"),
                repeatRule = if (version >= 3) t.getString("repeatRule") else RepeatRule.NONE.name,
                nextOccurrenceId = if (version >= 3 && !t.isNull("nextOccurrenceId")) t.id("nextOccurrenceId") else null,
                snoozeCount = if (version >= 3) t.getInt("snoozeCount") else 0,
                checklist = if (version >= 4) t.getJSONArray("checklist").toString().also { Checklist.decode(it) } else "[]")
                .also { require(it.title.isNotBlank() && it.title.length <= 200 && it.notes.length <= 4000 &&
                    it.minutes in 1..1440 && it.priority in 0..2 && it.updatedAt >= it.createdAt &&
                    it.repeatRule in RepeatRule.entries.map { rule -> rule.name } && it.snoozeCount in 0..100_000 &&
                    it.nextOccurrenceId != it.id && (it.nextOccurrenceId == null || it.completedAt != null)) { "Invalid task." } }
        } }
        val ids = tasks.map { it.id }.toSet()
        require(ids.size == tasks.size) { "Duplicate tasks." }
        val events = (0 until eventsJson.length()).map { index -> eventsJson.getJSONObject(index).let { e ->
            TaskEvent(id = e.id("id"), taskId = e.id("taskId"), title = e.getString("title"),
                kind = e.getString("kind"), at = e.timestamp("at"), detail = e.getString("detail"))
                .also { require(it.taskId in ids && it.title.isNotBlank() && it.title.length <= 200 &&
                    it.detail.length <= 4000 && it.kind in setOf("CREATED", "EDITED", "STARTED", "COMPLETED", "REOPENED", "SNOOZED", "RESCHEDULED", "CHECKLIST", "ARCHIVED", "RESTORED")) { "Invalid history record." } }
        } }
        require(events.map { it.id }.toSet().size == events.size) { "Duplicate history records." }
        val deletedJson = if (version >= 2) root.getJSONArray("deletions") else JSONArray()
        require(deletedJson.length() <= 100_000) { "Backup has too many deletion records." }
        val deletions = (0 until deletedJson.length()).map { index -> deletedJson.getJSONObject(index).let {
            TaskDeletion(id = it.id("id"), deletedAt = it.timestamp("deletedAt"))
        } }
        require(deletions.map { it.id }.toSet().size == deletions.size) { "Duplicate deletion records." }
        require(deletions.none { it.id in ids }) { "A deleted task cannot also be active in the backup." }
        return Contents(tasks, events, deletions)
    }
    /** Validate everything first, then merge atomically. A stale backup never overwrites newer work. */
    suspend fun restore(repository: TaskRepository, text: String): Int {
        val contents = decode(text)
        return repository.database.withTransaction {
            var restored = 0
            contents.deletions.forEach { deletion ->
                repository.dao.rememberDeletion(deletion)
                repository.dao.deleteEvents(deletion.id)
                repository.dao.deleteTask(deletion.id)
            }
            val deleted = repository.dao.allDeletions().mapTo(mutableSetOf()) { it.id }
            contents.tasks.filterNot { it.id in deleted }.forEach { task ->
                val existing = repository.dao.task(task.id)
                if (existing == null || task.updatedAt > existing.updatedAt) {
                    repository.dao.put(task)
                    restored++
                }
            }
            contents.events.filterNot { it.taskId in deleted }.forEach { repository.dao.event(it) }
            restored
        }.also { repository.notifyChanged() }
    }
}
