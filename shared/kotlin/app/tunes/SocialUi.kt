package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ChatNotificationBanner(alert: ChatAlert, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClickLabel = "Open message", onClick = onOpen).padding(4.dp)) {
                Text(alert.peer.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(alert.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onOpen) { Text("Open", color = MaterialTheme.colorScheme.inversePrimary) }
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Dismiss message notification" }) {
                Text("×", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Full conversations refresh while visible; app-wide message alerts use ChatInbox. */
@Composable
fun FriendsScreen(social: SocialState, sync: Sync, nowPlaying: Song? = null, onPlay: Play = { _, _ -> },
    onOpen: Open = {}, onOpenAccount: () -> Unit) {
    val appActive = LocalAppActive.current
    LaunchedEffect(social, sync.userId, appActive) {
        if (!appActive) return@LaunchedEffect
        if (social.userId == null) social.refresh()
        else while (isActive) {
            social.refresh()
            delay(social.pollDelayMs)
        }
    }
    var editingProfile by remember { mutableStateOf(false) }
    var addingFriend by remember { mutableStateOf(false) }
    var blockedUsers by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<FriendAction?>(null) }
    LaunchedEffect(social.userId) {
        editingProfile = false; addingFriend = false; blockedUsers = false; confirming = null
    }
    val profile = social.profile
    Column(Modifier.fillMaxSize()) {
        when {
            social.userId == null -> SocialEmpty(
                "Music is better with friends.",
                "Connect your Troit account to add friends, chat, and keep conversations on your phone and PC.",
                "Connect account", onOpenAccount,
            )
            social.setupRequired -> SocialEmpty(
                "Friends & chat need a server update",
                "This server is not ready for friends and messages yet. Your music and playlists are still available.",
                "Try again", social::retry,
            )
            profile == null && social.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            profile == null && !social.profileReady -> {
                if (social.error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
                else SocialEmpty("Couldn't load your profile", social.error ?: "Check your connection and try again.", "Try again", social::retry)
            }
            profile == null -> Column(Modifier.fillMaxSize()) {
                SocialError(social)
                key(social.userId) { ProfileSetup(null, social.busy, social::saveProfile) }
            }
            else -> {
                SocialError(social)
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 720.dp
                    if (wide) Row(Modifier.fillMaxSize()) {
                        Column(Modifier.width(292.dp).fillMaxHeight()) {
                            FriendsList(social, onPlay, onAdd = { addingFriend = true }, onEditProfile = { editingProfile = true }, onBlocked = { blockedUsers = true })
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f)) {
                            val selected = social.selectedFriend
                            if (selected == null) SocialEmpty("Pick up a conversation", "Choose a friend to share a thought, a laugh, or a sticker.")
                            else key(selected.id) { Conversation(social, selected, nowPlaying, onPlay, onOpen, onBack = null, onAction = { confirming = it }) }
                        }
                    } else {
                        val selected = social.selectedFriend
                        if (selected == null) FriendsList(social, onPlay, onAdd = { addingFriend = true }, onEditProfile = { editingProfile = true }, onBlocked = { blockedUsers = true })
                        else key(selected.id) { Conversation(social, selected, nowPlaying, onPlay, onOpen, social::closeChat) { confirming = it } }
                    }
                }
            }
        }
    }
    if (editingProfile && profile != null) AlertDialog(
        onDismissRequest = { if (!social.busy) editingProfile = false },
        title = { Text("Your profile") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { ProfileSetup(profile, social.busy, social::saveProfile, compact = true); SocialError(social) } },
        confirmButton = { TextButton(onClick = { editingProfile = false }, enabled = !social.busy) { Text("Done") } },
    )
    if (addingFriend) AddFriendDialog(social) { addingFriend = false }
    if (blockedUsers) BlockedUsersDialog(social, onUnblock = { confirming = FriendAction.Unblock(it) }) { blockedUsers = false }
    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(action.title) },
            text = { Text(action.explanation) },
            confirmButton = { TextButton(enabled = !social.busy, onClick = {
                when (action) {
                    is FriendAction.Remove -> social.remove(action.connection)
                    is FriendAction.Block -> social.block(action.connection)
                    is FriendAction.Unblock -> social.unblock(action.peer.id)
                }
                confirming = null
            }) { Text(action.confirm) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private sealed class FriendAction(val title: String, val explanation: String, val confirm: String) {
    class Remove(val connection: SocialConnection) : FriendAction("Remove @${connection.peer.handle}?", "You will need to reconnect before you can exchange messages again.", "Remove friend")
    class Block(val connection: SocialConnection) : FriendAction("Block @${connection.peer.handle}?", "This person will no longer be able to send you friend requests or messages. You can unblock them from your friends list.", "Block")
    class Unblock(val peer: SocialProfile) : FriendAction("Unblock @${peer.handle}?", "This person can send you a new friend request. Unblocking does not automatically make you friends again.", "Unblock")
}

@Composable
private fun SocialError(social: SocialState) {
    social.error?.takeIf { !social.setupRequired }?.let { error ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = social::retry, enabled = !social.loading && !social.busy) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun SocialEmpty(title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            TuneIcon("chat", Modifier.padding(22.dp).size(34.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) { Spacer(Modifier.height(20.dp)); Button(onClick = onAction) { Text(action) } }
    }
}

@Composable
private fun ProfileSetup(profile: SocialProfile?, busy: Boolean, onSave: (String, String) -> Unit, compact: Boolean = false) {
    var handle by rememberSaveable(profile?.id, profile?.handle) { mutableStateOf(profile?.handle.orEmpty()) }
    var name by rememberSaveable(profile?.id, profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
    val validHandle = handle.matches(ProfileHandle)
    val validName = name.trim().length in 1..40
    val scrolling = if (compact) Modifier else Modifier.verticalScroll(rememberScrollState())
    Column(Modifier.fillMaxWidth().then(scrolling).padding(if (compact) 0.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!compact) {
            Text("MAKE YOURSELF AT HOME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("What should your friends call you?", style = MaterialTheme.typography.headlineMedium)
            Text("Choose a public handle to share. Your account email stays private.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true, label = { Text("Display name") }, enabled = !busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = handle, onValueChange = { handle = it.removePrefix("@").take(24) }, singleLine = true, label = { Text("Your handle") }, prefix = { Text("@") }, enabled = !busy, shape = RoundedCornerShape(14.dp), supportingText = { Text("3–24 letters, numbers, or underscores") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(handle.lowercase(Locale.ROOT), name.trim()) }, enabled = validHandle && validName && !busy) {
            if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (profile == null) "Create profile" else "Save profile")
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun FriendsList(social: SocialState, onPlay: Play, onAdd: () -> Unit, onEditProfile: () -> Unit, onBlocked: () -> Unit) {
    val unreadPeers = LocalUnreadChatPeers.current
    val profile = social.profile ?: return
    var query by rememberSaveable(social.userId) { mutableStateOf("") }
    val friends = social.friends
    val incoming = social.incoming
    val outgoing = social.outgoing
    val visible = remember(friends, query) { friends.filter { it.peer.displayName.contains(query, true) || it.peer.handle.contains(query.removePrefix("@"), true) } }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2_000); copied = false } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "my-profile", contentType = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YOUR PEOPLE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Friends", style = MaterialTheme.typography.headlineLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile, Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { clipboard.setText(AnnotatedString("@${profile.handle}")); copied = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(28.dp)) { Text(if (copied) "Copied!" else "@${profile.handle}", style = MaterialTheme.typography.bodySmall) }
                    }
                    TextButton(onClick = onEditProfile) { Text("Edit") }
                }
                Button(onClick = onAdd, enabled = !social.busy, modifier = Modifier.fillMaxWidth()) { TuneIcon("plus", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add a friend") }
                if (social.activityAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show friends what I'm playing", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(social.sharingActivity, onCheckedChange = social::setActivitySharing, enabled = !social.busy)
                }
                if (friends.isNotEmpty()) OutlinedTextField(query, { query = it.take(50) }, placeholder = { Text("Find a friend") }, singleLine = true, leadingIcon = { TuneIcon("search", Modifier.size(18.dp)) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }
        }
        if (incoming.isNotEmpty()) {
            item(key = "incoming-heading", contentType = "section") { Text("Friend requests · ${incoming.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
            items(incoming, key = { "incoming:${it.id}" }, contentType = { "request" }) { connection ->
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(12.dp)) {
                        PeerLabel(connection.peer)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { social.accept(connection) }, enabled = !social.busy) { Text("Accept") }
                            TextButton(onClick = { social.decline(connection) }, enabled = !social.busy) { Text("Decline") }
                        }
                    }
                }
            }
        }
        item(key = "friends-heading", contentType = "section") { Text("Your friends · ${friends.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
        if (visible.isEmpty()) item(key = "friends-empty", contentType = "empty") {
            Text(if (friends.isEmpty()) "Share your handle to connect. Chats are available once a friend request is accepted." else "No friends match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        items(visible, key = { "friend:${it.id}" }, contentType = { "friend" }) { connection ->
            val selected = social.selectedFriend?.id == connection.id
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(onClickLabel = "Chat with ${connection.peer.displayName}") { social.openChat(connection) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(connection.peer, Modifier.size(40.dp))
                val playing = social.activity[connection.peer.id]
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    PeerLabel(connection.peer)
                    if (playing != null) Text("♪ ${playing.title} · ${playing.artist}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (connection.peer.id in unreadPeers) Badge(Modifier.padding(end = 8.dp)) { Text("New") }
                if (playing != null) IconButton(onClick = { onPlay(listOf(playing), 0) },
                    modifier = Modifier.semantics { contentDescription = "Play what ${connection.peer.displayName} is listening to" }) { TuneIcon("play", Modifier.size(18.dp)) }
                TuneIcon("chat", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (outgoing.isNotEmpty()) {
            item(key = "outgoing-heading", contentType = "section") { Text("Requests sent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
            items(outgoing, key = { "outgoing:${it.id}" }, contentType = { "request" }) { connection ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PeerLabel(connection.peer, Modifier.weight(1f))
                    Text("Pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { social.remove(connection) }, enabled = !social.busy) { Text("Cancel") }
                }
            }
        }
        item(key = "friends-settings", contentType = "footer") { TextButton(onClick = onBlocked) { Text("Blocked accounts${if (social.blocked.isEmpty()) "" else " · ${social.blocked.size}"}") } }
    }
}

@Composable
private fun ProfileAvatar(profile: SocialProfile, modifier: Modifier) {
    val initial = remember(profile.displayName, profile.handle) {
        val label = profile.displayName.trim().ifEmpty { profile.handle }.ifEmpty { "?" }
        String(Character.toChars(label.codePointAt(0))).uppercase(Locale.ROOT)
    }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable
private fun PeerLabel(peer: SocialProfile, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(peer.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("@${peer.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddFriendDialog(social: SocialState, onDismiss: () -> Unit) {
    var handle by rememberSaveable(social.userId) { mutableStateOf("") }
    val normalized = handle.removePrefix("@").lowercase(Locale.ROOT).trim()
    val existing = remember(social.connections, normalized) { social.connections.any { it.peer.handle.equals(normalized, true) } }
    val valid = normalized.matches(ProfileHandle) && normalized != social.profile?.handle && !existing
    AlertDialog(
        onDismissRequest = { if (!social.busy) onDismiss() },
        title = { Text("Find your people") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ask your friend for their Troit handle. They decide whether to accept your request.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(handle, { handle = it.removePrefix("@").take(24) }, singleLine = true, label = { Text("Friend's handle") }, prefix = { Text("@") }, enabled = !social.busy, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            if (existing) Text("You're already connected or have a request pending.", style = MaterialTheme.typography.bodySmall)
            SocialError(social)
        } },
        confirmButton = { TextButton(onClick = { social.requestFriend(normalized) }, enabled = valid && !social.busy) { Text(if (existing) "Request sent" else if (social.busy) "Sending…" else "Send request") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !social.busy) { Text("Done") } },
    )
}

@Composable
private fun BlockedUsersDialog(social: SocialState, onUnblock: (SocialProfile) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Blocked accounts") }, text = {
        Column {
            if (social.blocked.isEmpty()) Text("No blocked accounts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(social.blocked, key = { it.id }, contentType = { "blocked" }) { peer ->
                    Row(verticalAlignment = Alignment.CenterVertically) { PeerLabel(peer, Modifier.weight(1f)); TextButton(onClick = { onUnblock(peer) }, enabled = !social.busy) { Text("Unblock") } }
                }
            }
            SocialError(social)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun Conversation(social: SocialState, connection: SocialConnection, nowPlaying: Song?, onPlay: Play, onOpen: Open,
    onBack: (() -> Unit)?, onAction: (FriendAction) -> Unit) {
    val peer = connection.peer
    var menu by remember { mutableStateOf(false) }
    var stickers by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(social.userId, peer.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val messages = social.messages
    val newestFirst = remember(messages) { messages.asReversed() }
    val latest = messages.lastOrNull()
    val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset < 100 } }
    val focus = LocalFocusManager.current
    LaunchedEffect(latest?.id) {
        if (newestFirst.isNotEmpty() && (nearBottom || latest?.pending == true && latest.senderId == social.userId)) listState.scrollToItem(0)
    }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty() && !social.sending) { social.sendText(text); draft = ""; stickers = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to friends" }) { TuneIcon("back", Modifier.size(22.dp)) }
            ProfileAvatar(peer, Modifier.size(38.dp))
            PeerLabel(peer, Modifier.weight(1f).padding(start = 12.dp))
            Box {
                TextButton(onClick = { menu = true }) { Text("More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove friend") }, onClick = { menu = false; onAction(FriendAction.Remove(connection)) })
                    DropdownMenuItem(text = { Text("Block account") }, onClick = { menu = false; onAction(FriendAction.Block(connection)) })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (social.loading && messages.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (messages.isEmpty() && !social.loading) item(key = "empty-conversation", contentType = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    TuneIcon("chat", Modifier.size(30.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp)); Text("This is the start of something good.", style = MaterialTheme.typography.bodyMedium)
                    Text("Say hi or send a sticker.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(newestFirst, key = { it.id }, contentType = { if (it.stickerId == null) "text" else "sticker" }) { message ->
                MessageBubble(message, own = message.senderId == social.userId, retryEnabled = !social.sending, onPlay = onPlay, onOpen = onOpen, onRetry = { social.retrySend(message) })
            }
            if (social.hasOlder) item(key = "load-older", contentType = "paging") {
                TextButton(onClick = social::loadOlder, enabled = !social.loadingOlder, modifier = Modifier.fillMaxWidth()) { Text(if (social.loadingOlder) "Loading earlier messages…" else "Load earlier messages") }
            }
            else if (messages.size >= SocialRules.MAX_MESSAGES) item(key = "recent-history-limit", contentType = "paging") {
                Text("Showing the most recent ${SocialRules.MAX_MESSAGES} messages", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!nearBottom && messages.isNotEmpty()) TextButton(onClick = { scope.launch { listState.scrollToItem(0) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to latest") }
        if (stickers) StickerPicker(enabled = !social.sending, onPick = { social.sendSticker(it); stickers = false }, onDismiss = { stickers = false })
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { stickers = !stickers; focus.clearFocus() }, modifier = Modifier.semantics { contentDescription = if (stickers) "Hide stickers" else "Choose a sticker" }) { TuneIcon("sticker", Modifier.size(23.dp)) }
                    val share = remember(nowPlaying) { nowPlaying?.let(SongShare::text) }
                    if (share != null) IconButton(onClick = { social.sendText(share) }, enabled = !social.sending,
                        modifier = Modifier.semantics { contentDescription = "Send the song you're playing" }) { TuneIcon("music", Modifier.size(22.dp)) }
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(2_000) }, placeholder = { Text("Message @${peer.handle}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp), maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    FilledIconButton(onClick = ::send, enabled = draft.isNotBlank() && !social.sending, modifier = Modifier.size(48.dp).semantics { contentDescription = "Send message" }) {
                        if (social.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else TuneIcon("send", Modifier.size(21.dp))
                    }
                }
                if (draft.length > 1800) Text("${draft.length} / 2000", Modifier.align(Alignment.End).padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val ChatClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val ProfileHandle = Regex("[a-zA-Z0-9_]{3,24}")

@Composable
private fun MessageBubble(message: ChatMessage, own: Boolean, retryEnabled: Boolean, onPlay: Play, onOpen: Open, onRetry: () -> Unit) {
    val time = remember(message.createdAt) { runCatching { ChatClock.format(Instant.parse(message.createdAt)) }.getOrDefault("") }
    val shared = remember(message.text) { message.text?.let(SongShare::parse) }
    Row(Modifier.fillMaxWidth().padding(start = if (own) 32.dp else 0.dp, end = if (own) 0.dp else 32.dp), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            Surface(shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (own) 18.dp else 5.dp, bottomEnd = if (own) 5.dp else 18.dp),
                color = if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            ) {
                if (message.stickerId != null) Sticker(message.stickerId, Modifier.width(152.dp).padding(12.dp))
                else if (shared != null) Row(Modifier.clickable(onClickLabel = if (shared is Song) "Play ${shared.title}" else "Open ${shared.title}") {
                    if (shared is Song) onPlay(listOf(shared), 0) else onOpen(shared)
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(shared.artwork, Modifier.size(48.dp), RoundedCornerShape(8.dp))
                    Column(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
                        Text(shared.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (shared is Song) shared.artist else "Album or playlist", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TuneIcon(if (shared is Song) "play" else "arrow", Modifier.size(20.dp))
                }
                else Text(message.text.orEmpty(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
            when {
                message.failed -> TextButton(onClick = onRetry, enabled = retryEnabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) { Text("Not sent · Retry", style = MaterialTheme.typography.labelSmall) }
                message.pending -> Text("Sending…", Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                time.isNotEmpty() -> Text(time, Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Shares a song or album into a friend's chat from any list in the app. */
@Composable
internal fun SendToFriendDialog(item: Item, onDismiss: () -> Unit) {
    val social = LocalSocialState.current ?: return
    val text = remember(item) { SongShare.text(item) }
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(emptySet<String>()) }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(social) { social.refresh() } // the friend list is otherwise only loaded by the Friends tab
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Send to a friend") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                social.userId == null -> Text("Connect your Troit account in Friends to share music.")
                text == null -> Text("This can't be shared as a link.")
                social.friends.isEmpty() -> Text(if (social.loading) "Loading your friends…" else "Add a friend in the Friends tab first.")
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(social.friends, key = { it.id }) { friend ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PeerLabel(friend.peer, Modifier.weight(1f))
                            TextButton(enabled = !sending && friend.id !in sent, onClick = {
                                sending = true
                                scope.launch { if (social.sendTo(friend, text)) sent = sent + friend.id; sending = false }
                            }) { Text(if (friend.id in sent) "Sent" else "Send") }
                        }
                    }
                }
            }
            social.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerPicker(enabled: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var pack by rememberSaveable { mutableStateOf("Jomok") }
    val stickers = remember(pack) { StickerCatalog.entries.filter { it.pack == pack } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(pack == "Jomok", { pack = "Jomok" }, label = { Text("Jomok") })
                FilterChip(pack == "Jawir", { pack = "Jawir" }, label = { Text("Jawir") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Close sticker picker" }) { TuneIcon("close", Modifier.size(18.dp)) }
            }
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stickers, key = { it.id }, contentType = { "sticker" }) { sticker ->
                    Box(Modifier.width(104.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Send ${sticker.caption}") { onPick(sticker.id) }.padding(4.dp)) { Sticker(sticker.id, Modifier.fillMaxWidth(), animate = false) }
                }
            }
        }
    }
}
