package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SleepTimerDialog(state: SleepTimerState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(state) { state.refresh(); while (true) { delay(1000); tick++ } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sleep timer") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val timer = state.timer
            Text("Pause music after a countdown or when the current song finishes.")
            if (timer != null) {
                val remaining = remember(tick, timer) { state.remainingMs() }
                Text(if (timer.kind == "end_of_track") "At the end of this song" else "${((remaining ?: 0) / 60_000)}:${(((remaining ?: 0) / 1000) % 60).toString().padStart(2, '0')} remaining",
                    style = MaterialTheme.typography.titleLarge)
                Text(if (timer.local) "On this device" else "On ${timer.deviceName} · follows connected playback", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { scope.launch { state.cancel() } }, enabled = !state.busy) { Text("Cancel timer") }
                HorizontalDivider()
            }
            listOf(15, 30, 60).forEach { minutes ->
                OutlinedButton(onClick = { scope.launch { state.setMinutes(minutes) } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("$minutes minutes") }
            }
            OutlinedButton(onClick = { scope.launch { state.setEndOfTrack() } }, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("End of this song") }
            Text("Minute timers follow playback between your devices. End-of-song timers follow that song and cancel if you skip to another song.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
