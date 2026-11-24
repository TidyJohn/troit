package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The position callback may read the local player or another device's estimated playback position. */
@Composable
fun LyricsDialog(song: Song, positionMs: () -> Long, television: Boolean = false, onDismiss: () -> Unit) {
    val state = remember { LyricsState() }
    val scope = rememberCoroutineScope()
    var position by remember(song.url) { mutableLongStateOf(positionMs()) }
    val currentPosition by rememberUpdatedState(positionMs)
    LaunchedEffect(song.url, song.title, song.artist, song.durationSeconds) { state.load(song) }
    LaunchedEffect(song.url) { while (true) { position = currentPosition(); delay(250) } }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(if (television) 20.dp else 12.dp).fillMaxSize().widthIn(max = 1_100.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Lyrics", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (television) TvAction("Done", onClick = onDismiss)
                    else TextButton(onClick = onDismiss) { Text("Done") }
                }
                LyricsPanel(song, state.result, position, television, Modifier.weight(1f), state.loading, state.error,
                    onRetry = { scope.launch { state.load(song, refresh = true) } })
            }
        }
    }
}

/** Pure lyrics surface so phone, desktop and TV layout can be validated without a network request. */
@Composable
fun LyricsPanel(song: Song, lyrics: LyricsResult?, positionMs: Long, television: Boolean = false,
    modifier: Modifier = Modifier, loading: Boolean = false, error: String? = null, onRetry: () -> Unit = {}) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = television && maxWidth >= 720.dp
        val artworkSize = minOf(220.dp, (maxHeight - 240.dp).coerceAtLeast(96.dp))
        if (wide) Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(song.artwork, Modifier.size(artworkSize), RoundedCornerShape(18.dp))
                Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(song.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Lyrics by LRCLIB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LyricsBody(lyrics, positionMs, true, loading, error, onRetry, Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Artwork(song.artwork, Modifier.size(64.dp), RoundedCornerShape(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LyricsBody(lyrics, positionMs, television, loading, error, onRetry, Modifier.weight(1f))
            Text("Lyrics by LRCLIB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LyricsBody(lyrics: LyricsResult?, positionMs: Long, television: Boolean,
    loading: Boolean, error: String?, onRetry: () -> Unit, modifier: Modifier) {
    when {
        loading -> Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text("Finding lyrics…")
        }
        error != null -> Column(modifier, verticalArrangement = Arrangement.Center) {
            Text(error, color = MaterialTheme.colorScheme.error)
            if (television) TvAction("Retry lyrics", onClick = onRetry) else TextButton(onClick = onRetry) { Text("Retry lyrics") }
        }
        lyrics?.instrumental == true -> Column(modifier, verticalArrangement = Arrangement.Center) {
            Text("Instrumental", style = MaterialTheme.typography.headlineSmall)
            Text("This recording has no sung lyrics.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lyrics == null || !lyrics.available -> Column(modifier, verticalArrangement = Arrangement.Center) {
            Text("No lyrics for this recording", style = MaterialTheme.typography.titleLarge)
            Text("A matching version is not available from LRCLIB yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lyrics.lines.isEmpty() -> Column(modifier) {
            Text("Text lyrics · timing unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                itemsIndexed(lyrics.plainText.orEmpty().lineSequence().take(2_000).toList()) { _, line ->
                    Text(line, fontSize = if (television) 26.sp else 20.sp, lineHeight = if (television) 38.sp else 30.sp)
                }
            }
        }
        else -> TimedLyrics(lyrics, positionMs, television, modifier)
    }
}

@Composable
private fun TimedLyrics(lyrics: LyricsResult, positionMs: Long, television: Boolean, modifier: Modifier) {
    val active = lyrics.activeIndex(positionMs)
    val list = rememberLazyListState()
    var follow by remember(lyrics) { mutableStateOf(true) }
    var scrolling by remember { mutableStateOf(false) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.distinctUntilChanged().collect { if (it && !scrolling) follow = false }
    }
    LaunchedEffect(active, follow, lyrics) {
        if (follow && active >= 0) {
            scrolling = true
            try { list.animateScrollToItem((active - 1).coerceAtLeast(0)) }
            finally { scrolling = false }
        }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (follow) "Following playback" else "Browsing lyrics", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!follow) {
                if (television) TvAction("Follow lyrics", onClick = { follow = true })
                else TextButton(onClick = { follow = true }) { Text("Follow lyrics") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(if (television) 18.dp else 12.dp),
            contentPadding = PaddingValues(vertical = 18.dp)) {
            itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                Text(line.text.ifEmpty { " " }, modifier = Modifier.fillMaxWidth().onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) follow = false
                }.then(if (television) Modifier.focusable() else Modifier)
                    .border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
                    .background(if (index == active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surface, shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { selected = index == active; contentDescription = if (index == active) "Current lyric: ${line.text}" else line.text },
                    fontSize = if (television) 30.sp else 22.sp, lineHeight = if (television) 42.sp else 32.sp,
                    fontWeight = if (index == active) FontWeight.Bold else FontWeight.Medium,
                    color = if (index == active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f))
            }
        }
    }
}
