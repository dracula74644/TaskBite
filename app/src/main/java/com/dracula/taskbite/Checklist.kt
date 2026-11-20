package com.dracula.taskbite

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class ChecklistItem(val id: String = UUID.randomUUID().toString(), val text: String, val done: Boolean = false)

object Checklist {
    const val MAX_ITEMS = 30
    fun decode(raw: String): List<ChecklistItem> {
        require(raw.length <= 16_000) { "Checklist is too large." }
        val array = JSONArray(raw)
        require(array.length() <= MAX_ITEMS) { "Use up to 30 checklist items." }
        val items = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ChecklistItem(item.getString("id"), item.getString("text"), item.getBoolean("done")).also {
                require(runCatching { UUID.fromString(it.id).toString() == it.id }.getOrDefault(false))
                require(it.text.isNotBlank() && it.text.length <= 200)
            }
        }
        require(items.map { it.id }.toSet().size == items.size) { "Duplicate checklist item." }
        return items
    }
    fun encode(items: List<ChecklistItem>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
    }.toString().also { decode(it) }
    fun reset(raw: String) = encode(decode(raw).map { it.copy(done = false) })
    fun matches(task: Task, query: String): Boolean = query.trim().let { term ->
        term.isEmpty() || task.title.contains(term, true) || task.notes.contains(term, true) ||
            decode(task.checklist).any { it.text.contains(term, true) }
    }
}

object LaterToday {
    fun at(time: LocalTime, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val chosen = local.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
        require(chosen > now) { "Choose a later time today, or use Tomorrow." }
        return chosen
    }
}
