package com.dracula.taskbite

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun NudgeGapDialog(minutes: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(minutes.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in AwayPolicy.MIN_GAP_MINUTES..AwayPolicy.MAX_GAP_MINUTES
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(240).dp
    val width = (LocalConfiguration.current.screenWidthDp - 48).coerceIn(240, 360).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(width), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nudge gap", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose a preset or enter your own gap. Quiet hours still apply.")
                    listOf(15, 30, 60, 120).chunked(2).forEach { choices ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            choices.forEach { preset -> FilterChip(selected = value == preset,
                                onClick = { text = preset.toString() }, label = { Text("$preset min") },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) }
                        }
                    }
                    OutlinedTextField(value = text, onValueChange = { input ->
                        if (input.length <= 5 && input.all { it in '0'..'9' }) text = input
                    }, label = { Text("Minutes") }, singleLine = true, isError = !valid,
                        supportingText = { Text("15–1,440 minutes (up to 24 hours)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { value?.takeIf { valid }?.let(onSave) }, enabled = valid) { Text("Save gap") }
                }
            }
        }
    }
}
