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
                setSound(null, null)
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
        val app = context.applicationContext as TaskBiteApp
        val next = nextAt(app, now)
        val work = WorkManager.getInstance(context)
        if (next == null) work.cancelUniqueWork(NEXT)
        else work.enqueueUniqueWork(NEXT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(maxOf(1_000, next - now), TimeUnit.MILLISECONDS).build())
    }
    suspend fun nextAt(app: TaskBiteApp, now: Long = System.currentTimeMillis()): Long? {
        val p = app.preferences
        if (!p.onboarded || !p.reminders || !allowed(app) || (p.quietEnabled && p.quietStart == p.quietEnd)) return null
        val taskDue = app.repository.dao.pending().minOfOrNull {
            maxOf(it.dueAt, it.remindAfter, if (p.deadlinesDuringPause) 0L else p.teasingPausedUntil)
        }
        val regular = listOfNotNull(taskDue, PlanningNudges.nextAt(app, now)).minOrNull()
            ?.let { maxOf(now, it, p.lastReminder + ReminderPolicy.GLOBAL_COOLDOWN) }
        var at = listOfNotNull(regular, AwayNudges.next(app, now)?.at).minOrNull() ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val local = java.time.Instant.ofEpochMilli(at).atZone(zone)
        if (p.isQuiet(local.hour)) {
            val day = if (p.quietStart > p.quietEnd && local.hour >= p.quietStart) local.toLocalDate().plusDays(1) else local.toLocalDate()
            at = day.atTime(p.quietEnd, 0).atZone(zone).toInstant().toEpochMilli()
        }
        return at
    }
    fun abandon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(EXIT, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(workDataOf("abandon" to true))
                .setInitialDelay(AwayPolicy.gapMillis(Preferences(context).awayGapMinutes), TimeUnit.MILLISECONDS).build())
    }
    fun cancelAbandon(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(EXIT) }
    fun allowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun clear(context: Context, id: String) {
        NotificationReactions.forget(context, id, 1)
        NotificationManagerCompat.from(context).cancel(id, 1); clearAway(context)
    }
    fun clearAway(context: Context) {
        NotificationReactions.forget(context, "away_task", 4)
        NotificationReactions.forget(context, "away_plan", 3)
        NotificationManagerCompat.from(context).cancel("away_task", 4)
        NotificationManagerCompat.from(context).cancel("away_plan", 3)
    }
    fun clearPlanning(context: Context) {
        NotificationReactions.forget(context, "plan_day", 2)
        NotificationReactions.forget(context, "away_plan", 3)
        NotificationManagerCompat.from(context).cancel("plan_day", 2)
        NotificationManagerCompat.from(context).cancel("away_plan", 3)
    }
    fun notifyPlanning(context: Context, line: String, textOnly: Boolean = false, followUp: Boolean = false,
        titleOverride: String? = null, test: Boolean = false, now: Long = System.currentTimeMillis()): BiteNotice? {
        if (!allowed(context)) return null
        val tag = if (test) "delivery_test" else if (followUp) "away_plan" else "plan_day"
        val id = if (test) 5 else if (followUp) 3 else 2
        val notice = NotificationReactions.register(context, tag, id, null, now, test)
        val add = NotificationReactions.content(context, notice, add = !test)
        val title = titleOverride ?: if (test) "Background nudge test" else if (followUp) AwayScenario.EMPTY_DAY.title(null) else "Give today a little plan"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            // Also suppress sound on existing installs whose channel still has a chime.
            .setSilent(true)
            .setSmallIcon(com.dracula.taskbite.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(line).setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setSubText(if (textOnly) "Text only · no matching recording" else null)
            .setContentIntent(add).setAutoCancel(true).setOnlyAlertOnce(!followUp && !test)
            .setDeleteIntent(NotificationReactions.delete(context, notice))
            .addExtras(android.os.Bundle().apply { putString(NotificationReactions.TOKEN, notice.token) })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, if (test) "Open Bite" else "Add a task", add).build()
        return try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
            DeliveryStatus.posted(context, title); notice
        } catch (_: SecurityException) { NotificationReactions.forget(context, tag, id); null }
    }
    fun notify(context: Context, task: Task, line: String, textOnly: Boolean = false, awayTitle: String? = null,
        now: Long = System.currentTimeMillis()): BiteNotice? {
        if (!allowed(context)) return null
        val tag = if (awayTitle == null) task.id else "away_task"
        val id = if (awayTitle == null) 1 else 4
        val notice = NotificationReactions.register(context, tag, id, task, now)
        val open = NotificationReactions.content(context, notice)
        fun action(name: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderActionReceiver::class.java).setAction(name)
                .setData(android.net.Uri.parse("taskbite://task/${task.id}/$name"))
                .putExtra("id", task.id).putExtra("revision", task.updatedAt), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSilent(true)
            .setSmallIcon(com.dracula.taskbite.R.drawable.ic_notification)
            .setContentTitle(awayTitle ?: "Still pending: ${task.title}")
            .setContentText(line)
            .setSubText(if (textOnly) "Text only · no matching recording" else null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setContentIntent(open).setAutoCancel(true)
            .setDeleteIntent(NotificationReactions.delete(context, notice))
            .addExtras(android.os.Bundle().apply { putString(NotificationReactions.TOKEN, notice.token) })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Done", action("done"))
            .addAction(0, "Tomorrow", action("snooze"))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
            DeliveryStatus.posted(context, awayTitle ?: "Still pending: ${task.title}"); notice
        } catch (_: SecurityException) { NotificationReactions.forget(context, tag, id); null }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TaskBiteApp
        DeliveryStatus.worker(app)
        // Always post text from the worker. A failed or restricted voice service must
        // never suppress the notification along with its audio.
        if (!NotificationTest.deliver(app, allowVoice = false))
            ReminderDelivery.deliver(app, inputData.getBoolean("abandon", false), false)
        Reminders.schedule(app, app.repository)
        return Result.success()
    }
}

