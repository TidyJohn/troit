package app.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val LocalPlaybackRemote = staticCompositionLocalOf<PlaybackRemoteState?> { null }
val LocalOpenDevices = staticCompositionLocalOf<() -> Unit> { {} }

/** A live view of the account's player; audio stays on the device named in the card. */
@Composable
fun ConnectedPlayerBar(sync: Sync, remote: PlaybackRemoteState, player: MusicPlayer?, deviceName: String) {
    val snapshot = remote.snapshot ?: return
    val scope = rememberCoroutineScope()
    val openDevices = LocalOpenDevices.current
    val preferences = LocalListeningPreferences.current
    val ecosystem = LocalEcosystem.current
    var transferring by remember { mutableStateOf(false) }
    var transferError by remember(sync.userId, snapshot.deviceId, snapshot.revision) { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        ConnectedPlaybackContent(snapshot, remote.busy || remote.pending || transferring,
            if (transferring) "Switching playback to this device…" else transferError ?: remote.error ?: remote.message,
            onControl = { action -> transferError = null; scope.launch { remote.sendControl(action) } },
            onDevices = openDevices,
            onListenHere = if (player != null && preferences.allows(snapshot.song)) ({
                scope.launch {
                    transferring = true
                    transferError = null
                    try {
                        if (!continueConnectedHere(sync, remote, player, preferences, ecosystem)) {
                            transferError = ecosystem?.error ?: remote.error ?: "Couldn't switch playback. Open Devices to check the connection."
                        }
                    }
                    finally { transferring = false }
                }; Unit
            }) else null,
            deviceName = deviceName, compact = true, messageIsError = transferError != null || remote.error != null)
    }
}

/** Wait for the old device's confirmed pause before starting audio here. */
internal suspend fun continueConnectedHere(
    sync: Sync, remote: PlaybackRemoteState, player: MusicPlayer, preferences: ListeningPreferences,
    ecosystem: EcosystemState? = null,
): Boolean {
    val target = remote.snapshot ?: return false
    val owner = sync.userId ?: return false
    val generation = sync.accountGeneration
    val localIntent = player.interactionRevision
    if (target.deviceId == sync.deviceId || !target.isFresh() || !preferences.allows(target.song)) return false
    fun currentIntent() = sync.userId == owner && sync.accountGeneration == generation && player.interactionRevision == localIntent
    if (ecosystem != null && !ecosystem.setupRequired) {
        ecosystem.refresh()
        if (!currentIntent()) return false
        val queue = ecosystem.queue
        if (queue != null) {
            if (queue.deviceId != target.deviceId || queue.instanceId != target.instanceId ||
                queue.playbackRevision != target.revision || queue.index != target.queueIndex ||
                queue.song?.url != target.song.url || !queue.songs.all(preferences::allows)) return false
            val destination = ecosystem.devices.firstOrNull { it.id == sync.deviceId } ?: return false
            return ecosystem.transferTo(destination, localDestination = player)
        }
    }
    try {
        player.preloadSource(target.song)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
    } catch (_: Exception) { return false }
    if (!currentIntent() ||
        remote.snapshot?.let { it.deviceId == target.deviceId && it.instanceId == target.instanceId &&
            it.revision == target.revision && it.queueIndex == target.queueIndex && it.song.url == target.song.url } != true) return false
    if (!remote.sendControl("pause")) return false
    val latest = remote.snapshot ?: return false
    if (!currentIntent() ||
        latest.deviceId != target.deviceId ||
        latest.instanceId != target.instanceId || latest.song.url != target.song.url || latest.queueIndex != target.queueIndex ||
        !latest.isFresh() || latest.playing) return false
    val handoff = sync.handoff?.takeIf { it.device == latest.deviceId && it.index == latest.queueIndex &&
        it.queue.getOrNull(it.index)?.url == latest.song.url }
    val queue = handoff?.queue ?: listOf(latest.song)
    val index = handoff?.index ?: 0
    val selection = preferences.selectPlayback(queue, index) ?: return false
    player.play(selection.songs, selection.index, remote.positionMs())
    sync.dismissHandoff()
    return true
}

