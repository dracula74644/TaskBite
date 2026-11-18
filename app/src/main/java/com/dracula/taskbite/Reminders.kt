package com.dracula.taskbite

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object Reminders {
    const val CHANNEL = "taskbite_reminders"
    private const val PERIODIC = "taskbite_periodic"
    private const val NEXT = "taskbite_next"
    private const val EXIT = "taskbite_exit"
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Due tasks and your sarcastic sidekick"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
        )
    }
    fun start(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build())
    }
    suspend fun schedule(context: Context, repository: TaskRepository) {
        SpokenAlarms.schedule(context)
        val now = System.currentTimeMillis()
        val next = repository.dao.pending().minOfOrNull { maxOf(it.dueAt, it.remindAfter) }
        val work = WorkManager.getInstance(context)
        if (next == null) work.cancelUniqueWork(NEXT)
        else work.enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(maxOf(1_000, next - now), TimeUnit.MILLISECONDS).build())
    }
    fun abandon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(EXIT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(workDataOf("abandon" to true))
                .setInitialDelay(30, TimeUnit.SECONDS).build())
    }
    fun cancelAbandon(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(EXIT) }
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun clear(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(id, 1)
    fun notify(context: Context, task: Task, line: String) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderActionReceiver::class.java).setAction(name)
                .setData(android.net.Uri.parse("taskbite://task/${task.id}/$name"))
                .putExtra("id", task.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.dracula.taskbite.R.drawable.ic_notification)
            .setContentTitle("Still pending: ${task.title}")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Done", action("done"))
            .addAction(0, "Tomorrow", action("snooze"))
            .build()
        try { NotificationManagerCompat.from(context).notify(task.id, 1, notification) }
        catch (_: SecurityException) { /* Permission can be revoked between checking and posting. */ }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TaskBiteApp
        val abandon = inputData.getBoolean("abandon", false)
        if (!abandon && app.preferences.backgroundVoice && SpokenAlarms.available(app) &&
            !inputData.getBoolean("fallback", false)) {
            SpokenAlarms.schedule(app)
        } else ReminderDelivery.deliver(app, abandon, false)
        return Result.success()
    }
}

object ReminderDelivery {
    private val mutex = Mutex()
    suspend fun deliver(app: TaskBiteApp, abandon: Boolean, allowVoice: Boolean) = mutex.withLock {
        val prefs = app.preferences
        val now = System.currentTimeMillis()
        if (!prefs.onboarded || !prefs.reminders || prefs.isQuiet() || !Reminders.allowed(app) ||
            now - prefs.lastReminder < ReminderPolicy.GLOBAL_COOLDOWN) return@withLock
        if (abandon && app.foreground) return@withLock
        val pending = app.repository.dao.pending()
        val candidate = if (abandon) pending.firstOrNull() else pending.firstOrNull { ReminderPolicy.eligible(it, now) }
        if (candidate == null) return@withLock
        val task = app.database.withTransaction {
            val fresh = app.repository.dao.task(candidate.id) ?: return@withTransaction null
            if (fresh.archived || fresh.completedAt != null || (!abandon && !ReminderPolicy.eligible(fresh, now))) null
            else {
                app.repository.dao.put(fresh.copy(remindAfter = now + ReminderPolicy.TASK_COOLDOWN))
                fresh
            }
        } ?: return@withLock
        val line = Roasts.pick(if (abandon) RoastKind.ABANDON else ReminderPolicy.kind(task))
        Reminders.notify(app, task, line)
        prefs.lastReminder = now
        if (allowVoice && prefs.backgroundVoice && !app.foreground && !prefs.isQuiet()) {
            withContext(Dispatchers.Main) {
                val voice = Voice(app)
                try { voice.speak("Pending: ${task.title.take(100)}. $line") } finally { voice.close() }
            }
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val action = intent.action ?: return
        if (action !in listOf("done", "snooze")) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as TaskBiteApp
                when (action) {
                    "done" -> app.repository.complete(id, true)
                    "snooze" -> app.repository.snooze(id)
                }
                Reminders.clear(context, id)
                Reminders.schedule(context, app.repository)
            } finally { pendingResult.finish() }
        }
    }
}
