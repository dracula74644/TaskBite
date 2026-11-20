package com.dracula.taskbite

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun AppUpdateEntry(onOpen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by remember { AppUpdates.observe(context) }.collectAsStateWithLifecycle(initialValue = AppUpdates.state(context))
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("App updates")
            Text(if (state.ready) "Ready to install" else if (state.candidate != null) "New version available" else "Bite ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
    }
}

@Composable fun UpdatesScreen(activity: MainActivity, onClose: () -> Unit) {
    val state by remember { AppUpdates.observe(activity) }.collectAsStateWithLifecycle(initialValue = AppUpdates.state(activity))
    val manager = remember { WorkManager.getInstance(activity) }
    val checks by remember { manager.getWorkInfosForUniqueWorkFlow(AppUpdates.CHECK) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val daily by remember { manager.getWorkInfosForUniqueWorkFlow(AppUpdates.DAILY) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloads by remember { manager.getWorkInfosForUniqueWorkFlow(AppUpdates.DOWNLOAD) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val checking = checks.any { !it.state.isFinished } || daily.any { it.state == WorkInfo.State.RUNNING }
    val download = downloads.firstOrNull { !it.state.isFinished }
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(activity.packageManager.canRequestPackageInstalls()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canInstall = activity.packageManager.canRequestPackageInstalls() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onClose)
    fun open(intent: Intent) {
        try { activity.externalAction(); activity.startActivity(intent) }
        catch (_: ActivityNotFoundException) { feedback = "Your phone couldn't open that screen. Try again from Android settings." }
        catch (_: SecurityException) { feedback = "Android blocked this action. Check your phone's app installation settings." }
    }
    Scaffold(topBar = { TestToolsHeader("App updates", "Back to settings", onClose) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("app_updates"), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Bite ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineMedium)
                Text("Installed on this phone", color = Muted)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel, contentColor = Cream)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Automatic updates", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = state.automatic, onCheckedChange = { AppUpdates.setAutomatic(activity, it) },
                                modifier = Modifier.semantics { contentDescription = "Automatic app updates" })
                        }
                        Text("Check daily and download on unmetered Wi-Fi. You'll review the update before Android installs it.", color = Muted)
                        Text("Android may delay background checks. Check now whenever you want.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { feedback = null; AppUpdates.checkNow(activity) }, enabled = !checking,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (checking) "Checking for updates" else "Check now") }
                        if (checking) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (checks.any { it.state == WorkInfo.State.ENQUEUED }) Text("Waiting for a connection or for Android to start the check.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.checkedAt > 0) Text("Last checked · " + Instant.ofEpochMilli(state.checkedAt).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm")), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val candidate = state.candidate
                Card(colors = CardDefaults.cardColors(containerColor = Panel, contentColor = Cream)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (candidate == null) {
                            Text(when (state.result) { "no_release" -> "No published updates yet"; "current" -> "You're up to date"; else -> "Ready when the next version arrives" },
                                style = MaterialTheme.typography.titleLarge)
                            Text("Updates come from Dracula's TaskBite releases on GitHub.", color = Muted)
                        } else {
                            Text("Bite ${candidate.versionName}", style = MaterialTheme.typography.titleLarge)
                            Text("%.1f MB".format(candidate.size / (1024.0 * 1024.0)), color = Muted)
                            when {
                                candidate.minSdk > android.os.Build.VERSION.SDK_INT -> Text("This update needs a newer Android version. You can keep using your installed version.", color = Muted)
                                state.ready -> {
                                    Text("Downloaded and verified", color = Lime)
                                    Text("Your tasks, history, and saved settings stay on this phone.", color = Muted)
                                    if (!canInstall) {
                                        Text("Allow Bite to install updates in Android settings, then return here and tap Install update.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))) },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow installation") }
                                    } else Button(onClick = {
                                        installing = true; feedback = null
                                        scope.launch {
                                            try { open(AppUpdates.installIntent(activity, candidate)) }
                                            catch (e: CancellationException) { throw e }
                                            catch (_: Exception) { feedback = "The downloaded file couldn't be verified. Check for updates and download it again." }
                                            finally { installing = false }
                                        }
                                    }, enabled = !installing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text(if (installing) "Verifying update" else "Install update")
                                    }
                                }
                                download != null -> {
                                    val percent = download.progress.getInt("percent", 0).coerceIn(0, 100)
                                    Text(if (download.state == WorkInfo.State.RUNNING) { if (percent == 100) "Verifying update" else "Downloading · $percent%" }
                                        else if (AppUpdates.AUTOMATIC in download.tags) "Waiting for Wi-Fi or for Android to start the download"
                                        else "Waiting for a connection or for Android to start the download", color = Lime)
                                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                                    if (download.state == WorkInfo.State.ENQUEUED && AppUpdates.AUTOMATIC in download.tags) {
                                        Text("Download now can use mobile data.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        FilledTonalButton(onClick = { AppUpdates.download(activity, candidate) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download now") }
                                    }
                                    OutlinedButton(onClick = { AppUpdates.cancelDownload(activity) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel download") }
                                }
                                else -> {
                                    Text("A new version is available. Download now uses your current connection.", color = Muted)
                                    Button(onClick = { feedback = null; AppUpdates.download(activity, candidate) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download update") }
                                }
                            }
                            if (candidate.notes.isNotBlank()) {
                                TextButton(onClick = { showNotes = !showNotes }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showNotes) "Hide what's new" else "What's new") }
                                if (showNotes) Text(candidate.notes, color = Muted)
                            }
                        }
                        (feedback ?: state.error)?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse(candidate?.releaseUrl ?: "${UpdateRelease.REPOSITORY}/releases"))) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("View GitHub releases") }
                    }
                }
            }
            item { Text("Install updates over Bite. Uninstalling or clearing app storage removes local data.", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