@Composable
fun ConnectedDevicesDialog(
    sync: Sync, remote: PlaybackRemoteState, player: MusicPlayer?, deviceName: String,
    isTelevision: Boolean = false, preferences: ListeningPreferences = ListeningPreferences(), onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ecosystem = LocalEcosystem.current
    var transferring by remember { mutableStateOf(false) }
    LaunchedEffect(remote) { remote.refresh() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(if (isTelevision) 24.dp else 12.dp).widthIn(max = if (isTelevision) 760.dp else 560.dp)
            .fillMaxWidth().heightIn(max = if (isTelevision) 490.dp else 680.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(if (isTelevision) 28.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TuneIcon("devices", Modifier.size(28.dp), MaterialTheme.colorScheme.primary)
                    Text("Devices & playback", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                }
                Text("This device · $deviceName", style = MaterialTheme.typography.labelLarge)
                if (sync.userId == null) {
                    Text("Sign in with the same account on both devices to move your music between them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AccountCard(sync)
                } else {
                    if (ecosystem != null) {
                        EcosystemDeviceSection(sync, ecosystem, isTelevision)
                        HorizontalDivider()
                    }
                    val snapshot = remote.snapshot
                    when {
                        remote.setupRequired -> Text("Playback controls need the Troit Connect database update.", color = MaterialTheme.colorScheme.error)
                        snapshot == null -> {
                            Text("Start a song on any signed-in device to see its playback here.")
                            remote.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        snapshot.deviceId == sync.deviceId && player?.now?.song != null -> {
                            Text(if (player.now.playing) "Playing here" else "Paused here", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            PlayerBar(player)
                        }
                        else -> ConnectedPlaybackContent(snapshot, remote.busy || remote.pending || transferring, remote.error ?: remote.message,
                            onControl = { action -> scope.launch { remote.sendControl(action) } },
                            onListenHere = if (player != null && snapshot.deviceId != sync.deviceId && preferences.allows(snapshot.song)) ({
                                scope.launch {
                                    transferring = true
                                    try { if (continueConnectedHere(sync, remote, player, preferences, ecosystem)) onDismiss() }
                                    finally { transferring = false }
                                }; Unit
                            }) else null,
                            deviceName = deviceName, television = isTelevision)
                    }
                    TextButton(onClick = { scope.launch { remote.refresh(); sync.syncNow() } }, enabled = !remote.busy,
                        modifier = Modifier.heightIn(min = if (isTelevision) 56.dp else 48.dp)) { Text("Refresh connection") }
                    HorizontalDivider()
                    AccountCard(sync)
                }
            }
        }
    }
}

/** Kept independent of transport so layout and real button routing can be tested offscreen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectedPlaybackContent(
    snapshot: ConnectSnapshot, busy: Boolean, message: String?, onControl: (String) -> Unit,
    onDevices: (() -> Unit)? = null, onListenHere: (() -> Unit)? = null,
    deviceName: String = "this device", television: Boolean = false,
    compact: Boolean = false, messageIsError: Boolean = false,
) {
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot) { while (true) { time = System.currentTimeMillis(); delay(500) } }
    val fresh = snapshot.isFresh(time)
    val position = snapshot.estimatedPositionMs(time)
    val duration = snapshot.song.durationSeconds.coerceIn(0, 604800) * 1000
    if (compact) {
        CompactConnectedPlayback(snapshot, fresh, busy, position, duration, message, messageIsError,
            onControl, onDevices, onListenHere, deviceName, television)
        return
    }
    Column(Modifier.fillMaxWidth().padding(if (television) 20.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (fresh) "${if (snapshot.playing) "Playing" else "Paused"} on ${snapshot.deviceName}" else "${snapshot.deviceName} is offline",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = if (fresh) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (onDevices != null) TextButton(onClick = onDevices) { Text("Devices") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Artwork(snapshot.song.artwork, Modifier.size(if (television) 80.dp else 52.dp), RoundedCornerShape(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(snapshot.song.title, style = if (television) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(snapshot.song.artist, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (duration > 0) {
            LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = "Playback progress on ${snapshot.deviceName}" })
            Text("${connectTime(position)} / ${connectTime(duration)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LikeButton(snapshot.song, television = television)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectControl("previous", "Previous on ${snapshot.deviceName}", fresh && !busy, television) { onControl("previous") }
                ConnectControl(if (snapshot.playing) "pause" else "play", "${if (snapshot.playing) "Pause" else "Play"} on ${snapshot.deviceName}",
                    fresh && !busy, television, primary = true) { onControl(if (snapshot.playing) "pause" else "play") }
                ConnectControl("next", "Next on ${snapshot.deviceName}", fresh && !busy, television) { onControl("next") }
            }
            if (onListenHere != null) {
                if (television) TvAction("Listen here", enabled = fresh && !busy, onClick = onListenHere)
                else TextButton(onClick = onListenHere, enabled = fresh && !busy,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Listen here") }
            }
            val lyrics = LocalOpenLyrics.current
            val timer = LocalOpenSleepTimer.current
            EcosystemAction("Lyrics", television, onClick = lyrics)
            EcosystemAction("Sleep timer", television, onClick = timer)
        }
        if (onListenHere != null && fresh) Text("Continue this song on $deviceName.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!fresh) Text("Open Troit on ${snapshot.deviceName} to reconnect.", style = MaterialTheme.typography.bodySmall)
        if (busy || message != null) Text(if (busy) "Waiting for ${snapshot.deviceName}…" else message.orEmpty(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Persistent controls stay compact; recovery and secondary actions have a predictable menu. */
@Composable
private fun CompactConnectedPlayback(
    snapshot: ConnectSnapshot, fresh: Boolean, busy: Boolean, position: Long, duration: Long,
    message: String?, messageIsError: Boolean, onControl: (String) -> Unit,
    onDevices: (() -> Unit)?, onListenHere: (() -> Unit)?, deviceName: String, television: Boolean,
) {
    val status = if (!fresh) "${snapshot.deviceName} is offline"
        else "${if (snapshot.playing) "Playing" else "Paused"} on ${snapshot.deviceName}"
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val wide = maxWidth >= ((if (television) 800 else 640) * fontScale).dp
        val showDeviceIcon = television || maxWidth >= (340 * fontScale).dp
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactConnectedSummary(snapshot, if (wide) status else null, television, Modifier.weight(1f))
                if (wide && !television) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CompactConnectTransport(snapshot, fresh && !busy, false, onControl)
                }
                Row(if (wide && !television) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                LikeButton(snapshot.song, television = television,
                    modifier = if (television) Modifier.heightIn(min = 56.dp) else Modifier.size(48.dp))
                if (wide && television) CompactConnectTransport(snapshot, fresh && !busy, true, onControl)
                if (wide && onDevices != null) {
                    TextButton(onClick = onDevices, modifier = Modifier.heightIn(min = 48.dp)) {
                        TuneIcon("devices", Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Devices")
                    }
                }
                if (wide) ConnectedPlaybackOptions(snapshot, fresh, busy, onDevices, onListenHere, deviceName, television)
                }
            }
            if (!wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onDevices != null) TextButton(onClick = onDevices,
                        modifier = Modifier.weight(1f).heightIn(min = if (television) 56.dp else 48.dp)
                            .semantics { contentDescription = "Devices & playback. $status" },
                        shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        if (showDeviceIcon) {
                            TuneIcon("devices", Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        CompactDeviceStatus(snapshot.deviceName, fresh, snapshot.playing, Modifier.weight(1f))
                    } else CompactDeviceStatus(snapshot.deviceName, fresh, snapshot.playing, Modifier.weight(1f))
                    CompactConnectTransport(snapshot, fresh && !busy, television, onControl)
                    ConnectedPlaybackOptions(snapshot, fresh, busy, onDevices, onListenHere, deviceName, television)
                }
            }
            if (duration > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(2.dp).semantics {
                        contentDescription = "Playback progress on ${snapshot.deviceName}"
                    })
                Text("${connectTime(position)} / ${connectTime(duration)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (busy || !message.isNullOrBlank()) Text(if (busy) "Waiting for ${snapshot.deviceName}…" else message.orEmpty(),
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (messageIsError && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CompactConnectedSummary(snapshot: ConnectSnapshot, status: String?, television: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Artwork(snapshot.song.artwork, Modifier.size(if (television) 56.dp else 44.dp), RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(snapshot.song.title, style = if (television) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(snapshot.song.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status != null) Text(status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CompactDeviceStatus(device: String, fresh: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(device, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (!fresh) "Offline" else if (playing) "Playing" else "Paused", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompactConnectTransport(snapshot: ConnectSnapshot, enabled: Boolean, television: Boolean,
    onControl: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (television) 6.dp else 0.dp)) {
        ConnectControl("previous", "Previous on ${snapshot.deviceName}", enabled, television, compact = true) { onControl("previous") }
        ConnectControl(if (snapshot.playing) "pause" else "play", "${if (snapshot.playing) "Pause" else "Play"} on ${snapshot.deviceName}",
            enabled, television, primary = true, compact = true) { onControl(if (snapshot.playing) "pause" else "play") }
        ConnectControl("next", "Next on ${snapshot.deviceName}", enabled, television, compact = true) { onControl("next") }
    }
}

@Composable
private fun ConnectedPlaybackOptions(snapshot: ConnectSnapshot, fresh: Boolean, busy: Boolean,
    onDevices: (() -> Unit)?, onListenHere: (() -> Unit)?, deviceName: String, television: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }
    LaunchedEffect(expanded) {
        if (expanded) { withFrameNanos { }; menuFocus.requestFocus() }
    }
    val lyrics = LocalOpenLyrics.current
    val timer = LocalOpenSleepTimer.current
    val closeOnBack: (KeyEvent) -> Boolean = { event ->
        if (expanded && event.type == KeyEventType.KeyDown && (event.key == Key.Escape || event.key == Key.Back)) {
            expanded = false; true
        } else false
    }
    Box(Modifier.onPreviewKeyEvent(closeOnBack)) {
        if (television) TvAction("More", modifier = Modifier.semantics { contentDescription = "More playback options" }, onClick = { expanded = true })
        else IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "More playback options" }) {
            TuneIcon("more", Modifier.size(22.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.focusRequester(menuFocus).onPreviewKeyEvent(closeOnBack).focusable()) {
            if (!fresh) Text("Open Troit on ${snapshot.deviceName} to reconnect.", Modifier.widthIn(max = 280.dp).padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onListenHere != null) DropdownMenuItem(enabled = fresh && !busy, text = {
                Column {
                    Text("Listen here")
                    Text("Move playback to $deviceName", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, onClick = { expanded = false; onListenHere() })
            if (onDevices != null) DropdownMenuItem(text = { Text("Devices & playback") }, onClick = { expanded = false; onDevices() })
            DropdownMenuItem(text = { Text("Lyrics") }, onClick = { expanded = false; lyrics() })
            DropdownMenuItem(text = { Text("Sleep timer") }, onClick = { expanded = false; timer() })
        }
    }
}

@Composable
private fun ConnectControl(icon: String, label: String, enabled: Boolean, television: Boolean,
    primary: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.size(if (television) 60.dp else 48.dp).semantics { contentDescription = label }
    if (television) {
        TvAction(icon.replaceFirstChar { it.uppercase() }, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }, onClick = onClick)
        return
    }
    if (primary) FilledIconButton(onClick = onClick, enabled = enabled, modifier = modifier) { TuneIcon(icon, Modifier.size(24.dp)) }
    else if (compact) IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { TuneIcon(icon, Modifier.size(22.dp)) }
    else OutlinedIconButton(onClick = onClick, enabled = enabled, modifier = modifier) { TuneIcon(icon, Modifier.size(24.dp)) }
}

private fun connectTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
