package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val CollectionPlaceholder = Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF111111)))

/** The collection stays foremost; import and account settings are available when needed. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun CollectionScreen(library: Library, import: ImportState, sync: Sync, onOpen: Open) {
    val collaborations = LocalCollaborativePlaylists.current
    val social = LocalSocialState.current
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedPlaylist?>(null) }
    var renaming by remember { mutableStateOf<SavedPlaylist?>(null) }
    var collaborating by remember { mutableStateOf(false) }
    var collaborationSource by remember { mutableStateOf<SavedPlaylist?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val playlists by remember(library) { derivedStateOf { library.all.filterNot { it.deleted } } }
    val normalizedQuery = remember(query) { query.trim() }
    val visible = remember(playlists, normalizedQuery) {
        if (normalizedQuery.isEmpty()) playlists else playlists.filter { it.title.contains(normalizedQuery, ignoreCase = true) }
    }
    val visibleCollaborations = remember(collaborations?.summaries, normalizedQuery) {
        collaborations?.summaries.orEmpty().filter { it.title.contains(normalizedQuery, ignoreCase = true) }
    }
    val songCount = remember(playlists) { playlists.sumOf { it.songs.size } }
    val gridState = rememberLazyGridState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (maxWidth < 480.dp) 140.dp else 180.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "collection-header", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (playlists.isNotEmpty()) Text(
                    "${playlists.size} playlists · $songCount saved songs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(onClick = { creating = true }) {
                        TuneIcon("plus", Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New playlist")
                    }
                    // One primary action, the two places people come here for, and the occasional rest under More.
                    val listeningLibrary = LocalOpenListeningLibrary.current
                    OutlinedButton(onClick = listeningLibrary) { Text("Your listening") }
                    val downloads = LocalOpenDownloads.current
                    OutlinedButton(onClick = downloads) {
                        Text(if (Downloads.entries.isEmpty()) "Downloads" else "Downloads · ${Downloads.entries.size}")
                    }
                    Box {
                        var more by remember { mutableStateOf(false) }
                        TextButton(onClick = { more = true }) { Text("More") }
                        DropdownMenu(more, onDismissRequest = { more = false }) {
                            if (collaborations != null && social != null) DropdownMenuItem(text = { Text("New collaboration") }, onClick = {
                                more = false
                                if (sync.userId == null) account = true
                                else { collaborationSource = null; collaborating = true }
                            })
                            DropdownMenuItem(text = { Text("Import from Spotify") }, onClick = { more = false; importing = true })
                            DropdownMenuItem(text = { Text("Account & sync") },
                                onClick = { more = false; account = true })
                        }
                    }
                }
            }
        }
        if (import.running || import.report.isNotBlank()) {
            item(key = "import-status", contentType = "status", span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (import.running) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Bringing your music over", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { importing = true }) { Text("View import") }
                            }
                            ImportProgress(import)
                            Text("Keep exploring while we find your songs.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(import.report, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { import.report = "" }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
        if (playlists.isNotEmpty() || collaborations?.summaries?.isNotEmpty() == true) {
            item(key = "collection-search", contentType = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Find a playlist") },
                    leadingIcon = { TuneIcon("search", Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (collaborations != null) {
            item(key = "collaborations-heading", contentType = "collaborations", span = { GridItemSpan(maxLineSpan) }) {
                CollaborationLibrarySection(collaborations, normalizedQuery, onOpen, showPlaylists = false)
            }
            items(visibleCollaborations, key = { "collaboration:${it.id}" }, contentType = { "collaborative-playlist" }) { playlist ->
                CollaborativeCollectionTile(playlist) { onOpen(playlist) }
            }
        }
        if (visible.isEmpty() && visibleCollaborations.isEmpty()) {
            item(key = "collection-empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .12f), shape = RoundedCornerShape(24.dp)) {
                        TuneIcon("library", Modifier.padding(24.dp).size(36.dp), MaterialTheme.colorScheme.primary)
                    }
                    Text(if (normalizedQuery.isEmpty()) "Create your first playlist" else "No playlists found",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (normalizedQuery.isEmpty()) "Choose New playlist, then add songs from their options menu."
                        else "Try another name or clear your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (normalizedQuery.isEmpty()) {
                        TextButton(onClick = { importing = true }) { Text("Bring your playlists from Spotify") }
                    } else {
                        TextButton(onClick = { query = "" }) { Text("Clear search") }
                    }
                }
            }
        }
        if (collaborations != null && visible.isNotEmpty()) item(key = "personal-playlists-heading", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
            Text("Your personal playlists", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        }
        items(visible, key = { "playlist:${it.id}" }, contentType = { "playlist" }) { playlist ->
            CollectionTile(playlist, onOpen = { onOpen(playlist) }, onRename = { renaming = playlist }, onDelete = { deleting = playlist },
                onCollaborate = if (collaborations != null && social != null) ({
                    if (sync.userId == null) account = true
                    else { collaborationSource = playlist; collaborating = true }
                }) else null)
        }
    }
    }

    if (collaborating && collaborations != null && social != null) MakeCollaborativeDialog(collaborations, social, collaborationSource,
        onCreated = { collaborating = false; collaborationSource = null; onOpen(it) },
        onDismiss = { collaborating = false; collaborationSource = null })

    if (creating) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Make it yours") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Give your new playlist a name. You can add songs from anywhere in Troit.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = title, onValueChange = { title = it.take(100) },
                        label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    val playlist = SavedPlaylist(title.trim(), emptyList())
                    library.add(playlist)
                    creating = false
                    onOpen(playlist)
                }, enabled = title.isNotBlank()) { Text("Create playlist") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
    renaming?.let { playlist ->
        RenamePlaylistDialog(playlist, library) { renaming = null }
    }
    deleting?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${playlist.title}\" will be removed from your library and synced devices.") },
            confirmButton = { TextButton(onClick = { library.remove(playlist); deleting = null }) {
                Text("Delete playlist", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep playlist") } },
        )
    }
    if (importing) ImportCollectionDialog(import) { importing = false }
    if (account) {
        AlertDialog(
            onDismissRequest = { account = false },
            title = { Text("Your account") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { AccountCard(sync) }
            },
            confirmButton = { TextButton(onClick = { account = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun CollectionTile(playlist: SavedPlaylist, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onCollaborate: (() -> Unit)? = null) {
    var menu by remember(playlist.id) { mutableStateOf(false) }
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playlist.artwork != null) {
            Artwork(playlist.artwork, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(12.dp))
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(CollectionPlaceholder),
                contentAlignment = Alignment.Center,
            ) { TuneIcon("music", Modifier.size(56.dp), Color.White.copy(alpha = .8f)) }
        }
        Text(playlist.title, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { menu = true }, contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.semantics { contentDescription = "Actions for ${playlist.title}" }) {
                    Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onCollaborate != null) DropdownMenuItem(text = { Text("Make collaborative") }, onClick = { menu = false; onCollaborate() })
                    DropdownMenuItem(text = { Text("Rename playlist") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun RenamePlaylistDialog(playlist: SavedPlaylist, library: Library, onDismiss: () -> Unit) {
    var name by remember(playlist.id) { mutableStateOf(playlist.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { library.rename(playlist.id, name); onDismiss() },
                enabled = name.isNotBlank() && name.trim() != playlist.title) { Text("Save name") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportCollectionDialog(import: ImportState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your music comes with you") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Import up to 1,000 songs from Spotify playlists, albums, or song links.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LocalSpotifyConnection.current?.let { SpotifySetupCard(it, enabled = !import.running) }
                OutlinedTextField(value = import.links, onValueChange = { import.links = it },
                    label = { Text("Spotify links") }, placeholder = { Text("https://open.spotify.com/playlist/…") },
                    minLines = 2, maxLines = 4, enabled = !import.running, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = import.name, onValueChange = { import.name = it.take(100) },
                    label = { Text("Save as playlist") }, placeholder = { Text("Spotify import") },
                    singleLine = true, enabled = !import.running, modifier = Modifier.fillMaxWidth())
                if (import.running) ImportProgress(import)
                if (import.running) TextButton(onClick = import::cancel) { Text("Pause import") }
                if (import.canResume && import.foundCount > 0) TextButton(onClick = import::saveFound) { Text("Save ${import.foundCount} matched songs") }
                if (import.report.isNotEmpty()) Text(import.report, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = import::start, enabled = !import.running && import.links.isNotBlank()) {
                Text(if (import.running) "Importing…" else if (import.canResume) "Resume import" else "Import music")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (import.running) "Keep browsing" else "Done") } },
    )
}

@Composable
internal fun SpotifySetupCard(connection: SpotifyConnection, enabled: Boolean = true) {
    val account = connection.account
    val uriHandler = LocalUriHandler.current
    var clientId by rememberSaveable(account.clientId) { mutableStateOf(account.clientId) }
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (account.connected) {
                Text("Spotify connected", style = MaterialTheme.typography.titleSmall)
                account.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Full playlist import is ready for playlists your account can access through Spotify's API.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = connection::disconnect, enabled = enabled && !connection.connecting && !account.busy) { Text("Disconnect Spotify") }
            } else if (!setupOpen && !connection.connecting) {
                Text("Full playlist import", style = MaterialTheme.typography.titleSmall)
                Text("Connect Spotify to import beyond the public preview, up to 1,000 songs.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { setupOpen = true }, enabled = enabled) { Text("Set up Spotify") }
            } else {
                Text("Connect Spotify for full playlists", style = MaterialTheme.typography.titleSmall)
                Text("Create a Spotify developer app, register the redirect URI below, and enter its Client ID. Then sign in to Spotify in your browser.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uriHandler.openUri("https://developer.spotify.com/dashboard") }, enabled = enabled && !connection.connecting) { Text("Open developer dashboard") }
                Text("Redirect URI", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(SpotifyOAuthLoopback.REDIRECT_URI, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(value = clientId, onValueChange = { clientId = it.trim().take(64) },
                    label = { Text("Spotify Client ID") }, singleLine = true,
                    supportingText = { Text("Use the Client ID. No client secret is required.") },
                    enabled = enabled && !connection.connecting && !account.busy,
                    modifier = Modifier.fillMaxWidth())
                Text("Connect the account that owns the playlist or is a collaborator. Spotify developer-app access rules still apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connection.connecting) {
                    Text("Waiting for Spotify in your browser…", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = connection::cancel) { Text("Cancel connection") }
                } else Button(onClick = { connection.connect(clientId, uriHandler::openUri) }, enabled = enabled && clientId.isNotBlank() && !account.busy) { Text("Connect Spotify") }
                Text("Without connecting, links use Spotify's public preview and may contain fewer songs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!connection.connecting) TextButton(onClick = { setupOpen = false }) { Text("Hide setup") }
            }
            val status = connection.error ?: account.status.takeIf { it.isNotBlank() }
            if (status != null) Text(status, style = MaterialTheme.typography.bodySmall,
                color = if (connection.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportProgress(import: ImportState) {
    if (import.total > 0 && import.done > 0) {
        LinearProgressIndicator(progress = { (import.done.toFloat() / import.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
        Text("Finding song ${import.done} of ${import.total}", style = MaterialTheme.typography.bodySmall)
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("Reading your Spotify links…", style = MaterialTheme.typography.bodySmall)
    }
}
