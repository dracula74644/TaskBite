package com.dracula.taskbite

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun TestingScreen(onClose: () -> Unit, onSimulate: () -> Unit, onTest: () -> Unit,
    onSettings: () -> Unit, onBattery: () -> Unit, onAlarms: () -> Unit, snackbar: SnackbarHostState) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = { TestToolsHeader("Test & troubleshoot", "Back to settings", onClose) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("testing_tools"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel, contentColor = Cream), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow("PRACTICE WITH SAMPLE TASKS", Lime)
                        Text("Explore a day in minutes", style = MaterialTheme.typography.titleLarge)
                        Text("Try task actions, fast-forward time, and hear matching reactions. Your real tasks stay untouched.", color = Muted)
                        FilledTonalButton(onClick = onSimulate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.FastForward, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("Simulate a day")
                        }
                        Text("Sample mode sends no real notifications.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel, contentColor = Cream), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow("TEST ON THIS PHONE", Lime)
                        Text("Check a real notification", style = MaterialTheme.typography.titleLarge)
                        Text("Send a test, then press Home or lock your phone. Look for Bite after about 10 seconds.", color = Muted)
                        ReminderDiagnostics(onTest, onSettings, onBattery, onAlarms, onVoiceControls = onClose)
                    }
                }
            }
        }
    }
}

@Composable internal fun TestToolsHeader(title: String, backLabel: String, onBack: () -> Unit) {
    Surface(color = Ink) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, backLabel)
            }
            Text(title, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}
