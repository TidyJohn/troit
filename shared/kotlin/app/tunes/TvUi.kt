package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val tvDestinations = listOf("Discover" to "home", "Search" to "search", "Library" to "library", "Now playing" to "music")

/** TV uses large, focusable controls throughout; no touch-only menus or hover actions. */
@Composable
fun TvAppContent(
    player: MusicPlayer?, dataDir: File, deviceName: String, library: Library, sync: Sync,
    onDevices: () -> Unit,
    backHandler: @Composable (onBack: () -> Unit) -> Unit,
    homeSource: ((ListeningPreferences) -> List<Section>)? = null,
) {
    val scope = rememberCoroutineScope()
    val preferences = remember(dataDir) { RegionPreferences(File(dataDir, "listening-preferences.json")) }
    val settings = preferences.settings
    val home = remember(settings, homeSource) {
        if (homeSource == null) Loader(scope, Music.homeFeed(settings)) else Loader(scope) { homeSource(settings) }
    }
    DisposableEffect(home) { onDispose { home.reset() } }
    val search = remember { SearchState(scope) }
    val remote = LocalPlaybackRemote.current
    val ecosystem = LocalEcosystem.current
    val remoteSnapshot = remote?.snapshot?.takeIf { it.deviceId != sync.deviceId && player?.now?.playing != true }
    var tab by remember { mutableIntStateOf(0) }
    val pages = remember { mutableStateListOf<Item>() }
    var account by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    val navigationFocus = remember { tvDestinations.map { FocusRequester() } }
    val footerDevicesFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { navigationFocus.first().requestFocus() }
    LaunchedEffect(settings) { search.updatePreferences(settings) }
    fun navigate(index: Int) {
        pages.clear()
        tab = index
        navigationFocus[index].requestFocus()
    }
    fun popPage() {
        pages.removeAt(pages.lastIndex)
        if (pages.isEmpty()) navigationFocus[tab].requestFocus()
    }
    if (pages.isNotEmpty()) backHandler(::popPage)
    else if (tab != 0) backHandler { navigate(0) }

    fun play(songs: List<Song>, index: Int) {
        val selection = settings.selectPlayback(songs, index) ?: return
        player?.play(selection.songs, selection.index)
    }
    fun open(item: Item, siblings: List<Item>, index: Int) {
        if (item is Song) play(siblings.filterIsInstance<Song>(), siblings.take(index).count { it is Song })
        else pages += item
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Troit", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(deviceName, modifier = Modifier.weight(1f).padding(end = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ecosystem?.queue != null) {
                TvAction("Shared queue", onClick = LocalOpenSharedQueue.current)
                Spacer(Modifier.width(12.dp))
            }
            ListeningFeaturesMenu(television = true)
            Spacer(Modifier.width(12.dp))
            TvAction(if (sync.userId == null) "Sign in" else "Account", onClick = { account = true })
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.width(174.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tvDestinations.forEachIndexed { index, (title, icon) ->
                    TvAction(title, selected = index == tab, icon = icon,
                        modifier = Modifier.fillMaxWidth().semantics { selected = index == tab }.focusRequester(navigationFocus[index]).focusProperties {
                            if (index > 0) up = navigationFocus[index - 1]
                            down = navigationFocus.getOrNull(index + 1) ?: footerDevicesFocus
                        },
                        onClick = { navigate(index) })
                }
                Spacer(Modifier.weight(1f))
                Text("Arrows to move\nOK to select", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val page = pages.lastOrNull()
                when {
                    page != null -> key(pages.size, page) {
                        TvPage(page, settings, player, ::play, ::open, ::popPage)
                    }
                    tab == 0 -> TvLoaded(home) { sections -> TvShelves(sections, ::open) }
                    tab == 1 -> TvSearch(search, ::open)
                    tab == 2 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your playlists", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            if (LocalListeningLibrary.current != null) TvAction("Liked songs & history", onClick = LocalOpenListeningLibrary.current)
                        }
                        when {
                            library.playlists.isNotEmpty() -> TvItemList(library.playlists, ::open)
                            sync.userId == null -> TvMessage("No playlists on this TV", "Pair your phone to bring your saved playlists here.",
                                actionLabel = "Pair with phone", action = { pairing = true })
                            else -> TvMessage("No saved playlists", "Save a playlist on your phone or computer to see it here.",
                                actionLabel = "Browse music", action = { navigate(0) })
                        }
                    }
                    remoteSnapshot != null && remote != null -> ConnectedPlaybackContent(remoteSnapshot,
                        remote.busy || remote.pending, remote.error ?: remote.message,
                        onControl = { action -> scope.launch { remote.sendControl(action) } },
                        onDevices = onDevices,
                        onListenHere = if (player != null && settings.allows(remoteSnapshot.song)) ({
                            scope.launch { continueConnectedHere(sync, remote, player, settings, ecosystem) }; Unit
                        }) else null,
                        deviceName = deviceName, television = true, compact = true)
                    else -> TvNowPlaying(player, ::play, onBrowse = { navigate(0) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        TvTransport(player, onDevices, remote, sync.deviceId, footerDevicesFocus)
    }
    if (account) AlertDialog(
        onDismissRequest = { account = false },
        title = { Text("Your Troit account") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text("Use the same account on your phone, computer and TV.", modifier = Modifier.padding(bottom = 12.dp))
            if (sync.userId == null) TvAction("Pair with phone", onClick = { account = false; pairing = true })
            AccountCard(sync)
        } },
        confirmButton = { TvAction("Done", onClick = { account = false }) },
    )
    if (pairing) {
        val pairingState = remember(sync, deviceName) { DevicePairingState(sync, deviceName) }
        PairTvDialog(pairingState) { pairing = false }
    }
}

@Composable
internal fun TvAction(
    label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    icon: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val background = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }.copy(alpha = if (enabled) 1f else .45f)
    Box(modifier.heightIn(min = 56.dp).onFocusChanged { focused = it.isFocused }
        .tvDirectionalFocus()
        .clip(shape).background(background)
        .border(if (focused) 3.dp else 1.dp, when {
            focused -> MaterialTheme.colorScheme.onSurface
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }, shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = if (icon == null) Alignment.Center else Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) TuneIcon(icon, Modifier.size(22.dp), foreground)
            Text(label, color = foreground, fontSize = 16.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TvMessage(title: String, detail: String = "", action: (() -> Unit)? = null, actionLabel: String = "Try again") {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (detail.isNotBlank()) Text(detail, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(16.dp)); TvAction(actionLabel, onClick = action) }
    }
}

@Composable
private fun <T> TvLoaded(loader: Loader<List<T>>, content: @Composable (List<T>) -> Unit) {
    LaunchedEffect(loader) { loader.start() }
    val result = loader.result
    when {
        result == null -> TvMessage("Loading music…")
        result.isFailure -> TvMessage("Couldn't load music", result.exceptionOrNull()?.message.orEmpty(), loader::retry)
        result.getOrThrow().isEmpty() -> TvMessage("Nothing here yet")
        else -> content(result.getOrThrow())
    }
}

@Composable
private fun TvShelves(sections: List<Section>, onOpen: (Item, List<Item>, Int) -> Unit) {
    val keys = remember(sections) { stableOccurrenceKeys(sections) { it.title } }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(sections, key = { index, _ -> keys[index] }) { _, section ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(4.dp)) {
                    itemsIndexed(section.items, key = { index, item -> "$index:${item.listIdentity()}" }) { index, item ->
                        TvTile(item) { onOpen(item, section.items, index) }
                    }
                    section.more?.let { more -> item { TvAction("See all", onClick = { onOpen(more, listOf(more), 0) }) } }
                }
            }
        }
    }
}

