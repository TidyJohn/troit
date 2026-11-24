package app.tunes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Library summaries and invitations never need to load every playlist's songs. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CollaborationLibrarySection(state: CollaborativePlaylists, query: String, onOpen: Open, showPlaylists: Boolean = true) {
    val visible = remember(state.summaries, query) {
        state.summaries.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("WITH YOUR FRIENDS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text("Collaborative playlists", style = MaterialTheme.typography.titleLarge)
        if (state.userId == null) {
            Text("Connect your Troit account to make playlists with accepted friends.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CollaborationError(state)
            state.invites.forEach { invitation ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(invitation.title, style = MaterialTheme.typography.titleSmall)
                        Text("You're invited to edit this playlist.", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { state.respond(invitation.id, true, onAccepted = onOpen) }, enabled = !state.busy) { Text("Accept invitation") }
                            TextButton(onClick = { state.respond(invitation.id, false) }, enabled = !state.busy) { Text("Decline") }
                        }
                    }
                }
            }
            if (state.loading && state.summaries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loading && !state.setupRequired && visible.isEmpty()) Text(
                if (query.isBlank()) "Start a collaboration or accept an invitation to share songs with friends." else "No collaborative playlists match your search.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showPlaylists) visible.forEach { playlist ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open collaborative playlist ${playlist.title}") { onOpen(playlist) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Artwork(playlist.artwork, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.songCount} songs · ${playlist.memberCount} collaborators", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (playlist.isOwner) "Owner" else "Editor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollaborativeCollectionTile(playlist: CollaborativePlaylist, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Open collaborative playlist ${playlist.title}", onClick = onOpen).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Artwork(playlist.artwork, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp))
            Text(playlist.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${playlist.songCount} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (playlist.isOwner) "Collaborative · Owner" else "Collaborative · Editor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun CollaborationError(state: CollaborativePlaylists) {
    val error = state.error ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = state::retry, enabled = !state.busy) { Text("Try again") }
        }
    }
}

