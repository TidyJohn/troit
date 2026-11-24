package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File

val LocalAppActive = staticCompositionLocalOf { true }
val LocalUnreadChatPeers = staticCompositionLocalOf<Set<String>> { emptySet() }
val LocalSocialState = staticCompositionLocalOf<SocialState?> { null }

/** What the player is doing right now. */
data class NowPlaying(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val audioQuality: AudioQuality? = null,
) {
    val song get() = queue.getOrNull(index)
}

/** The one thing that differs between phone and PC: how audio actually gets played. */
interface MusicPlayer {
    val now: NowPlaying
    val playbackMode: PlaybackMode get() = PlaybackMode.STOP
    fun setPlaybackMode(mode: PlaybackMode) {}
    val transitionSettings: TransitionSettings get() = TransitionSettings()
    fun setTransitionSettings(settings: TransitionSettings) {}
    val canNormalize: Boolean get() = true
    /** 0..1 where the app owns the volume (Windows); null where the system volume keys do (Android). */
    val volume: Float? get() = null
    fun setVolume(value: Float) {}
    val completedTrackRevision: Long get() = 0L
    fun setStopAfterCurrent(enabled: Boolean) {}
    fun setSleepDeadline(deadlineMillis: Long?) {}
    /** Explicit local playback intent; automatic queue progression does not increment this. */
    val interactionRevision: Long get() = 0L
    /** Resolve a source without changing the current queue, position or audio output. */
    suspend fun preloadSource(song: Song) {}
    fun positionMs(): Long
    fun play(songs: List<Song>, index: Int, positionMs: Long = 0)
    fun toggle()
    fun next()
    fun previous()
    /** Apply on the player's owning thread, after checking the target and account again. */
    suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean = { true }): Boolean {
        val live = now
        if (!allowed() || live.song?.url != expectedUrl || live.index != expectedIndex ||
            (live.loading && !(action == "pause" && live.playing))) return false
        when (action) {
            "next" -> next()
            "previous" -> previous()
            "play" -> if (!live.playing) toggle()
            "pause" -> if (live.playing) toggle()
            else -> return false
        }
        return true
    }
    /** A null expected queue starts a transferred session; an edit must preserve the audible song. */
    suspend fun replaceQueue(expectedQueue: List<Song>?, songs: List<Song>, index: Int, positionMs: Long,
        playing: Boolean, mode: PlaybackMode, allowed: () -> Boolean = { true }): Boolean {
        if (!allowed() || index !in songs.indices || songs.size > 1000 ||
            (expectedQueue != null && now.queue != expectedQueue)) return false
        setPlaybackMode(mode)
        play(songs, index, positionMs)
        if (!playing && now.playing) toggle()
        return true
    }
    fun seekTo(positionMs: Long) {
        val current = now
        if (current.song != null) play(current.queue, current.index, positionMs)
    }
    fun durationMs(): Long = (now.song?.durationSeconds ?: 0L) * 1000
}

