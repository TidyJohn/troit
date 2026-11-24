package app.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private enum class PlayerView { PLAYER, QUEUE }

/** One player surface on phone and desktop, with a seekable timeline and an inspectable queue. */
@Composable
fun PlayerBar(player: MusicPlayer, handoff: RemotePlayback? = null, onDismissHandoff: () -> Unit = {}) {
    val now = player.now
    val song = now.song
    val remote = handoff?.takeIf { !now.playing && it.queue.getOrNull(it.index) != null }
    // Leave room for discovery until there is an actual local or connected listening session.
    if (song == null && remote == null) return
    var sheet by remember { mutableStateOf<PlayerView?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        if (remote != null) {
            HandoffCardContent(remote, player, onDismissHandoff)
        } else if (song != null) {
            BoxWithConstraints(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                val roomForVolume = maxWidth >= 1000.dp
                val showCompactTimes = maxWidth >= 480.dp
                if (maxWidth >= 720.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        SongSummary(now, Modifier.weight(1f)) { sheet = PlayerView.PLAYER }
                        Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlaybackControls(player)
                            PlaybackTimeline(player, compact = true)
                        }
                        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (roomForVolume) VolumeControl(player, Modifier.width(120.dp))
                            LikeButton(song)
                            VideoToggleButton()
                            QueueButton { sheet = PlayerView.QUEUE }
                        }
                    }
                } else {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongSummary(now, Modifier.weight(1f)) { sheet = PlayerView.PLAYER }
                            PlaybackControls(player, compact = true)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlaybackTimeline(player, Modifier.weight(1f), compact = true, showTimes = showCompactTimes)
                            VideoToggleButton()
                            QueueButton { sheet = PlayerView.QUEUE }
                        }
                    }
                }
            }
        }
    }
    sheet?.let { view ->
        if (song != null) PlaybackQueue(player, view, { sheet = it }) { sheet = null }
    }
}