/** A new playlist and a promoted personal playlist use the same accepted-friend selection. */
@Composable
internal fun MakeCollaborativeDialog(
    state: CollaborativePlaylists,
    social: SocialState,
    source: SavedPlaylist? = null,
    onCreated: (CollaborativePlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(source?.id) { mutableStateOf(source?.title.orEmpty()) }
    var selected by remember(source?.id, social.userId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(social, social.userId) { social.refresh(forceSnapshot = true, waitForRead = true) }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(if (source == null) "New collaborative playlist" else "Make collaborative") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.userId == null -> Text("Connect your Troit account from Library to make playlists with friends.")
                    else -> {
                        Text(if (source == null) "Invite accepted friends to add and remove songs together. You manage the playlist and collaborators."
                            else "Move this playlist into your shared collection. Invited friends can add and remove songs after accepting.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(title, { title = it.take(100) }, label = { Text("Playlist name") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        FriendPicker(social, selected, { selected = it }, enabled = !state.busy, limit = 19)
                        Text("${selected.size} invited · up to 20 collaborators including you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CollaborationError(state)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { state.create(title.trim(), selected.toList(), source, onCreated) },
                enabled = state.userId != null && !state.busy && !state.setupRequired && title.isNotBlank()) {
                Text(if (state.busy) "Creating…" else if (source == null) "Create collaboration" else "Make collaborative")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
    )
}

@Composable
private fun FriendPicker(social: SocialState, selected: Set<String>, onSelect: (Set<String>) -> Unit, enabled: Boolean, limit: Int, excluded: Set<String> = emptySet()) {
    var query by remember { mutableStateOf("") }
    val friends = social.friends
    val candidates = remember(friends, query, excluded) { friends.filter { it.peer.id !in excluded &&
        (it.peer.displayName.contains(query.trim(), true) || it.peer.handle.contains(query.trim().removePrefix("@"), true)) } }
    LaunchedEffect(friends, excluded) {
        val available = friends.mapTo(hashSetOf()) { it.peer.id } - excluded
        val retained = selected.intersect(available)
        if (retained != selected) onSelect(retained)
    }
    Text("Invite friends", style = MaterialTheme.typography.titleSmall)
    if (social.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (social.error != null) {
        Text(social.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = social::retry, enabled = enabled) { Text("Reload friends") }
    }
    if (friends.isEmpty() && !social.loading) Text("Add and accept friends in Friends first. You can invite them later too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (friends.isNotEmpty()) {
        OutlinedTextField(query, { query = it.take(50) }, placeholder = { Text("Find a friend") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(candidates, key = { it.id }) { connection ->
                val peer = connection.peer
                val checked = peer.id in selected
                val selectable = enabled && (checked || selected.size < limit)
                Row(Modifier.fillMaxWidth().clickable(enabled = selectable) { onSelect(if (checked) selected - peer.id else selected + peer.id) }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { onSelect(if (it) selected + peer.id else selected - peer.id) }, enabled = selectable)
                    Column(Modifier.weight(1f)) {
                        Text(peer.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${peer.handle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (candidates.isEmpty()) Text("No available friends match your search.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CollaborativePlaylistScreen(state: CollaborativePlaylists, playlistId: String, onPlay: Play, onBack: () -> Unit) {
    LaunchedEffect(state, playlistId, state.userId) { state.open(playlistId) }
    DisposableEffect(state, playlistId) { onDispose { if (state.selectedId == playlistId) state.close() } }
    val playlist = state.selected?.takeIf { it.id == playlistId }
    var query by rememberSaveable(playlistId) { mutableStateOf("") }
    var manage by remember(playlistId) { mutableStateOf(false) }
    var renaming by remember(playlistId) { mutableStateOf(false) }
    var confirm by remember(playlistId) { mutableStateOf<String?>(null) }
    val preferences = LocalListeningPreferences.current
    val songs = remember(playlist?.songs, preferences) { playlist?.songs.orEmpty().filter(preferences::allows) }
    val matches = remember(songs, query) { searchPlaylist(songs, query) }
    val matchingSongs = remember(matches) { matches.map { it.song } }
    val keys = remember(songs) { stableOccurrenceKeys(songs, Item::listIdentity) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Back") }
        Box(Modifier.padding(horizontal = 20.dp)) { CollaborationError(state) }
        when {
            state.userId == null -> Text("Connect your Troit account to open this collaboration.", Modifier.padding(24.dp))
            playlist == null && state.detailLoading -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            playlist == null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This collaborative playlist is unavailable.")
                Text("It may have been deleted, or your access may have changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = state::retry) { Text("Reload playlist") }
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                item(key = "shared-header", contentType = "header") {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("COLLABORATIVE PLAYLIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Artwork(playlist.artwork, Modifier.size(80.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.songCount} songs · ${playlist.memberCount} collaborators", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onPlay(matchingSongs, 0) }, enabled = matchingSongs.isNotEmpty()) { Text("Play") }
                            IconButton(onClick = { onPlay(matchingSongs.shuffled(), 0) }, enabled = matchingSongs.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Shuffle collaborative playlist" }) { TuneIcon("shuffle", Modifier.size(21.dp)) }
                            TextButton(onClick = { manage = true }) { Text("Collaborators") }
                        }
                        Text("Use the + control on any song to add it to this collaboration. Members can add or remove songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (playlist.isOwner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { renaming = true }, enabled = !state.busy) { Text("Rename") }
                            TextButton(onClick = { confirm = "delete" }, enabled = !state.busy) { Text("Delete playlist") }
                        } else TextButton(onClick = { confirm = "leave" }, enabled = !state.busy) { Text("Leave collaboration") }
                        OutlinedTextField(query, { query = it.take(200) }, placeholder = { Text("Search in this playlist") }, singleLine = true,
                            leadingIcon = { TuneIcon("search", Modifier.size(20.dp)) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear collaborative playlist search" }) { TuneIcon("close", Modifier.size(18.dp)) } }, modifier = Modifier.fillMaxWidth())
                        if (query.isNotBlank()) Text("${matches.size} of ${songs.size} songs", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (matches.isEmpty()) item(key = "shared-empty", contentType = "empty") {
                    Text(if (songs.isEmpty()) "Add the first song to start listening together." else "No songs match your search.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(matches, key = { keys[it.queueIndex] }, contentType = { "song" }) { match ->
                    ItemRow(match.song, trailing = { TextButton(onClick = { state.removeSong(playlist.id, match.song) }, enabled = !state.busy) { Text("Remove") } }) { onPlay(songs, match.queueIndex) }
                }
            }
        }
    }
    if (manage && playlist != null) CollaboratorsDialog(state, playlist) { manage = false }
    if (renaming && playlist != null) {
        var title by remember(playlist.id) { mutableStateOf(playlist.title) }
        val revision = remember(playlist.id) { playlist.revision }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename collaboration") },
            text = { OutlinedTextField(title, { title = it.take(100) }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { state.rename(playlist.id, title, revision); renaming = false }, enabled = title.isNotBlank() && title.trim() != playlist.title && !state.busy) { Text("Save name") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (confirm != null && playlist != null) AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(if (confirm == "delete") "Delete collaboration?" else "Leave collaboration?") },
        text = { Text(if (confirm == "delete") "This removes the shared playlist for every collaborator."
            else "This playlist will leave your shared collection. You will need another invitation to edit it again.") },
        confirmButton = { TextButton(onClick = {
            if (confirm == "delete") state.delete(playlist.id, onDeleted = onBack) else state.leave(playlist.id, onLeft = onBack)
            confirm = null
        }, enabled = !state.busy) { Text(if (confirm == "delete") "Delete playlist" else "Leave", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
}

@Composable
private fun CollaboratorsDialog(state: CollaborativePlaylists, playlist: CollaborativePlaylist, onDismiss: () -> Unit) {
    val social = LocalSocialState.current
    val members = remember(playlist.members) { playlist.members.filter { it.status in setOf("accepted", "pending") } }
    val invitedCount = members.count { it.status == "pending" }
    val availableSlots = (20 - members.size).coerceAtLeast(0)
    var inviting by remember(playlist.id) { mutableStateOf(false) }
    var selected by remember(playlist.id, state.userId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(inviting, social) { if (inviting) social?.refresh(forceSnapshot = true, waitForRead = true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Collaborators") }, text = {
        Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${playlist.memberCount} collaborators · $invitedCount invited · 20 places total", style = MaterialTheme.typography.labelMedium)
            members.forEach { member ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(member.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("@${member.handle} · ${if (member.role == "owner") "Owner" else if (member.status == "pending") "Invited" else "Editor"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (playlist.isOwner && member.id != playlist.ownerId) TextButton(onClick = { state.removeMember(playlist.id, member.id) }, enabled = !state.busy) { Text("Remove") }
                }
            }
            if (playlist.isOwner && social != null && availableSlots > 0) {
                TextButton(onClick = { inviting = !inviting }) { Text(if (inviting) "Hide friend picker" else "Invite friends") }
                if (inviting) FriendPicker(social, selected, { selected = it }, enabled = !state.busy,
                    limit = availableSlots, excluded = members.mapTo(hashSetOf()) { it.id })
            }
            CollaborationError(state)
        }
    }, confirmButton = {
        if (inviting && selected.isNotEmpty()) Button(onClick = { state.invite(playlist.id, selected.toList()); selected = emptySet(); inviting = false }, enabled = !state.busy && selected.size <= availableSlots) { Text("Send invitations") }
        else TextButton(onClick = onDismiss) { Text("Done") }
    }, dismissButton = { if (inviting && selected.isNotEmpty()) TextButton(onClick = onDismiss) { Text("Close") } })
}
