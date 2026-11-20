package com.dracula.taskbite

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Pending includes future tasks; the list includes overdue tasks plus the rest of today. */
data class TaskWidgetModel(val today: List<Task>, val pendingCount: Int, val overdueCount: Int, val doneToday: Int) {
    companion object {
        fun from(tasks: List<Task>, now: Long, zone: ZoneId = ZoneId.systemDefault()): TaskWidgetModel {
            val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val active = tasks.filterNot { it.archived }
            val pending = active.filter { it.completedAt == null }
            return TaskWidgetModel(
                pending.filter { it.dueAt < end }.sortedWith(compareBy<Task> { it.dueAt }.thenByDescending { it.priority }.thenBy { it.id }),
                pending.size,
                pending.count { it.dueAt <= now },
                active.count { it.completedAt?.let { at -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() } == day },
            )
        }
    }
}

data class WidgetRequest(val action: String, val taskId: String? = null, val token: String = UUID.randomUUID().toString())

object WidgetNavigation {
    const val OPEN = "com.dracula.taskbite.widget.OPEN"
    const val ADD = "com.dracula.taskbite.widget.ADD"
    const val VOICE = "com.dracula.taskbite.widget.VOICE"
    const val EDIT = "com.dracula.taskbite.widget.EDIT"
    const val TASK_ID = "widget_task_id"
    fun intent(context: Context, action: String, taskId: String? = null): Intent =
        Intent(context, MainActivity::class.java).setAction(action)
            .setData(Uri.Builder().scheme("taskbite").authority("widget").appendPath(action).appendPath(taskId ?: "home").build())
            .putExtra(TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    fun parse(intent: Intent): WidgetRequest? {
        val action = intent.action ?: return null
        if (action !in setOf(OPEN, ADD, VOICE, EDIT)) return null
        val id = intent.getStringExtra(TASK_ID)
        if (action == EDIT && id.isNullOrBlank()) return null
        return WidgetRequest(action, id)
    }
}