/** Connected song details own their space; actions never compete with a one-line title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandoffCardContent(handoff: RemotePlayback, player: MusicPlayer, onDismiss: () -> Unit) {
    val song = handoff.queue.getOrNull(handoff.index) ?: return
    val preferences = LocalListeningPreferences.current
    val selection = remember(handoff.queue, handoff.index, preferences) {
        preferences.selectPlayback(handoff.queue, handoff.index)
    }
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Last played on ${handoff.deviceName}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(song.artwork, Modifier.size(56.dp), RoundedCornerShape(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(song.title, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(song.artist, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selection == null) Text(
            "This track is hidden by your music filters.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(enabled = selection != null, onClick = {
                selection?.let { player.play(it.songs, it.index, handoff.positionMs) }
                onDismiss()
            }) {
                TuneIcon("play", Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Continue here")
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun SongSummary(now: NowPlaying, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val song = now.song ?: return
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open now playing", onClick = onClick).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.artwork, Modifier.size(52.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    now.error != null -> "${now.error} Tap Play to retry."
                    now.loading -> "Loading audio…"
                    else -> song.artist
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (now.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaybackControls(player: MusicPlayer, compact: Boolean = false) {
    val now = player.now
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 10.dp)) {
        if (!compact) IconButton(
            onClick = player::previous,
            modifier = Modifier.semantics { contentDescription = "Previous track or restart" },
        ) { TuneIcon("previous", Modifier.size(22.dp)) }
        FilledIconButton(
            onClick = player::toggle,
            enabled = !now.loading || now.playing,
            shape = CircleShape,
            modifier = Modifier.size(if (compact) 44.dp else 42.dp).semantics {
                contentDescription = when {
                    now.error != null -> "Retry playback"
                    now.loading && now.playing -> "Cancel loading and pause"
                    now.loading -> "Loading audio"
                    now.playing -> "Pause"
                    else -> "Play"
                }
            },
        ) {
            if (now.loading && !now.playing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TuneIcon(if (now.playing) "pause" else "play", Modifier.size(22.dp))
            }
        }
        IconButton(
            onClick = player::next,
            enabled = canPlayNext(player.playbackMode, now),
            modifier = Modifier.semantics { contentDescription = "Next track" },
        ) { TuneIcon("next", Modifier.size(22.dp)) }
    }
}

/** The quick icon and the expanded queue share the same explicit four-choice menu. */
@Composable
internal fun PlaybackModeSelector(player: MusicPlayer, expanded: Boolean = false) {
    val mode = player.playbackMode
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        if (expanded) TextButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "Playback mode: ${mode.label}" }) {
            TuneIcon(mode.icon, Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Text(mode.label)
            Spacer(Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.labelSmall)
        } else Hint("When songs finish: ${mode.label}") {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Playback mode: ${mode.label}" }) {
                TuneIcon(mode.icon, Modifier.size(21.dp), if (mode == PlaybackMode.STOP) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.widthIn(max = 310.dp)) {
            PlaybackMode.entries.forEach { choice ->
                DropdownMenuItem(onClick = { player.setPlaybackMode(choice); menuOpen = false },
                    leadingIcon = { TuneIcon(choice.icon, Modifier.size(22.dp)) },
                    trailingIcon = { if (choice == mode) Text("✓", color = MaterialTheme.colorScheme.primary) },
                    text = {
                        Column(Modifier.widthIn(max = 220.dp)) {
                            Text(choice.label, style = MaterialTheme.typography.titleSmall)
                            Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
            }
        }
    }
}

@Composable
private fun PlaybackTimeline(player: MusicPlayer, modifier: Modifier = Modifier, compact: Boolean = false, showTimes: Boolean = true) {
    val now = player.now
    var position by remember(player, now.song?.url) { mutableLongStateOf(player.positionMs()) }
    var duration by remember(player, now.song?.url) { mutableLongStateOf(player.durationMs()) }
    var scrub by remember(player, now.song?.url) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, now.song?.url) {
        while (true) {
            position = player.positionMs().coerceAtLeast(0)
            duration = player.durationMs().coerceAtLeast(0)
            delay(500)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTimes) Text(
            playbackTime(scrub?.toLong() ?: position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(35.dp),
        )
        Slider(
            value = (scrub ?: position.toFloat()).coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                scrub?.let {
                    player.seekTo(it.toLong())
                    position = it.toLong()
                }
                scrub = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = duration > 0 && !now.loading && now.error == null,
            modifier = Modifier.weight(1f).height(if (compact) 24.dp else 40.dp).semantics { contentDescription = "Playback position" },
        )
        if (showTimes) Text(
            if (duration > 0) playbackTime(duration) else "—:—",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(35.dp),
        )
    }
}

/** Only where the app owns the volume; on Android the system volume keys already do this. */
@Composable
private fun VolumeControl(player: MusicPlayer, modifier: Modifier = Modifier) {
    val volume = player.volume ?: return
    var drag by remember { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TuneIcon("volume", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = drag ?: volume,
            onValueChange = { drag = it; player.setVolume(it) },
            onValueChangeFinished = { drag = null },
            modifier = Modifier.weight(1f).height(24.dp).semantics { contentDescription = "Volume" },
        )
    }
}

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (seconds >= 3600) "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:$remainder"
    else "${seconds / 60}:$remainder"
}

/** The same icon on every width; the queue's length is shown inside, where there is room for it. */
@Composable
private fun QueueButton(onClick: () -> Unit) = Hint("Now playing and queue") {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Open play queue" }) {
        TuneIcon("queue", Modifier.size(18.dp))
        Text("Queue", Modifier.padding(start = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackQueue(player: MusicPlayer, view: PlayerView, onView: (PlayerView) -> Unit, onDismiss: () -> Unit) {
    val now = player.now
    val song = now.song ?: return
    val preferences = LocalListeningPreferences.current
    val queueKeys = remember(now.queue) { stableOccurrenceKeys(now.queue, Item::listIdentity) }
    val allowedTracks = remember(now.queue, preferences) { now.queue.map(preferences::allows) }
    var queueMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(queueMessage) {
        if (queueMessage != null) { delay(4_000); queueMessage = null }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(20.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close now playing" }) {
                        TuneIcon("close", Modifier.size(22.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(view == PlayerView.PLAYER, onClick = { onView(PlayerView.PLAYER) }, label = { Text("Player") })
                    FilterChip(view == PlayerView.QUEUE, onClick = { onView(PlayerView.QUEUE) }, label = { Text("Queue (${now.queue.size})") })
                }
                if (view == PlayerView.PLAYER) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(song.artwork, Modifier.size(90.dp), RoundedCornerShape(16.dp))
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (now.error != null) Text(now.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    now.audioQuality?.takeIf { now.error == null && !now.loading }?.let { quality ->
                        Text(
                            "Best available · ${quality.description}",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    PlaybackTimeline(player)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PlaybackControls(player) }
                    PlaybackModeSelector(player, expanded = true)
                    val openLyrics = LocalOpenLyrics.current
                    val openTimer = LocalOpenSleepTimer.current
                    val openTransitions = LocalOpenTransitions.current
                    VolumeControl(player, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        LikeButton(song)
                        SaveButton(song)
                        TextButton(onClick = openLyrics) { Text("Lyrics") }
                        TextButton(onClick = openTimer) { Text("Sleep timer") }
                        TextButton(onClick = openTransitions) { Text("Playback settings") }
                        // The picture shows beside the app, not in this view, so showing it closes the view.
                        LocalVideoOpen.current?.let { video ->
                            TextButton(onClick = { video.value = !video.value; if (video.value) onDismiss() }) {
                                Text(if (video.value) "Hide music video" else "Show music video")
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Track ${now.index + 1} of ${now.queue.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (LocalEcosystem.current?.queue != null) {
                            val openSharedQueue = LocalOpenSharedQueue.current
                            TextButton(onClick = { onDismiss(); openSharedQueue() }) { Text("Edit shared queue") }
                        }
                    }
                    queueMessage?.let { message ->
                        Text(message, Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = now.index.coerceAtLeast(0))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(now.queue, key = { index, _ -> queueKeys[index] }, contentType = { _, _ -> "queue-song" }) { index, queued ->
                            val selected = now.index == index
                            val allowed = allowedTracks[index]
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface)
                                    .clickable(onClickLabel = "Play ${queued.title}") {
                                        val selection = preferences.selectPlayback(now.queue, index)
                                        if (selection == null) queueMessage = "This track is excluded by your music preferences. Change them in Settings to play it."
                                        else {
                                            queueMessage = null
                                            player.play(selection.songs, selection.index)
                                        }
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                                    if (selected) TuneIcon(if (now.playing) "music" else "pause", Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
                                    else Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Artwork(queued.artwork, Modifier.size(40.dp), RoundedCornerShape(8.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(queued.title, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (allowed) queued.artist else "Excluded by your preferences", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (queued.durationSeconds > 0) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(playbackTime(queued.durationSeconds * 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
