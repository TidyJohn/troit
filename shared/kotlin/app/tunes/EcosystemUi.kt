package app.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

val LocalEcosystem = staticCompositionLocalOf<EcosystemState?> { null }
val LocalOpenSharedQueue = staticCompositionLocalOf<() -> Unit> { {} }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EcosystemDeviceSection(sync: Sync, state: EcosystemState, television: Boolean) {
    val scope = rememberCoroutineScope()
    val openQueue = LocalOpenSharedQueue.current
    var renaming by remember { mutableStateOf<EcosystemDevice?>(null) }
    var pairing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Choose a playback device", style = MaterialTheme.typography.titleMedium)
        Text("Open Troit on the device you want to use.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.setupRequired -> Text("Device switching and shared queues need the Troit ecosystem server update.", color = MaterialTheme.colorScheme.error)
            state.devices.isEmpty() -> Text("No devices registered yet. Refresh after opening Troit on another device.")
        }
        state.devices.forEach { device ->
            DeviceRow(device, isHere = device.id == sync.deviceId,
                active = state.queue?.deviceId == device.id,
                canTransfer = state.queue != null && device.online && state.queue?.deviceId != device.id && !state.busy && !state.pending,
                busy = state.busy || state.pending, television = television,
                onTransfer = { scope.launch { state.transferTo(device) } }, onRename = { renaming = device })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EcosystemAction("Shared queue", television, enabled = state.queue != null, onClick = openQueue)
            EcosystemAction("Pair a TV", television, onClick = { pairing = true })
            val timer = LocalOpenSleepTimer.current
            val party = LocalOpenParty.current
            EcosystemAction("Sleep timer", television, onClick = timer)
            EcosystemAction("Party queue", television, onClick = party)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.busy || state.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
        EcosystemAction("Refresh devices", television, enabled = !state.busy) { scope.launch { state.refresh() } }
    }
    renaming?.let { device ->
        var name by remember(device) { mutableStateOf(device.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Name this device") },
            text = { OutlinedTextField(name, { name = it.take(64) }, label = { Text("Device name") }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank() && !state.busy, onClick = {
                scope.launch { state.renameDevice(device, name.trim()); renaming = null }
            }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    if (pairing) ApproveTvDialog(sync) { pairing = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceRow(device: EcosystemDevice, isHere: Boolean, active: Boolean, canTransfer: Boolean,
    busy: Boolean, television: Boolean, onTransfer: () -> Unit, onRename: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TuneIcon("devices", Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(if (device.online) "Online" else "Offline", if (isHere) "This device" else null,
                        if (active) "Current player" else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EcosystemAction(if (active) "Current player" else "Play on this device", television, enabled = canTransfer && device.isOnline(),
                    modifier = Modifier.semantics { contentDescription = "Play on ${device.name}" }, onClick = onTransfer)
                EcosystemAction("Rename", television, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "Rename ${device.name}" }, onClick = onRename)
            }
        }
    }
}

@Composable
internal fun EcosystemAction(label: String, television: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (television) TvAction(label, modifier, enabled = enabled, onClick = onClick)
    else TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
internal fun SongQueueMenu(song: Song) {
    val state = LocalEcosystem.current
    val save = LocalSave.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    if (sending) SendToFriendDialog(song) { sending = false }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "More options for ${song.title}" }) {
            TuneIcon("more", Modifier.size(20.dp))
        }
        // Grouped by what they act on: the queue, your library, a friend.
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Play next") }, enabled = state?.queue != null && !state.busy && !state.pending, onClick = {
                expanded = false; scope.launch { state?.editQueue("add_next", songs = listOf(song)) }
            })
            DropdownMenuItem(text = { Text("Add to queue") }, enabled = state?.queue != null && !state.busy && !state.pending, onClick = {
                expanded = false; scope.launch { state?.editQueue("add_last", songs = listOf(song)) }
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Save to playlist") }, onClick = { expanded = false; save(SaveRequest(listOf(song), "")) })
            val downloading = Downloads.progress[song.url]
            DropdownMenuItem(text = { Text(when {
                downloading != null -> "Cancel download · ${(downloading * 100).toInt()}%"
                Downloads.isDownloaded(song.url) -> "Remove download"
                else -> "Download"
            }) }, onClick = {
                expanded = false
                if (downloading != null || Downloads.isDownloaded(song.url)) Downloads.remove(listOf(song.url)) else Downloads.download(listOf(song))
            })
            if (LocalSocialState.current != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Send to a friend") }, onClick = { expanded = false; sending = true })
            }
        }
    }
}

@Composable
fun SharedQueueDialog(state: EcosystemState, television: Boolean = false, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val queue = state.queue
    val busy = state.busy || state.pending
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(state) { state.refresh() }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(if (television) 20.dp else 12.dp).widthIn(max = 820.dp).fillMaxWidth()
            .heightIn(max = if (television) 500.dp else 720.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(if (television) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shared queue", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    EcosystemAction("Done", television, onClick = onDismiss)
                }
                Text(if (queue != null) "${queue.songs.size} songs · ${queue.deviceName}" else "Start a song to create a shared queue.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Changes apply to the current player. The song playing keeps its position.", style = MaterialTheme.typography.bodySmall)
                if (queue != null) {
                    EcosystemAction("Add songs", television, enabled = !busy, onClick = { adding = true })
                    SharedQueueList(queue.songs, queue.index, busy, television,
                        onRemove = { index -> scope.launch { state.editQueue("remove", fromIndex = index) } },
                        onMove = { from, to -> scope.launch { state.editQueue("move", fromIndex = from, toIndex = to) } },
                        modifier = Modifier.weight(1f, fill = false))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (adding) AddQueueSongsDialog(state, television) { adding = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharedQueueList(songs: List<Song>, currentIndex: Int, busy: Boolean, television: Boolean,
    onRemove: (Int) -> Unit, onMove: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val keys = remember(songs) { stableOccurrenceKeys(songs, Item::listIdentity) }
    LazyColumn(modifier.fillMaxWidth().heightIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(songs, key = { i, _ -> keys[i] }) { index, song ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Artwork(song.artwork, Modifier.size(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (index == currentIndex) "Now playing · ${song.artist}" else song.artist,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (index == currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index > currentIndex) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EcosystemAction("Move up", television, enabled = !busy && index > currentIndex + 1,
                        modifier = Modifier.semantics { contentDescription = "Move queue item ${index + 1} up" }) { onMove(index, index - 1) }
                    EcosystemAction("Move down", television, enabled = !busy && index < songs.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move queue item ${index + 1} down" }) { onMove(index, index + 1) }
                    EcosystemAction("Remove", television, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Remove queue item ${index + 1}" }) { onRemove(index) }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AddQueueSongsDialog(state: EcosystemState, television: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val search = remember { SearchState(scope) }
    val preferences = LocalListeningPreferences.current
    LaunchedEffect(preferences) { search.updatePreferences(preferences) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add songs to the queue") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = if (television) 300.dp else 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search.query, search::updateQuery, label = { Text("Search songs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (search.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            search.result?.exceptionOrNull()?.let { Text("Search couldn't connect. Try again.", color = MaterialTheme.colorScheme.error) }
            val songs = search.result?.getOrNull().orEmpty().filterIsInstance<Song>()
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(songs, key = { i, song -> "$i:${song.url}" }) { _, song ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EcosystemAction("Play next", television, enabled = !state.busy && !state.pending) {
                                scope.launch { state.editQueue("add_next", songs = listOf(song)) }
                            }
                            EcosystemAction("Add last", television, enabled = !state.busy && !state.pending) {
                                scope.launch { state.editQueue("add_last", songs = listOf(song)) }
                            }
                        }
                    }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { EcosystemAction("Done", television, onClick = onDismiss) })
}
