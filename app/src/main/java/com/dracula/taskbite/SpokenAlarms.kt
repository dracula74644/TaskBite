package com.dracula.taskbite

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Exact alarms are opt-in and only used for the user's spoken due-task reminders. */
object SpokenAlarms {
    private val mutex = Mutex()
    fun available(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 7,
        Intent(context, SpokenAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    suspend fun schedule(context: Context) = mutex.withLock {
        val app = context.applicationContext as TaskBiteApp
        val prefs = app.preferences
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = intent(context)
        if (!prefs.onboarded || !prefs.reminders || !prefs.backgroundVoice || !available(context) || !Reminders.allowed(context) ||
            (prefs.quietEnabled && prefs.quietStart == prefs.quietEnd)) {
            manager.cancel(alarm)
            return@withLock
        }
        val due = app.repository.dao.pending().minOfOrNull { maxOf(it.dueAt, it.remindAfter) }
        if (due == null) { manager.cancel(alarm); return@withLock }
        val now = System.currentTimeMillis()
        var at = maxOf(now + 5_000, due, prefs.lastReminder + ReminderPolicy.GLOBAL_COOLDOWN)
        val local = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        if (prefs.isQuiet(local.hour)) {
            var date = local.toLocalDate()
            if (prefs.quietStart > prefs.quietEnd && local.hour >= prefs.quietStart) date = date.plusDays(1)
            at = date.atTime(prefs.quietEnd, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm) }
        catch (_: SecurityException) { /* Permission revoked; WorkManager retains notification fallback. */ }
    }
}

class SpokenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { ContextCompat.startForegroundService(context, Intent(context, SpokenReminderService::class.java)) }
        catch (_: RuntimeException) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInputData(workDataOf("fallback" to true)).build())
        }
    }
}

class SpokenReminderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { job?.cancel(); stopSelf(); return START_NOT_STICKY }
        val channel = "taskbite_speech"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Spoken reminder playback", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 8, Intent(this, SpokenReminderService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Bite has a quick reminder")
            .setContentText("Reading a pending task aloud").setOngoing(true).setSilent(true)
            .addAction(0, "Stop speaking", stop).build()
        try {
            ServiceCompat.startForeground(this, 8080, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        job?.cancel()
        job = scope.launch {
            try {
                ReminderDelivery.deliver(application as TaskBiteApp, false, true)
                SpokenAlarms.schedule(this@SpokenReminderService)
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as TaskBiteApp
                Reminders.start(context)
                Reminders.schedule(context, app.repository)
            } finally { result.finish() }
        }
    }
}
