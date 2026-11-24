package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyDialog(state: PartyState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Listening with friends") }
    var code by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val openDevices = LocalOpenDevices.current
    val openQueue = LocalOpenSharedQueue.current
    LaunchedEffect(state) { state.refresh() }
    val room = state.room
    AlertDialog(onDismissRequest = onDismiss, title = { Text(room?.name ?: "Party queue", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.widthIn(max = 640.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (room == null) {
                Text("Listen together on the host's player. Accepted Troit friends can join with a room code and add songs.")
                if (!state.signedIn) Text("Sign in from Devices first to host a room or join friends.", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Room name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { state.create(name) } }, enabled = state.signedIn && !state.busy && name.isNotBlank()) { Text("Host a room") }
                HorizontalDivider()
                val invite = state.invitation
                if (invite == null) {
                    OutlinedTextField(code, { code = it.uppercase(java.util.Locale.ROOT).take(8) }, label = { Text("Friend's room code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { scope.launch { state.inspect(code) } }, enabled = state.signedIn && !state.busy && code.length == 8) { Text("Check room") }
                } else {
                    Text("Join ${invite.hostName} in ${invite.name}?")
                    Button(onClick = { scope.launch { state.join() } }, enabled = !state.busy) { Text("Join this room") }
                }
            } else {
                Text("Room code · ${room.code}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Hosted by ${room.hostName}. Share this code with accepted Troit friends.")
                Text(if (room.isHost) "You control playback and the queue. Guests can add songs at the end."
                    else "The host controls playback. Your song requests are added to their queue.", style = MaterialTheme.typography.bodySmall)
                if (room.isHost) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onDismiss(); openDevices() }) { Text("Playback controls") }
                        TextButton(onClick = { onDismiss(); openQueue() }) { Text("Edit queue") }
                    }
                }
                val queue = room.queue
                if (queue == null || !queue.isFresh()) Text("The host player is offline or preparing a queue. Keep Troit open there.")
                else {
                    Text("${queue.songs.size} songs · ${queue.deviceName}", style = MaterialTheme.typography.labelLarge)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                        itemsIndexed(queue.songs.drop(queue.index.coerceAtLeast(0)), key = { i, song -> "$i:${song.url}" }) { i, song ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(if (i == 0) "Now · ${song.title}" else song.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium)
                                Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(onClick = { adding = true }, enabled = !state.busy && !state.pending) { Text("Add a song") }
                }
                Text("Participants · ${room.members.size}/20", style = MaterialTheme.typography.titleSmall)
                room.members.forEach { member ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(member.name + if (member.host) " · Host" else "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (room.isHost && !member.host) TextButton(onClick = { scope.launch { state.removeMember(member.id) } }, enabled = !state.busy) { Text("Remove") }
                    }
                }
                TextButton(onClick = { scope.launch { if (room.isHost) state.end() else state.leave() } }, enabled = !state.busy) {
                    Text(if (room.isHost) "End room" else "Leave room", color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.busy || state.pending) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    if (adding) PartySearchDialog(state) { adding = false }
}

@Composable
private fun PartySearchDialog(state: PartyState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val search = remember { SearchState(scope) }
    val preferences = LocalListeningPreferences.current
    LaunchedEffect(preferences) { search.updatePreferences(preferences) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to the party queue") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(search.query, search::updateQuery, label = { Text("Search songs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (search.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            search.result?.exceptionOrNull()?.let { Text("Search couldn't connect. Try again.", color = MaterialTheme.colorScheme.error) }
            val songs = search.result?.getOrNull().orEmpty().filterIsInstance<Song>()
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(songs, key = { i, song -> "$i:${song.url}" }) { _, song ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { scope.launch { state.addSongs(listOf(song)) } }, enabled = !state.busy && !state.pending) { Text("Add to host queue") }
                    }
                }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
