package com.dracula.taskbite

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TaskBiteApp::class)
class AppUpdatesTest {
    private val app get() = RuntimeEnvironment.getApplication() as TaskBiteApp
    @Before fun setup() { AppUpdates.setAutomatic(app, false) }
    private fun transport(error: IOException? = null, wrongSigner: Boolean = false, corrupt: Boolean = false) = object : UpdateTransport {
        override suspend fun read(url: String, maxBytes: Int): ByteArray {
            error?.let { throw it }
            return (if (url == UpdateRelease.API) UpdateFixture.release().toString() else UpdateFixture.manifest().toString()).toByteArray()
        }
        override suspend fun download(url: String, target: File, size: Long, progress: suspend (Int) -> Unit) {
            error?.let { throw it }
            target.writeBytes(if (corrupt) ByteArray(size.toInt()) else UpdateFixture.bytes)
            shadowOf(app.packageManager).setPackageArchiveInfo(target.path,
                UpdateFixture.info(UpdateFixture.candidate().versionCode, if (wrongSigner) 9 else 1))
            progress(100)
        }
    }
    @Suppress("DEPRECATION") private fun installIdentity() {
        val own = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
        own.signingInfo = UpdateFixture.info(BuildConfig.VERSION_CODE.toLong()).signingInfo
        shadowOf(app.packageManager).installPackage(own)
    }
    private fun worker() = TestListenableWorkerBuilder<AppUpdateDownloadWorker>(app)
        .setInputData(workDataOf("candidateKey" to UpdateFixture.candidate().key)).build()

    @Test fun successfulCheckAndNoReleaseDoNotChangeTasksHistoryOrVoiceSettings() = runBlocking {
        app.repository.save(Task(title = "Keep my task", dueAt = System.currentTimeMillis() + 86_400_000))
        val tasks = app.repository.dao.allTasks(); val history = app.repository.dao.allEvents()
        val prefs = app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all.toMap()
        assertTrue(AppUpdates.check(app, transport()))
        assertEquals(UpdateFixture.candidate(), AppUpdates.state(app).candidate)
        assertTrue(AppUpdates.check(app, transport(UpdateHttpException(404))))
        assertEquals("no_release", AppUpdates.state(app).result)
        assertNull(AppUpdates.state(app).candidate)
        assertEquals(tasks, app.repository.dao.allTasks()); assertEquals(history, app.repository.dao.allEvents())
        assertEquals(prefs, app.getSharedPreferences("taskbite", Context.MODE_PRIVATE).all)
    }
    @Test fun offlineChecksKeepAnAlreadyDownloadedVerifiedUpdate() = runBlocking {
        val c = UpdateFixture.candidate()
        AppUpdates.saveCandidate(app, c)
        AppUpdates.apk(app, c).writeBytes(UpdateFixture.bytes); AppUpdates.ready(app, c)
        assertFalse(AppUpdates.check(app, transport(IOException("offline"))))
        assertTrue(AppUpdates.state(app).ready)
        assertEquals(c, AppUpdates.state(app).candidate)
        assertTrue(AppUpdates.state(app).error!!.contains("connection"))
    }
    @Test fun signedDownloadProvidesOnlyAReadGrantedContentUriAndRechecksTamperingBeforeInstall() = runBlocking {
        installIdentity()
        val c = UpdateFixture.candidate()
        AppUpdates.saveCandidate(app, c)
        assertEquals(ListenableWorker.Result.success(), worker().perform(transport()))
        assertTrue(AppUpdates.state(app).ready)
        val apk = AppUpdates.apk(app, c)
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, UpdateFixture.info(c.versionCode))
        val intent = AppUpdates.installIntent(app, c)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${app.packageName}.updates", intent.data!!.authority)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertArrayEquals(UpdateFixture.bytes, app.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.updates", File(app.filesDir, "private-voice.audio"))
        }
        apk.writeBytes(ByteArray(UpdateFixture.bytes.size))
        var rejected = false
        try { AppUpdates.installIntent(app, c) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertFalse(AppUpdates.state(app).ready); assertFalse(apk.exists())
    }
    @Test fun corruptAndWronglySignedDownloadsNeverBecomeInstallable() = runBlocking {
        installIdentity()
        val c = UpdateFixture.candidate()
        AppUpdates.saveCandidate(app, c)
        for (source in listOf(transport(corrupt = true), transport(wrongSigner = true))) {
            assertEquals(ListenableWorker.Result.failure(), worker().perform(source))
            assertFalse(AppUpdates.state(app).ready)
            assertFalse(AppUpdates.apk(app, c).exists())
            assertTrue(AppUpdates.directory(app).listFiles()!!.none { it.extension == "part" })
        }
    }
    @Test fun downloadedOldVersionIsClearedAfterAnUpgradeAndCannotBeDowngraded() {
        val old = UpdateFixture.candidate(BuildConfig.VERSION_CODE.toLong())
        app.getSharedPreferences("update_state", Context.MODE_PRIVATE).edit().putString("candidate", old.saved()).putString("ready", old.key).apply()
        val file = AppUpdates.apk(app, old).apply { writeBytes(UpdateFixture.bytes) }
        AppUpdates.start(app)
        assertNull(AppUpdates.state(app).candidate); assertFalse(AppUpdates.state(app).ready)
        assertFalse(file.exists()); assertEquals("current", AppUpdates.state(app).result)
    }
    @Test fun automaticDownloadsRequireUnmeteredNetworkAndManualDownloadsCanUseTheCurrentConnection() {
        // Large Unicode release notes must never overflow WorkManager's 10 KB input limit.
        val c = UpdateFixture.candidate().copy(notes = "改".repeat(4000))
        AppUpdates.saveCandidate(app, c)
        AppUpdates.setAutomatic(app, true)
        val work = WorkManager.getInstance(app)
        val automatic = work.getWorkInfosForUniqueWork(AppUpdates.DOWNLOAD).get().single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, automatic.constraints.requiredNetworkType)
        AppUpdates.download(app, c)
        val manual = work.getWorkInfosForUniqueWork(AppUpdates.DOWNLOAD).get().single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, manual.constraints.requiredNetworkType)
        assertFalse(AppUpdates.AUTOMATIC in manual.tags)
        AppUpdates.setAutomatic(app, false)
        assertTrue(work.getWorkInfosForUniqueWork(AppUpdates.DAILY).get().all { it.state.isFinished })
        assertTrue(work.getWorkInfosForUniqueWork(AppUpdates.DOWNLOAD).get().any { !it.state.isFinished })
        AppUpdates.cancelDownload(app)
        assertTrue(work.getWorkInfosForUniqueWork(AppUpdates.DOWNLOAD).get().all { it.state.isFinished })
    }
    @Test fun updateNotificationsAreSilentAndNeverTriggerTaskNotificationReactions() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(AppUpdates.check(app, transport()))
        val notice = app.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == "app_update" }.notification
        assertNull(notice.deleteIntent)
        assertEquals(AppUpdates.OPEN, shadowOf(notice.contentIntent).savedIntent.action)
        assertEquals("app_updates", notice.channelId)
        assertNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel(notice.channelId).sound)
        assertTrue(app.repository.dao.allTasks().isEmpty()); assertTrue(app.repository.dao.allEvents().isEmpty())
    }
}