@Composable
private fun TvTile(item: Item, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(164.dp).onFocusChanged { focused = it.isFocused }.clip(shape)
        .tvDirectionalFocus()
        .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
        .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(role = Role.Button, onClickLabel = if (item is Song) "Play song" else "Open collection", onClick = onClick)
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Artwork(item.artwork, Modifier.fillMaxWidth().height(112.dp))
            if (focused) Box(Modifier.padding(6.dp).align(Alignment.BottomEnd).size(30.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                TuneIcon(if (item is Song) "play" else "arrow", Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimary)
            }
        }
        Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TvItemList(items: List<Item>, onOpen: (Item, List<Item>, Int) -> Unit, modifier: Modifier = Modifier,
    currentIndex: Int? = null) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex?.coerceAtLeast(0) ?: 0)
    LazyColumn(modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(items, key = { index, item -> "$index:${item.listIdentity()}" }) { index, item ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(12.dp)
            Row(Modifier.fillMaxWidth().semantics { if (currentIndex != null) selected = index == currentIndex }
                .onFocusChanged { focused = it.isFocused }.clip(shape)
                .tvDirectionalFocus()
                .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .border(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(role = Role.Button) { onOpen(item, items, index) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Artwork(item.artwork, Modifier.size(54.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TuneIcon(when {
                    index == currentIndex -> "volume"
                    item is Song -> "play"
                    else -> "arrow"
                }, Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun TvPage(item: Item, settings: ListeningPreferences, player: MusicPlayer?, onPlay: Play,
    onOpen: (Item, List<Item>, Int) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val backFocus = remember(item) { FocusRequester() }
    LaunchedEffect(item) { backFocus.requestFocus() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvAction("Back", modifier = Modifier.focusRequester(backFocus), onClick = onBack)
            Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (item) {
            is SavedPlaylist -> TvSongs(item.songs, player, onPlay)
            is Playlist -> {
                val loader = remember(item, settings) { Loader(scope) { Music.songsIn(item.browseId, settings) } }
                TvLoaded(loader) { songs -> TvSongs(songs, player, onPlay) }
            }
            is Artist, is Category -> {
                val loader = remember(item, settings) { Loader(scope) {
                    when (item) {
                        is Artist -> Music.sections(item.browseId, preferences = settings)
                        is Category -> Music.sections(item.browseId, item.params, settings)
                        else -> emptyList()
                    }
                } }
                TvLoaded(loader) { TvShelves(it, onOpen) }
            }
            else -> TvMessage("Open this collection on your phone or computer")
        }
    }
}

@Composable
private fun TvSongs(songs: List<Song>, player: MusicPlayer?, onPlay: Play) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TvAction("Play all", enabled = songs.isNotEmpty() && player != null, onClick = { onPlay(songs, 0) })
            Text("${songs.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TvItemList(songs, { _, _, index -> onPlay(songs, index) }, Modifier.weight(1f))
    }
}

@Composable
private fun TvSearch(state: SearchState, onOpen: (Item, List<Item>, Int) -> Unit) {
    LaunchedEffect(state.query) {
        if (state.query.isNotBlank()) {
            delay(300)
            state.runIfChanged()
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(state.query, state::updateQuery, modifier = Modifier.weight(1f), singleLine = true,
                label = { Text("Search music") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.runIfChanged() }))
            if (state.query.isNotEmpty()) TvAction("Clear", onClick = state::clear)
        }
        if (state.query.isNotBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(listOf("Songs" to Yt.SONGS, "Artists" to Yt.ARTISTS, "Albums" to Yt.ALBUMS, "Playlists" to Yt.PLAYLISTS)) { _, filter ->
                TvAction(filter.first, selected = state.filter == filter.second, onClick = { state.filter = filter.second; state.run() })
            }
        }
        when {
            state.searching || state.waitingForQuery -> TvMessage("Searching…")
            state.result?.isFailure == true -> TvMessage("Search couldn't connect", state.result?.exceptionOrNull()?.message.orEmpty(), state::run)
            state.result?.getOrNull()?.isEmpty() == true -> TvMessage("No matches", "Try another song, artist or album.")
            state.result != null -> Column(Modifier.weight(1f)) {
                TvItemList(state.result?.getOrNull().orEmpty(), onOpen, Modifier.weight(1f))
                if (state.more != null) TvAction(if (state.loadingMore) "Loading…" else if (state.moreFailed) "Retry more results" else "More results",
                    enabled = !state.loadingMore, onClick = state::loadMore)
            }
            else -> TvMessage("Find a song, artist, album or playlist", "Select Search music and type with your TV keyboard.")
        }
    }
}

@Composable
private fun TvNowPlaying(player: MusicPlayer?, onPlay: Play, onBrowse: () -> Unit) {
    val activePlayer = player ?: run { TvMessage("Connecting to the player…"); return }
    val now = activePlayer.now
    val song = now?.song
    if (song == null) {
        TvMessage("Nothing playing", "Pick a song to play on this TV.", action = onBrowse, actionLabel = "Browse music")
        return
    }
    val videoOpen = LocalVideoOpen.current
    val surface = LocalVideoSurface.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (videoOpen != null && surface != null) TvAction(if (videoOpen.value) "Hide video" else "Music video", selected = videoOpen.value,
                icon = "video", onClick = { videoOpen.value = !videoOpen.value })
            TvAction("−10 sec", onClick = { activePlayer.seekTo((activePlayer.positionMs() - 10_000).coerceAtLeast(0)) })
            TvAction("+10 sec", onClick = { activePlayer.seekTo(activePlayer.positionMs() + 10_000) })
            var modesOpen by remember { mutableStateOf(false) }
            Box {
                TvAction("Playback mode", icon = activePlayer.playbackMode.icon, onClick = { modesOpen = true })
                DropdownMenu(expanded = modesOpen, onDismissRequest = { modesOpen = false }) {
                    PlaybackMode.entries.forEach { mode ->
                        DropdownMenuItem(modifier = Modifier.heightIn(min = 56.dp).semantics { selected = activePlayer.playbackMode == mode },
                            text = { Text(mode.label) },
                            leadingIcon = { TuneIcon(mode.icon, Modifier.size(22.dp)) },
                            trailingIcon = { if (activePlayer.playbackMode == mode) TuneIcon("check", Modifier.size(20.dp)) },
                            onClick = { activePlayer.setPlaybackMode(mode); modesOpen = false })
                    }
                }
            }
        }
        // On a TV the picture takes the queue's room; the actions above stay in reach of the remote.
        if (videoOpen?.value == true && surface != null) {
            Box(Modifier.weight(1f).aspectRatio(16f / 9f).align(Alignment.CenterHorizontally).background(androidx.compose.ui.graphics.Color.Black)) {
                surface(song, Modifier.fillMaxSize())
            }
        } else {
            Text("Queue · ${now.index + 1} of ${now.queue.size}", style = MaterialTheme.typography.titleMedium)
            TvItemList(now.queue, { _, _, index -> onPlay(now.queue, index) }, Modifier.weight(1f), now.index)
        }
    }
}

@Composable
internal fun TvTransport(player: MusicPlayer?, onDevices: () -> Unit, remote: PlaybackRemoteState? = null,
    localDeviceId: String = "", devicesFocus: FocusRequester? = null) {
    val now = player?.now
    val scope = rememberCoroutineScope()
    val snapshot = remote?.snapshot?.takeIf { it.deviceId != localDeviceId && now?.playing != true }
    val song = snapshot?.song ?: now?.song
    val deviceModifier = if (devicesFocus == null) Modifier else Modifier.focusRequester(devicesFocus)
    if (song == null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Play here from your phone or computer", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TvAction("Devices", deviceModifier, icon = "devices", onClick = onDevices)
        }
        return
    }
    val playing = snapshot?.playing ?: (now?.playing == true)
    var time by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var position by remember(player) { mutableLongStateOf(0) }
    // Tick even when a paused local player's position is unchanged: remote controls must expire.
    LaunchedEffect(player) {
        while (true) { time = System.currentTimeMillis(); position = player?.positionMs() ?: 0; delay(1_000) }
    }
    val remoteFresh = snapshot?.isFresh(time) != false
    val remoteBusy = snapshot != null && (remote?.busy == true || remote?.pending == true)
    val remoteReady = remoteFresh && !remoteBusy
    val duration = (if (snapshot != null) song.durationSeconds.coerceIn(0, 604800) * 1_000 else player?.durationMs()) ?: 0
    val currentPosition = (snapshot?.estimatedPositionMs(time) ?: position).coerceAtLeast(0)
    val status = when {
        snapshot != null && !remoteFresh -> "${snapshot.deviceName} · Offline"
        remoteBusy -> "${snapshot?.deviceName} · Updating…"
        snapshot != null && remote?.error != null -> "${snapshot.deviceName} · ${remote.error}"
        snapshot != null -> "${snapshot.deviceName} · ${if (playing) "Playing" else "Paused"}"
        now?.loading == true -> "Loading…"
        now?.error != null -> now.error.orEmpty()
        else -> if (playing) "Playing on this TV" else "Paused on this TV"
    }
    fun control(action: String) {
        if (snapshot != null && remote != null) scope.launch { remote.sendControl(action) }
        else when (action) {
            "previous" -> player?.previous()
            "next" -> player?.next()
            else -> player?.toggle()
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Artwork(song.artwork, Modifier.size(48.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$status · ${song.artist}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
                        color = if (now?.error != null && snapshot == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (duration > 0) "${tvTime(currentPosition)} / ${tvTime(duration)}" else tvTime(currentPosition),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (LocalListeningLibrary.current != null) TvTransportHeart(song)
            TvAction("Previous", enabled = remoteReady, onClick = { control("previous") })
            TvAction(if (snapshot == null && now?.loading == true) "Loading…" else if (playing) "Pause" else "Play",
                enabled = remoteReady, onClick = { control(if (playing) "pause" else "play") })
            TvAction("Next", enabled = remoteReady, onClick = { control("next") })
            TvAction("Devices", deviceModifier, onClick = onDevices)
        }
        if (duration > 0) LinearProgressIndicator(
            progress = { (currentPosition.toFloat() / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Playback progress" })
    }
}

/** Keep the one-tap heart without a wide secondary text button in the persistent TV footer. */
@Composable
private fun TvTransportHeart(song: Song) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(56.dp).onFocusChanged { focused = it.hasFocus }.tvDirectionalFocus().clip(shape)
        .background(if (focused) colors.primary else colors.surfaceContainerHigh)
        .border(if (focused) 3.dp else 1.dp, if (focused) colors.onSurface else colors.outlineVariant, shape)) {
        MaterialTheme(colorScheme = if (focused) colors.copy(primary = colors.onPrimary, onSurfaceVariant = colors.onPrimary) else colors) {
            LikeButton(song, modifier = Modifier.fillMaxSize())
        }
    }
}

internal fun tvTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Explicit spatial navigation also keeps hardware-arrow behavior consistent in desktop TV previews. */
@Composable
private fun Modifier.tvDirectionalFocus(): Modifier {
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }
        if (direction != null && event.type == KeyEventType.KeyDown) focus.moveFocus(direction) else false
    }
}
