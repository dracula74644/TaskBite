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

/** Exact alarms are opt-in and used for the user's spoken reminders and away check-ins. */
object SpokenAlarms {
    private val mutex = Mutex()
    fun available(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 7,
        Intent(context, SpokenAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    suspend fun schedule(context: Context, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val app = context.applicationContext as TaskBiteApp
        val prefs = app.preferences
        val manager = context.getSystemService(AlarmManager::class.java)
        val alarm = intent(context)
        if (!available(context) || !Reminders.allowed(context)) {
            manager.cancel(alarm)
            return@withLock
        }
        val regular = if (prefs.backgroundVoice) Reminders.nextAt(app, now) else null
        val queued = ReminderAudio.peek(app, now)?.takeIf {
            (if (it.interaction) prefs.notificationReactions else prefs.backgroundVoice) &&
                (it.preview || !prefs.isQuiet(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour))
        }
        val due = listOfNotNull(regular, queued?.at, NotificationTest.nextAt(app, now)).minOrNull()
        if (due == null) { manager.cancel(alarm); return@withLock }
        val at = maxOf(now + if (queued?.interaction == true) 100 else 1_000, due)
        try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm) }
        catch (_: SecurityException) { DeliveryStatus.audio(app, "Alarm access was revoked. Notifications can still arrive; enable Alarms & reminders for background voice.") }
    }

    fun notificationFallback(context: Context, message: String) {
        ReminderAudio.clear(context)
        DeliveryStatus.audio(context, message)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf("fallback" to true)).build())
    }
}

class SpokenAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeliveryStatus.alarm(context)
        try { ContextCompat.startForegroundService(context, Intent(context, SpokenReminderService::class.java)) }
        catch (_: RuntimeException) {
            SpokenAlarms.notificationFallback(context, "Android blocked background playback. A notification-only check was queued. Check Bite's battery restrictions.")
        }
    }
}

class SpokenReminderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var generation = 0
    private var latestStartId = 0
    private var interactionInProgress = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == "stop") { ReminderAudio.clear(this); job?.cancel(); stopSelf(); return START_NOT_STICKY }
        val isDismissal = intent?.action == NotificationReactions.DISMISS
        // A reminder alarm already in flight must not cancel a user's clearing voice.
        // Its pending work is rescheduled after the interaction finishes.
        if (!isDismissal && interactionInProgress && job?.isActive == true) return START_NOT_STICKY
        // New delete intents start this service directly. Older notifications still
        // arrive through the receiver with their original, expiring handoff time.
        val requestedAt = intent?.getLongExtra(NotificationReactions.DISMISSED_AT, System.currentTimeMillis()) ?: 0
        val dismissal = if (intent?.action == NotificationReactions.DISMISS) {
            val token = intent.getStringExtra(NotificationReactions.TOKEN)
            if (token != null && System.currentTimeMillis() - requestedAt in 0..ReminderAudio.INTERACTION_LIFETIME)
                NotificationReactions.claimDismissal(this, token, requestedAt) else {
                DeliveryStatus.audio(this, "Dismissal voice skipped because Android delivered the action too late.")
                DeliveryStatus.dismissal(this, "Android delivered the clearing action too late to play promptly.")
                null
            }
        } else null
        if (intent?.action == NotificationReactions.DISMISS && dismissal == null) {
            // Duplicate clear-all callbacks must not cancel or delay the first voice.
            if (job?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        val currentGeneration = ++generation
        val token = intent?.getStringExtra(ReminderAudio.TOKEN)
        interactionInProgress = dismissal != null || ReminderAudio.peek(this)?.let {
            it.interaction && (token == null || it.token == token)
        } == true
        val channel = "taskbite_speech"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Spoken reminder playback", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 8, Intent(this, SpokenReminderService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Bite has a quick reminder")
            .setContentText("Playing Bite’s recorded reaction").setOngoing(true).setSilent(true)
            .addAction(0, "Stop audio", stop).build()
        try {
            ServiceCompat.startForeground(this, 8080, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (_: RuntimeException) {
            if (interactionInProgress) DeliveryStatus.dismissal(this, "Android couldn't start the clearing voice service. Check Bite's background battery access.")
            SpokenAlarms.notificationFallback(this, "Android couldn't start the voice service. Notifications remain enabled; check Bite's battery restrictions.")
            job?.cancel(); stopSelf(); return START_NOT_STICKY
        }
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val app = application as TaskBiteApp
            try {
                if (dismissal != null) {
                    val audio = NotificationReactions.prepareDismissal(app, dismissal, requestedAt)
                    if (audio != null) ReminderAudio.playPending(app, audio.token)
                } else if (token != null) ReminderAudio.playPending(app, token)
                else if (!ReminderAudio.playPending(app) && !NotificationTest.deliver(app, allowVoice = true))
                    ReminderDelivery.deliver(app, false, true)
                Reminders.schedule(app, app.repository)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (interactionInProgress) DeliveryStatus.dismissal(app, "The clearing voice stopped unexpectedly. Try the background test in Sidekick.")
                SpokenAlarms.notificationFallback(app, "The voice service stopped unexpectedly. A notification-only check was queued.")
            } finally {
                if (generation == currentGeneration) { interactionInProgress = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStartId) }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        TaskWidgets.ensurePeriodic(context)
        TaskWidgets.requestRefresh(context)
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
