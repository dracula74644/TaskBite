package com.dracula.taskbite

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUpdateState(val automatic: Boolean, val candidate: UpdateCandidate?, val ready: Boolean,
    val checkedAt: Long, val result: String, val error: String?)

object AppUpdates {
    const val OPEN = "com.dracula.taskbite.OPEN_UPDATES"
    const val DAILY = "bite_update_daily"
    const val CHECK = "bite_update_check"
    const val DOWNLOAD = "bite_update_download"
    const val AUTOMATIC = "bite_update_automatic"
    private const val CHANNEL = "app_updates"
    private const val NOTICE = 9401
    private val checkLock = Mutex()
    internal val downloadLock = Mutex()
    private fun settings(context: Context) = context.getSharedPreferences("update_preferences", Context.MODE_PRIVATE)
    private fun storage(context: Context) = context.getSharedPreferences("update_state", Context.MODE_PRIVATE)
    fun automatic(context: Context) = settings(context).getBoolean("automatic", true)
    fun directory(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }
    fun apk(context: Context, candidate: UpdateCandidate) = File(directory(context), "update-${candidate.versionCode}-${candidate.sha256.take(16)}.apk")
    fun state(context: Context): AppUpdateState {
        val p = storage(context)
        val candidate = UpdateCandidate.restore(p.getString("candidate", null))?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        val ready = candidate != null && p.getString("ready", null) == candidate.key && apk(context, candidate).let { it.isFile && it.length() == candidate.size }
        return AppUpdateState(automatic(context), candidate, ready, p.getLong("checkedAt", 0), p.getString("result", "none") ?: "none", p.getString("error", null))
    }
    fun observe(context: Context) = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(state(context)) }
        storage(context).registerOnSharedPreferenceChangeListener(listener)
        settings(context).registerOnSharedPreferenceChangeListener(listener)
        trySend(state(context))
        awaitClose {
            storage(context).unregisterOnSharedPreferenceChangeListener(listener)
            settings(context).unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    fun start(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "New Bite versions and completed update downloads"
            setSound(null, null); enableVibration(false)
        })
        val old = UpdateCandidate.restore(storage(context).getString("candidate", null))
        if (old != null && old.versionCode <= BuildConfig.VERSION_CODE) {
            storage(context).edit().remove("candidate").remove("ready").remove("error").putString("result", "current").apply()
            NotificationManagerCompat.from(context).cancel("app_update", NOTICE)
        }
        directory(context).listFiles()?.forEach { file ->
            val version = Regex("update-(\\d+)-[a-f0-9]+\\.apk").matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (version != null && version <= BuildConfig.VERSION_CODE) file.delete()
            else if (file.name.endsWith(".part") && System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(1)) file.delete()
        }
        schedule(context)
    }
    fun setAutomatic(context: Context, enabled: Boolean) {
        settings(context).edit().putBoolean("automatic", enabled).apply()
        schedule(context)
        if (enabled) state(context).candidate?.takeIf { it.minSdk <= Build.VERSION.SDK_INT && !state(context).ready }?.let {
            download(context, it, automatic = true)
        }
    }
    private fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!automatic(context)) {
            work.cancelUniqueWork(DAILY)
            work.cancelAllWorkByTag(AUTOMATIC)
            return
        }
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("automatic" to true)).addTag(AUTOMATIC)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun download(context: Context, candidate: UpdateCandidate, automatic: Boolean = false) {
        if (state(context).candidate?.key != candidate.key || state(context).ready || candidate.minSdk > Build.VERSION.SDK_INT) return
        if (automatic && !AppUpdates.automatic(context)) return
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setInputData(workDataOf("candidateKey" to candidate.key, "automatic" to automatic))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (automatic) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
        if (automatic) request.addTag(AUTOMATIC)
        storage(context).edit().remove("error").apply()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD,
            if (automatic) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, request.build())
    }
    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD)
        storage(context).edit().putString("error", "Download canceled. You can download it again when you're ready.").apply()
    }
    internal fun failure(context: Context, message: String) { storage(context).edit().putString("error", message).apply() }
    internal fun saveCandidate(context: Context, candidate: UpdateCandidate?, now: Long = System.currentTimeMillis()) {
        val p = storage(context)
        val previous = UpdateCandidate.restore(p.getString("candidate", null))
        val newer = candidate?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        val result = when {
            candidate == null -> "no_release"
            newer == null -> "current"
            newer.minSdk > Build.VERSION.SDK_INT -> "incompatible"
            else -> "available"
        }
        p.edit().putString("candidate", newer?.saved()).putLong("checkedAt", now).putString("result", result).remove("error").apply()
        if (previous?.key != newer?.key) {
            p.edit().remove("ready").apply()
            WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD)
        }
        if (newer == null || result == "incompatible") NotificationManagerCompat.from(context).cancel("app_update", NOTICE)
    }
    suspend fun check(context: Context, transport: UpdateTransport = GithubUpdateTransport): Boolean = checkLock.withLock {
        try {
            val candidate = UpdateRelease.latest(transport)
            currentCoroutineContext().ensureActive()
            saveCandidate(context, candidate)
            state(context).candidate?.takeIf { it.minSdk <= Build.VERSION.SDK_INT }?.let {
                notify(context, it, state(context).ready)
                if (automatic(context) && !state(context).ready) download(context, it, automatic = true)
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (e: IOException) {
            failure(context, if (e is UpdateHttpException && e.status in setOf(403, 429)) "GitHub is limiting update checks. Please try again later."
                else "Couldn't reach GitHub. Check your connection and try again.")
            false
        } catch (_: Exception) { failure(context, "This release's update files couldn't be verified. Please check again later."); false }
    }
    internal fun ready(context: Context, candidate: UpdateCandidate) {
        if (state(context).candidate?.key != candidate.key) return
        storage(context).edit().putString("ready", candidate.key).remove("error").apply()
        notify(context, candidate, true)
    }
    private fun notify(context: Context, candidate: UpdateCandidate, ready: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val key = "${candidate.key}-$ready"
        if (storage(context).getString("notified", null) == key) return
        val open = PendingIntent.getActivity(context, NOTICE, Intent(context, MainActivity::class.java).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ready) "Bite ${candidate.versionName} is ready to install" else "Bite ${candidate.versionName} is available")
            .setContentText(if (ready) "Tap to review and install the update." else "Tap to see what's new and update Bite.")
            .setContentIntent(open).setSilent(true).setOnlyAlertOnce(true).setAutoCancel(true).build()
        try { manager.notify("app_update", NOTICE, notice); storage(context).edit().putString("notified", key).apply() }
        catch (_: SecurityException) { /* The update remains available inside Sidekick. */ }
    }
    suspend fun installIntent(context: Context, candidate: UpdateCandidate): Intent = withContext(Dispatchers.IO) {
        require(state(context).candidate?.key == candidate.key && state(context).ready) { "Check for updates and download this version again." }
        val file = apk(context, candidate)
        try { UpdateRelease.verifyApk(context, file, candidate) }
        catch (e: Exception) {
            storage(context).edit().remove("ready").apply()
            file.delete()
            throw e
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Bite update", uri) }
    }
}

class AppUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!inputData.getBoolean("automatic", false) || AppUpdates.automatic(applicationContext)) AppUpdates.check(applicationContext)
        // The next daily run or Check now can retry; no repeated error notifications.
        return Result.success()
    }
}

class AppUpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = perform(GithubUpdateTransport)
    internal suspend fun perform(transport: UpdateTransport): Result = AppUpdates.downloadLock.withLock {
        val context = applicationContext
        val candidate = AppUpdates.state(context).candidate?.takeIf { it.key == inputData.getString("candidateKey") }
            ?: return@withLock Result.success()
        val automatic = inputData.getBoolean("automatic", false)
        if (candidate.versionCode <= BuildConfig.VERSION_CODE || AppUpdates.state(context).candidate?.key != candidate.key ||
            (automatic && !AppUpdates.automatic(context))) return@withLock Result.success()
        val temporary = File(AppUpdates.directory(context), "$id.part")
        try {
            transport.download(candidate.apkUrl, temporary, candidate.size) { percent -> setProgress(workDataOf("percent" to percent)) }
            withContext(Dispatchers.IO) { UpdateRelease.verifyApk(context, temporary, candidate) }
            currentCoroutineContext().ensureActive()
            if (AppUpdates.state(context).candidate?.key != candidate.key || (automatic && !AppUpdates.automatic(context))) return@withLock Result.success()
            val target = AppUpdates.apk(context, candidate)
            check(temporary.renameTo(target)) { "Couldn't save the downloaded update." }
            AppUpdates.ready(context, candidate)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { AppUpdates.failure(context, "Download interrupted. Check your connection and retry."); Result.failure() }
        catch (_: Exception) { AppUpdates.failure(context, "The update failed verification. Your installed app is unchanged. Check for updates and try again."); Result.failure() }
        finally { temporary.delete() }
    }
}