object ReminderDelivery {
    private val mutex = Mutex()
    suspend fun claimPlanningInApp(app: TaskBiteApp, now: Long = System.currentTimeMillis()): RoastKind? = mutex.withLock {
        val prefs = app.preferences
        if (!app.foreground || prefs.teasingPaused(now) || prefs.isQuiet() ||
            !InAppReactionPolicy.canNudge(now, prefs.lastForegroundReaction)) return@withLock null
        if (PlanningNudges.claim(app, now)) RoastKind.PLAN_DAY else null
    }
    /** The open task list can deliver a due reaction without notification/alarm permission. */
    suspend fun claimInApp(app: TaskBiteApp, now: Long = System.currentTimeMillis()): RoastKind? = mutex.withLock {
        val prefs = app.preferences
        if (!app.foreground || !prefs.onboarded || !prefs.actionVoice || prefs.teasingPaused(now) || prefs.isQuiet() ||
            now - prefs.lastReminder < ReminderPolicy.GLOBAL_COOLDOWN) return@withLock null
        val task = app.database.withTransaction {
            val due = app.repository.dao.pending().firstOrNull { ReminderPolicy.eligible(it, now) }
                ?: return@withTransaction null
            app.repository.dao.put(due.copy(remindAfter = now + ReminderPolicy.TASK_COOLDOWN))
            due
        } ?: return@withLock null
        prefs.lastReminder = now
        ReminderPolicy.kind(task)
    }
    suspend fun deliver(app: TaskBiteApp, abandon: Boolean, allowVoice: Boolean, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val prefs = app.preferences
        if (!prefs.onboarded || !prefs.reminders || prefs.isQuiet(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour) || !Reminders.allowed(app)) return@withLock
        if (prefs.teasingPaused(now) && !prefs.deadlinesDuringPause) return@withLock
        val pending = app.repository.dao.pending()
        val candidate = pending.firstOrNull { ReminderPolicy.eligible(it, now) && now - prefs.lastReminder >= ReminderPolicy.GLOBAL_COOLDOWN }
        if (candidate == null) {
            if (!abandon && PlanningNudges.claim(app, now)) {
                val reaction = VoiceLibrary(app).reaction(RoastKind.PLAN_DAY)
                val notice = Reminders.notifyPlanning(app, reaction.caption, textOnly = reaction.clip == null, now = now)
                playBackground(app, reaction, notice, allowVoice, now)
            } else deliverAway(app, now, allowVoice)
            return@withLock
        }
        val task = app.database.withTransaction {
            val fresh = app.repository.dao.task(candidate.id) ?: return@withTransaction null
            if (fresh.archived || fresh.completedAt != null || !ReminderPolicy.eligible(fresh, now)) null
            else {
                app.repository.dao.put(fresh.copy(remindAfter = now + ReminderPolicy.TASK_COOLDOWN))
                fresh
            }
        } ?: return@withLock
        val kind = ReminderPolicy.kind(task)
        val paused = prefs.teasingPaused(now)
        val reaction = if (paused) VoiceReaction(kind, prefs.teasingStyle, "Your task is due. Open Bite to review it.", null)
            else VoiceLibrary(app).reaction(kind)
        val notice = Reminders.notify(app, task, reaction.caption, textOnly = !paused && reaction.clip == null, now = now)
        prefs.lastReminder = now
        playBackground(app, reaction, notice, allowVoice, now)
    }
    private suspend fun deliverAway(app: TaskBiteApp, now: Long, allowVoice: Boolean) {
        val nudge = AwayNudges.claim(app, now) ?: return
        val followUp = NotificationReactions.followUp(app, nudge, now)
        val reaction = VoiceLibrary(app).reaction(followUp?.second ?: nudge.scenario.kind)
        val notice = if (nudge.task == null) Reminders.notifyPlanning(app, reaction.caption, reaction.clip == null,
            followUp = true, titleOverride = followUp?.first, now = now)
        else Reminders.notify(app, nudge.task, reaction.caption, reaction.clip == null, awayTitle = followUp?.first ?: nudge.title, now = now)
        playBackground(app, reaction, notice, allowVoice, now)
    }
    private suspend fun playBackground(app: TaskBiteApp, reaction: VoiceReaction, notice: BiteNotice?, allowVoice: Boolean, now: Long) {
        val prefs = app.preferences
        if (notice == null) return
        if (prefs.backgroundVoice && !prefs.teasingPaused(now) && !app.foreground && !prefs.isQuiet()) {
            if (allowVoice) ReminderAudio.play(app, reaction)
            else {
                ReminderAudio.enqueue(app, reaction, notice.token, now = now)
                if (!SpokenAlarms.available(app)) DeliveryStatus.audio(app, "Notification posted. Enable Alarms & reminders for background voice.")
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
                val changed = app.database.withTransaction {
                    val current = app.repository.dao.task(id) ?: return@withTransaction false
                    val revision = intent.getLongExtra("revision", -1)
                    if (revision >= 0 && current.updatedAt != revision) return@withTransaction false
                    when (action) {
                        "done" -> app.repository.complete(id, true, notify = false)
                        "snooze" -> app.repository.snooze(id, notify = false)
                        else -> false
                    }
                }
                if (changed) app.repository.notifyChanged()
                Reminders.clear(context, id)
                Reminders.schedule(context, app.repository)
            } finally { pendingResult.finish() }
        }
    }
}
