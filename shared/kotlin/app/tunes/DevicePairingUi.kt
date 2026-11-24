package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Short-code TV sign-in: no password entry on the remote and no phone token sharing. */
@Composable
fun PairTvDialog(state: DevicePairingState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        state.begin()
        while (!state.completed) { delay(2_500); state.poll() }
    }
    DisposableEffect(state) { onDispose { state.dismiss() } }
    AlertDialog(onDismissRequest = { state.dismiss(); onDismiss() },
        title = { Text("Pair with your phone") },
        text = {
            Column(Modifier.widthIn(max = 560.dp).heightIn(max = 310.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Open Troit on a phone or computer where you're already signed in. Choose Devices → Pair a TV.")
                state.code?.let { Text(it, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp) }
                Text(state.status)
                if (state.waiting) Text("Code expires in 10 minutes. Approve only the code shown on this TV.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state.completed) TvAction("Done", onClick = onDismiss)
            else TvAction(if (state.code == null) "Get a code" else "New code", enabled = !state.busy, onClick = {
                scope.launch { state.cancel(); state.begin() }
            })
        },
        dismissButton = { if (!state.completed) TvAction("Cancel", onClick = { scope.launch { state.cancel(); onDismiss() } }) },
    )
}

@Composable
fun ApproveTvDialog(sync: Sync, onDismiss: () -> Unit) {
    val state = remember(sync) { PairingApprovalState(sync) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    LaunchedEffect(sync.userId) { state.reset() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Pair a TV") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    sync.userId == null -> Text("Sign in on this device first, then enter the code shown on your TV.")
                    state.approved -> Text("Approved. Your TV will connect shortly.")
                    state.inspected != null -> {
                        val target = state.inspected!!
                        Text("Connect ${target.deviceName} to ${sync.email ?: "your Troit account"}?")
                        Text("Code: ${normalizePairingCode(code).take(4)}-${normalizePairingCode(code).drop(4)}", fontWeight = FontWeight.SemiBold)
                        Text("Approve only if this exact code is visible on a TV you are setting up.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("On your TV, choose Account → Pair with phone. Enter its eight-character code here.")
                        OutlinedTextField(code, { code = it.take(12); state.reset() }, label = { Text("TV code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), enabled = !state.busy)
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                state.approved -> TextButton(onClick = onDismiss) { Text("Done") }
                state.inspected != null -> Button(onClick = { scope.launch { state.approve() } }, enabled = !state.busy) { Text("Connect this TV") }
                else -> Button(onClick = { scope.launch { state.inspect(code) } }, enabled = sync.userId != null && !state.busy && validPairingCode(code)) { Text("Check TV code") }
            }
        },
        dismissButton = { if (!state.approved) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