/** The whole app. [backHandler] hooks up the system back button on platforms that have one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    player: MusicPlayer?,
    dataDir: File,
    deviceName: String,
    onAccountChanged: (String?) -> Unit = {},
    themeChrome: @Composable () -> Unit = {},
    foreground: Boolean = true,
    pollChatInBackground: Boolean = false,
    onChatNotification: (ChatAlert) -> Unit = {},
    onChatRead: (String) -> Unit = {},
    notificationPeerId: String? = null,
    onNotificationOpened: () -> Unit = {},
    isTelevision: Boolean = false,
    managePlaybackConnect: Boolean = true,
    onRemotePlaybackReady: (PlaybackRemoteState, String) -> Unit = { _, _ -> },
    videoSurface: (@Composable (Song, Modifier) -> Unit)? = null,
    backHandler: @Composable (onBack: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    remember(dataDir) { Downloads.init(dataDir) }
    var downloadsOpen by remember { mutableStateOf(false) }
    val library = remember(dataDir) { Library(File(dataDir, "library.json")) }
    val sync = remember(dataDir, deviceName, library) { Sync(dataDir, deviceName, library, scope) }
    LaunchedEffect(sync.userId) {
        player?.setSleepDeadline(null)
        player?.setStopAfterCurrent(false)
        onAccountChanged(sync.userId)
    }
    if (player != null && managePlaybackConnect) {
        val connect = remember(sync, player, deviceName) { PlaybackConnect(sync, player, deviceName) }
        // Keep remote controls available while the desktop window is minimized.
        LaunchedEffect(connect, sync.userId) {
            while (true) {
                connect.tick()
                delay(connect.pollDelayMs)
            }
        }
    }
    val remote = remember(sync) { PlaybackRemoteState(sync) }
    val ecosystem = remember(sync, remote) { EcosystemState(sync, remote) }
    val listening = remember(sync, dataDir) { ListeningLibraryState(sync, dataDir, scope, deviceName) }
    val timer = remember(sync, player) { SleepTimerState(sync, player) }
    val party = remember(sync) { PartyState(sync) }
    SideEffect { onRemotePlaybackReady(remote, sync.deviceId) }
    var devicesOpen by remember { mutableStateOf(false) }
    var sharedQueueOpen by remember { mutableStateOf(false) }
    var listeningOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var partyOpen by remember { mutableStateOf(false) }
    var transitionsOpen by remember { mutableStateOf(false) }
    val dialogPreferences = remember(listeningOpen, sharedQueueOpen, devicesOpen, partyOpen) {
        RegionPreferences(File(dataDir, "listening-preferences.json")).settings
    }
    LaunchedEffect(listening, sync.userId, foreground) {
        listening.resetForAccount()
        if (!foreground) return@LaunchedEffect
        while (true) { listening.refresh(); delay(15_000) }
    }
    LaunchedEffect(timer, sync.userId, foreground) {
        timer.resetForAccount()
        if (!foreground) return@LaunchedEffect
        while (true) { timer.refresh(); delay(3_000) }
    }
    LaunchedEffect(party, sync.userId, foreground) {
        party.resetForAccount()
        if (!foreground || sync.userId == null) return@LaunchedEffect
        while (true) { party.refresh(); delay(5_000) }
    }
    if (player != null && managePlaybackConnect) LaunchedEffect(listening, player, sync.userId) {
        while (true) { listening.recordPlayback(player.now, player.positionMs()); delay(1_000) }
    }
    LaunchedEffect(remote, sync.userId, foreground) {
        remote.resetForAccount()
        if (!foreground || sync.userId == null) return@LaunchedEffect
        while (true) {
            remote.refresh()
            delay(remote.pollDelayMs)
        }
    }
    LaunchedEffect(ecosystem, sync.userId, foreground) {
        ecosystem.resetForAccount()
        if (!foreground || sync.userId == null) return@LaunchedEffect
        while (true) { ecosystem.refresh(); delay(ecosystem.pollDelayMs) }
    }
    val themes = remember(dataDir, sync) { UserThemeState(dataDir, sync, scope) }
    val social = remember(sync) { SocialState(sync, scope) }
    val inbox = remember(sync, dataDir) { ChatInbox(sync, dataDir) }
    // Whether the music-video panel is showing. Only offered where the platform can draw the picture.
    val videoOpen = remember { mutableStateOf(false) }
    TroitTheme(themes.settings) {
        themeChrome()
        CompositionLocalProvider(LocalAppActive provides foreground, LocalPlaybackRemote provides remote,
            LocalOpenDevices provides { devicesOpen = true }, LocalEcosystem provides ecosystem,
            LocalOpenSharedQueue provides { sharedQueueOpen = true }, LocalListeningLibrary provides listening,
            LocalOpenListeningLibrary provides { listeningOpen = true }, LocalOpenLyrics provides { lyricsOpen = true },
            LocalOpenSleepTimer provides { timerOpen = true }, LocalOpenParty provides { partyOpen = true },
            LocalOpenTransitions provides { transitionsOpen = true }, LocalListeningPreferences provides dialogPreferences,
            LocalOpenDownloads provides { downloadsOpen = true }, LocalVideoSurface provides videoSurface, LocalVideoOpen provides videoOpen.takeIf { videoSurface != null }) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppContent(player, dataDir, deviceName, library, sync, themes, social, inbox, foreground,
                    pollChatInBackground, onChatNotification, onChatRead, notificationPeerId, onNotificationOpened,
                    isTelevision, managePlaybackConnect, backHandler)
            }
            if (devicesOpen) {
                val preferences = remember { RegionPreferences(File(dataDir, "listening-preferences.json")).settings }
                ConnectedDevicesDialog(sync, remote, player, deviceName,
                    isTelevision = isTelevision, preferences = preferences, onDismiss = { devicesOpen = false })
            }
            if (sharedQueueOpen) SharedQueueDialog(ecosystem, isTelevision) { sharedQueueOpen = false }
            val playFiltered: Play = { songs, index ->
                val settings = RegionPreferences(File(dataDir, "listening-preferences.json")).settings
                settings.selectPlayback(songs, index)?.let { player?.play(it.songs, it.index) }
            }
            if (listeningOpen) ListeningLibraryDialog(onPlay = playFiltered, television = isTelevision) { listeningOpen = false }
            if (downloadsOpen) DownloadsDialog(onPlay = playFiltered) { downloadsOpen = false }
            if (lyricsOpen) {
                val connected = remote.snapshot?.takeIf { player?.now?.playing != true && it.deviceId != sync.deviceId }
                val song = connected?.song ?: player?.now?.song
                if (song != null) LyricsDialog(song, positionMs = { if (connected != null) remote.positionMs() else player?.positionMs() ?: 0 },
                    television = isTelevision) { lyricsOpen = false }
                else AlertDialog(onDismissRequest = { lyricsOpen = false }, title = { Text("Lyrics") },
                    text = { Text("Play a song on a connected device to see its lyrics.") },
                    confirmButton = { TextButton(onClick = { lyricsOpen = false }) { Text("Done") } })
            }
            if (timerOpen) SleepTimerDialog(timer) { timerOpen = false }
            if (partyOpen) {
                if (sync.userId != null) PartyDialog(party) { partyOpen = false }
                else AlertDialog(onDismissRequest = { partyOpen = false }, title = { Text("Sign in for party queues") },
                    text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { AccountCard(sync) } },
                    confirmButton = { TextButton(onClick = { partyOpen = false }) { Text("Done") } })
            }
            if (transitionsOpen && player != null) TransitionSettingsDialog(player) { transitionsOpen = false }
        }
    }
}

@Composable
private fun AppContent(
    player: MusicPlayer?, dataDir: File, deviceName: String,
    library: Library, sync: Sync, themes: UserThemeState, social: SocialState, inbox: ChatInbox, foreground: Boolean,
    pollChatInBackground: Boolean, onChatNotification: (ChatAlert) -> Unit,
    onChatRead: (String) -> Unit,
    notificationPeerId: String?, onNotificationOpened: () -> Unit,
    isTelevision: Boolean, managePlaybackConnect: Boolean,
    backHandler: @Composable (onBack: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val preferences = remember { RegionPreferences(File(dataDir, "listening-preferences.json")) }
    val settings = preferences.settings
    var tab by remember { mutableIntStateOf(0) }
    // Pages opened on top of the tabs: album -> artist -> another album, and so on.
    val pages = remember { mutableStateListOf<Item>() }
    // Held here so Home and Search keep their content while a page is open or the other tab is showing.
    val home = remember(settings) { Loader(scope, Music.homeFeed(settings)) }
    DisposableEffect(home) { onDispose { home.reset() } }
    val homeScroll = rememberLazyListState()
    val search = remember { SearchState(scope) }
    var focusSearch by remember { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    val spotifyAccount = remember(dataDir) { SpotifyAccount(dataDir) }
    val spotifyConnection = remember(spotifyAccount, scope) { SpotifyConnection(spotifyAccount, scope) }
    val import = remember(library, spotifyAccount) { ImportState(scope, library, spotifyAccount) }
    val collaborations = remember(sync, library, scope) { CollaborativePlaylists(sync, scope, library) }
    LaunchedEffect(collaborations, sync.userId, foreground) {
        collaborations.resetForAccount()
        if (sync.userId == null || !foreground) return@LaunchedEffect
        while (true) {
            collaborations.refresh()
            delay(collaborations.pollDelayMs)
        }
    }
    var accountOpen by remember { mutableStateOf(false) }
    var chatAlert by remember(sync.userId) { mutableStateOf<ChatAlert?>(null) }
    var requestedChat by remember(sync.userId) { mutableStateOf<String?>(null) }
    val activePeer = if (foreground && tab == 4 && pages.isEmpty()) social.selectedFriend?.peer?.id else null
    val liveActivePeer by rememberUpdatedState(activePeer)
    val notifyChat by rememberUpdatedState(onChatNotification)
    LaunchedEffect(inbox, sync.userId, foreground) {
        inbox.resetForAccount()
        social.resetForAccount()
        if (sync.userId == null || (!foreground && !pollChatInBackground)) return@LaunchedEffect
        var failures = 0
        while (true) {
            try {
                val alerts = inbox.poll(liveActivePeer)
                for (alert in alerts) {
                    if (alert.message.recipientId != sync.userId) continue
                    if (alert.peer.id == liveActivePeer) { inbox.markRead(alert.peer.id); continue }
                    chatAlert = alert
                    notifyChat(alert)
                }
                failures = 0
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failures = (failures + 1).coerceAtMost(4) }
            delay(if (failures == 0) 5_000L else (5_000L shl failures).coerceAtMost(60_000L))
        }
    }
    LaunchedEffect(activePeer, sync.userId) {
        activePeer?.let { peer ->
            inbox.markRead(peer)
            onChatRead(peer)
            if (chatAlert?.peer?.id == peer) chatAlert = null
        }
    }
    LaunchedEffect(chatAlert) { if (chatAlert != null) { delay(8_000); chatAlert = null } }
    LaunchedEffect(notificationPeerId) {
        if (notificationPeerId != null) requestedChat = notificationPeerId
    }
    LaunchedEffect(requestedChat, sync.userId) {
        val peer = requestedChat ?: return@LaunchedEffect
        if (sync.userId != null) {
            pages.clear(); tab = 4
            social.refresh(forceSnapshot = true, waitForRead = true)
            if (peer.isEmpty()) social.closeChat()
            else social.friends.firstOrNull { it.peer.id == peer }?.let { social.openChat(it) }
        }
        requestedChat = null
        onNotificationOpened()
    }

    if (accountOpen) AlertDialog(
        onDismissRequest = { accountOpen = false },
        title = { Text("Your Troit account") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { AccountCard(sync) } },
        confirmButton = { TextButton(onClick = { accountOpen = false }) { Text("Done") } },
    )

    LaunchedEffect(settings) { search.updatePreferences(settings) }

    LaunchedEffect(sync, foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) {
            sync.syncNow()
            delay(30_000)
        }
    }
    LaunchedEffect(sync.userId, foreground) {
        if (!foreground) return@LaunchedEffect
        while (true) { themes.refresh(); delay(30_000) }
    }
    if (player != null && managePlaybackConnect) {
        // Restored paused queues must not replace another device's current song.
        LaunchedEffect(player) {
            val reports = PlaybackReportPolicy()
            while (true) {
                val now = player.now
                if (reports.shouldReport(now, System.currentTimeMillis())) {
                    sync.pushPlayback(now, player.positionMs())
                }
                delay(2_000)
            }
        }
    }

    if (isTelevision) {
        TvAppContent(player, dataDir, deviceName, library, sync, LocalOpenDevices.current, backHandler)
        return
    }

    // The queue is whatever list the song was tapped in, starting from that song.
    fun play(songs: List<Song>, index: Int) {
        val selected = settings.selectPlayback(songs, index) ?: return
        player?.play(selected.songs, selected.index)
    }

    var saving by remember { mutableStateOf<SaveRequest?>(null) }
    var saveMessage by remember { mutableStateOf("") }
    // A failed download is reported where saves are, not only inside the Downloads list.
    LaunchedEffect(Downloads.error) { Downloads.error?.let { saveMessage = it } }
    LaunchedEffect(saveMessage) {
        if (saveMessage.isNotEmpty()) {
            delay(4_000)
            saveMessage = ""
        }
    }
    saving?.let { request ->
        SaveDialog(request, library) { message ->
            saving = null
            if (message != null) saveMessage = message
        }
    }

    CompositionLocalProvider(
        LocalSave provides { saving = it },
        LocalListeningPreferences provides settings,
        LocalRegionPreferences provides preferences,
        LocalOpenPreferences provides { pages.clear(); tab = 3 },
        LocalUnreadChatPeers provides inbox.unreadPeerIds,
        LocalSpotifyConnection provides spotifyConnection,
        LocalCollaborativePlaylists provides collaborations,
        LocalSocialState provides social,
    ) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 900.dp
        val focusedChat = maxWidth < 720.dp && tab == 4 && pages.isEmpty() && social.selectedFriend != null
        // The picture follows whatever is playing here; with nothing playing there is nothing to show.
        val videoSong = player?.now?.song?.takeIf { LocalVideoOpen.current?.value == true }
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            if (wide) {
                Column(Modifier.width(224.dp).fillMaxHeight().padding(20.dp)) {
                    Row(Modifier.padding(top = 12.dp, bottom = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            TuneIcon("music", Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimary)
                        }
                        Text("Troit", Modifier.padding(start = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                        Text(" /", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp)
                    }
                    NavigationLinks(tab, vertical = true) { tab = it; pages.clear() }
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (library.playlists.isNotEmpty()) Text("YOUR PLAYLISTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
                        library.playlists.take(4).forEach { playlist ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pages.add(playlist) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(playlist.artwork, Modifier.size(30.dp))
                                Text(playlist.title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        Column(Modifier.weight(1f).padding(top = if (wide) 12.dp else 0.dp, end = if (wide) 12.dp else 0.dp)
            .clip(RoundedCornerShape(if (wide) 24.dp else 0.dp))
            .then(if (wide) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surface)) {
            if (!focusedChat) Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 4.dp)) {
                Text(listOf("Discover", "Search", "Library", "Settings", "Friends", "Appearance")[tab],
                    Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = LocalOpenDevices.current, modifier = Modifier.semantics { contentDescription = "Devices and playback" }) {
                    TuneIcon("devices", Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Devices")
                }
                ListeningFeaturesMenu(onAppearance = { tab = 5; pages.clear() })
            }
        Box(Modifier.weight(1f)) {
            val page = pages.lastOrNull()
            val back = { pages.removeAt(pages.lastIndex); Unit }
            val open = { item: Item -> pages.add(item); Unit }
            if (page != null) {
                backHandler(back)
                // key: a page opened from a page must not inherit the previous one's scroll position
                key(pages.size) {
                    screenStates.SaveableStateProvider("page:${pages.size}:${page.listIdentity()}") {
                    if (page is CollaborativePlaylist) {
                        CollaborativePlaylistScreen(collaborations, page.id, ::play, back)
                    } else if (page is Playlist || page is SavedPlaylist) {
                        PlaylistScreen(page, library, ::play, back, onCollaborativeCreated = { shared ->
                            pages[pages.lastIndex] = shared
                        })
                    } else {
                        ShelfPage(page, ::play, open, back)
                    }
                    }
                }
            } else {
                screenStates.SaveableStateProvider("tab:$tab") {
                    when (tab) {
                        0 -> HomeScreen(home, homeScroll, ::play, open) { query ->
                            focusSearch = query.isEmpty()
                            if (query.isNotEmpty()) {
                                search.filter = Yt.SONGS
                                search.updateQuery(query)
                                search.run()
                            }
                            tab = 1
                        }
                        1 -> SearchScreen(search, ::play, open, focusSearch) { focusSearch = false }
                        2 -> CollectionScreen(library, import, sync, open)
                        3 -> Column(Modifier.fillMaxSize()) {
                            TextButton(onClick = { tab = 5 }, modifier = Modifier.padding(horizontal = 16.dp)) {
                                TuneIcon("theme", Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Appearance")
                            }
                            Box(Modifier.weight(1f)) { TasteScreen(preferences) }
                        }
                        4 -> {
                            if (social.selectedFriend != null) backHandler(social::closeChat)
                            FriendsScreen(social, sync, nowPlaying = player?.now?.song, onPlay = ::play, onOpen = open) { accountOpen = true }
                        }
                        else -> {
                            backHandler { tab = 3 }
                            ThemeScreen(themes) { accountOpen = true }
                        }
                    }
                }
            }
        }
        }
            if (wide && videoSong != null) VideoPanel(videoSong, compact = false)
        }
        if (saveMessage.isNotEmpty()) {
            Text(saveMessage, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (!wide && !focusedChat && videoSong != null) VideoPanel(videoSong, compact = true)
        if (player != null && !focusedChat) {
            val remote = LocalPlaybackRemote.current
            val connected = remote?.snapshot?.takeIf { it.deviceId != sync.deviceId }
            if (connected != null && !player.now.playing && (connected.isFresh() || player.now.song == null)) {
                ConnectedPlayerBar(sync, remote, player, deviceName)
            } else PlayerBar(player, sync.handoff, sync::dismissHandoff)
        }
        if (!wide && !focusedChat) NavigationLinks(tab, vertical = false) { tab = it; pages.clear() }
        }
        chatAlert?.let { alert ->
            ChatNotificationBanner(alert, onOpen = { requestedChat = alert.peer.id; chatAlert = null },
                onDismiss = { chatAlert = null }, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
    }
    }
}

@Composable
private fun NavigationLinks(selected: Int, vertical: Boolean, onSelect: (Int) -> Unit) {
    val unreadCount = LocalUnreadChatPeers.current.size
    val invitationCount = LocalCollaborativePlaylists.current?.invites?.size ?: 0
    fun badgeCount(index: Int) = when (index) { 4 -> unreadCount; 2 -> invitationCount; else -> 0 }
    val links = listOf(Triple(0, "Discover", "home"), Triple(1, "Search", "search"), Triple(2, "Library", "library"), Triple(4, "Friends", "friends"), Triple(3, "Settings", "settings"))
    if (vertical) Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { (index, label, icon) ->
            val active = selected == index || (selected == 5 && index == 3)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TuneIcon(icon, Modifier.size(21.dp), if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (badgeCount(index) > 0) Badge { Text(badgeCount(index).toString()) }
            }
        }
    } else NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        links.forEach { (index, label, icon) -> NavigationBarItem(selected = selected == index || (selected == 5 && index == 3), onClick = { onSelect(index) }, icon = {
            BadgedBox(badge = { if (badgeCount(index) > 0) Badge { Text(badgeCount(index).toString()) } }) { TuneIcon(icon, Modifier.size(21.dp)) }
        }, label = { Text(label) }) }
    }
}

