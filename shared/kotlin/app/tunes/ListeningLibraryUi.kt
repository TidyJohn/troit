package app.tunes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

val LocalListeningLibrary = staticCompositionLocalOf<ListeningLibraryState?> { null }

/** One tap always changes the local heart immediately; a failed sync retains a durable operation. */
@Composable
fun LikeButton(song: Song, television: Boolean = false, modifier: Modifier = Modifier) {
    val state = LocalListeningLibrary.current ?: return
    val liked = state.isLiked(song)
    val label = if (liked) "Unlike ${song.title}" else "Like ${song.title}"
    if (television) {
        TvAction(if (liked) "Liked" else "Like song", modifier.semantics { contentDescription = label },
            selected = liked, onClick = { state.toggleLike(song) })
    } else {
        val tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = { state.toggleLike(song) }, modifier = modifier.semantics { contentDescription = label }) {
            Canvas(Modifier.size(23.dp)) {
                scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    val heart = Path().apply {
                        moveTo(12f, 21f)
                        cubicTo(9f, 18f, 2.5f, 13.5f, 2.5f, 8f)
                        cubicTo(2.5f, 2.5f, 9f, 1f, 12f, 6f)
                        cubicTo(15f, 1f, 21.5f, 2.5f, 21.5f, 8f)
                        cubicTo(21.5f, 13.5f, 15f, 18f, 12f, 21f)
                        close()
                    }
                    if (liked) drawPath(heart, tint) else drawPath(heart, tint, style = Stroke(1.7f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListeningLibraryScreen(onPlay: Play, television: Boolean = false) {
    val state = LocalListeningLibrary.current
    if (state == null) { Text("Listening library is getting ready."); return }
    var view by remember { mutableIntStateOf(0) } // liked, recently played, stats
    val showHistory = view == 1
    val scope = rememberCoroutineScope()
    val preferences = LocalListeningPreferences.current
    val liked = state.likedSongs
    val entries = state.history
    val songs = if (showHistory) entries.map { it.song } else liked
    Column(Modifier.fillMaxSize().padding(if (television) 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.signedIn) Text("Saved on this device. Sign in to share liked songs and history across your devices.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Liked songs (${liked.size})", "Recently played", "Stats").forEachIndexed { index, label ->
                if (television) TvAction(label, selected = view == index, onClick = { view = index })
                else FilterChip(view == index, onClick = { view = index }, label = { Text(label) })
            }
        }
        if (state.setupRequired) Text("Account sync needs the listening database update. Likes and history are saved on this device.", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.pending) Text("Changes saved on this device. Waiting to sync.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (view == 2) ListeningStatsView(entries, television, Modifier.weight(1f), onPlay)
        else if (songs.isEmpty()) {
            Text(if (showHistory) "Songs appear here after you listen for a few seconds." else "Tap a song's heart to keep it here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val playable = remember(songs, preferences) { songs.filter(preferences::allows) }
            if (!showHistory) {
                if (television) TvAction("Play liked songs", enabled = playable.isNotEmpty(), onClick = { onPlay(playable, 0) })
                else Button(enabled = playable.isNotEmpty(), onClick = { onPlay(playable, 0) }) { Text("Play liked songs") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (television) 10.dp else 2.dp)) {
                itemsIndexed(songs, key = { index, song -> if (showHistory) entries[index].id else song.url }) { index, song ->
                    val allowed = preferences.allows(song)
                    if (television) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(song.artwork, Modifier.size(52.dp))
                            TvAction(song.title, Modifier.weight(1f), enabled = allowed, onClick = { onPlay(songs, index) })
                            LikeButton(song, television = true)
                        }
                        Text(if (showHistory) "${song.artist} · ${historyTime(entries[index].playedAt)} · ${entries[index].deviceName}" else song.artist,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column {
                            ItemRow(song, trailing = { LikeButton(song) }) { if (allowed) onPlay(songs, index) }
                            if (showHistory) Text("${historyTime(entries[index].playedAt)} · ${entries[index].deviceName}",
                                Modifier.padding(start = 72.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        EcosystemAction("Refresh listening library", television, enabled = !state.busy) {
            scope.launch { state.refresh() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListeningStatsView(entries: List<ListeningHistoryEntry>, television: Boolean, modifier: Modifier, onPlay: Play) {
    var month by remember { mutableStateOf(true) }
    val preferences = LocalListeningPreferences.current
    val stats = remember(entries, month) {
        listeningStats(entries, if (month) System.currentTimeMillis() - 30L * 86_400_000 else 0)
    }
    val topSongs = remember(stats, preferences) { stats.songs.map { it.first }.filter(preferences::allows) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "Last 30 days", false to "All recorded").forEach { (choice, label) ->
                if (television) TvAction(label, selected = month == choice, onClick = { month = choice })
                else FilterChip(month == choice, onClick = { month = choice }, label = { Text(label) })
            }
        }
        if (stats.plays == 0) Text("Nothing played in this period yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (television) 10.dp else 2.dp)) {
            item { Text("${stats.plays} plays · Top songs", style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(stats.songs, key = { _, row -> row.first.url }) { index, (song, count) ->
                val label = "${index + 1}. ${song.title}"
                val position = topSongs.indexOf(song)
                if (television) TvAction("$label · $count plays", Modifier.fillMaxWidth(), enabled = position >= 0, onClick = { onPlay(topSongs, position) })
                else ItemRow(song.copy(title = label), trailing = { Text("$count plays", style = MaterialTheme.typography.labelMedium) }) {
                    if (position >= 0) onPlay(topSongs, position)
                }
            }
            item { Text("Top artists", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium) }
            itemsIndexed(stats.artists, key = { _, row -> "artist:${row.first}" }) { index, (artist, count) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${index + 1}. $artist", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$count plays", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Text("Counted from the most recent ${ListeningLibraryState.MAX_HISTORY} plays kept in your history.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val listeningDateFormat =DateTimeFormatter.ofPattern("d MMM · HH:mm")
private fun historyTime(time: Long) = runCatching { listeningDateFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())) }.getOrDefault("")
