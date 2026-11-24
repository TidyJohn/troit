package app.tunes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Immutable
data class SocialProfile(val id: String, val handle: String, val displayName: String)

@Immutable
data class SocialConnection(val id: String, val peer: SocialProfile, val status: String, val requestedBy: String)

@Immutable
data class ChatMessage(
    val id: String,
    val sequence: Long,
    val senderId: String,
    val recipientId: String,
    val text: String? = null,
    val stickerId: String? = null,
    val createdAt: String = "",
    val pending: Boolean = false,
    val failed: Boolean = false,
)

typealias SocialTransport = suspend (path: String, method: String, body: String?) -> String

/** Shared validation and bounded reconciliation are also used by fake-transport regression tests. */
internal object SocialRules {
    const val PAGE_SIZE = 50
    const val MAX_MESSAGES = 300
    const val MAX_FAILED = 20
    private val handlePattern = Regex("^[a-z0-9_]{3,24}$")

    fun handle(value: String): String = value.trim().lowercase(Locale.ROOT).also {
        require(handlePattern.matches(it)) { "Use 3–24 letters, numbers, or underscores for your handle." }
    }

    fun displayName(value: String): String = value.trim().also {
        require(it.length in 1..40) { "Use a display name between 1 and 40 characters." }
    }

    fun text(value: String): String = value.trim().also {
        require(it.length in 1..2000) { "Messages must contain 1–2000 characters." }
    }

    /** Server-confirmed copies replace local pending copies with the same UUID. */
    fun mergeMessages(existing: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        val unique = LinkedHashMap<String, ChatMessage>(existing.size + incoming.size)
        existing.forEach { unique[it.id] = it }
        incoming.forEach { unique[it.id] = it }
        val pending = unique.values.filter { it.pending || it.failed }.takeLast(MAX_FAILED)
        val confirmed = unique.values.filterNot { it.pending || it.failed }.sortedBy { it.sequence }
            .takeLast(MAX_MESSAGES - pending.size)
        return confirmed + pending
    }

    fun decodeProfile(json: JSONObject) = SocialProfile(json.getString("id"), json.getString("handle"), json.getString("display_name"))

    fun decodeMessage(json: JSONObject): ChatMessage = ChatMessage(
        id = json.getString("id"), sequence = json.getLong("seq").also { require(it > 0) },
        senderId = json.getString("sender_id"), recipientId = json.getString("recipient_id"),
        text = if (json.isNull("text")) null else json.getString("text"),
        stickerId = if (json.isNull("sticker_id")) null else json.getString("sticker_id"),
        createdAt = json.getString("created_at"),
    )
}

/**
 * Account-isolated, bounded conversation state. The visible Friends screen loads full messages;
 * the separate ChatInbox checks incoming messages across the app.
 */
class SocialState(
    private val sync: Sync,
    private val scope: CoroutineScope,
    private val request: SocialTransport = { path, method, body -> sync.accountRequest(path, method, body) },
    private val accountId: () -> String? = { sync.userId },
) {
    val userId get() = accountId()
    private var owner: String? = userId
    private var _profile by mutableStateOf<SocialProfile?>(null)
    private var _connections by mutableStateOf<List<SocialConnection>>(emptyList())
    private var _blocked by mutableStateOf<List<SocialProfile>>(emptyList())
    private var _selected by mutableStateOf<SocialConnection?>(null)
    private var _messages by mutableStateOf<List<ChatMessage>>(emptyList())
    val profile get() = _profile.takeIf { owner == userId }
    val profileReady get() = owner == userId && snapshotLoaded
    val connections get() = if (owner == userId) _connections else emptyList()
    val blocked get() = if (owner == userId) _blocked else emptyList()
    val selectedFriend get() = _selected.takeIf { owner == userId }
    val messages get() = if (owner == userId) _messages else emptyList()
    private val acceptedConnections = derivedStateOf { _connections.filter { it.status == "accepted" } }
    private val incomingConnections = derivedStateOf { _connections.filter { it.status == "pending" && it.requestedBy != userId } }
    private val outgoingConnections = derivedStateOf { _connections.filter { it.status == "pending" && it.requestedBy == userId } }
    val friends get() = if (owner == userId) acceptedConnections.value else emptyList()
    val incoming get() = if (owner == userId) incomingConnections.value else emptyList()
    val outgoing get() = if (owner == userId) outgoingConnections.value else emptyList()
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var hasOlder by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var setupRequired by mutableStateOf(false)
        private set
    /** What accepted friends are playing right now, by account id. Stays empty until the server supports it. */
    var activity by mutableStateOf<Map<String, Song>>(emptyMap())
        private set
    var sharingActivity by mutableStateOf(true)
        private set
    var activityAvailable by mutableStateOf(false)
        private set
    private val reads = Mutex()
    private var lastSnapshotAt = 0L
    private var snapshotLoaded = false
    private var failureCount = 0
    private var idlePolls = 0
    private val failedByPeer = LinkedHashMap<String, List<ChatMessage>>()
    val pollDelayMs: Long get() = when {
        setupRequired -> 60_000L
        failureCount > 0 -> (5_000L shl failureCount.coerceAtMost(4)).coerceAtMost(60_000L)
        selectedFriend == null -> 15_000L
        idlePolls >= 8 -> 12_000L
        idlePolls >= 3 -> 6_000L
        else -> 3_000L
    }

    private fun current(account: String) = owner == account && userId == account

    /** Call on sign-in/out as well as before each operation. No old account data remains visible. */
    fun resetForAccount() {
        val account = userId
        if (owner == account) return
        owner = account
        _profile = null; _connections = emptyList(); _blocked = emptyList()
        _selected = null; _messages = emptyList(); failedByPeer.clear()
        loading = false; busy = false; sending = false; loadingOlder = false; hasOlder = false
        error = null; setupRequired = false; lastSnapshotAt = 0; snapshotLoaded = false
        failureCount = 0; idlePolls = 0
        activity = emptyMap(); sharingActivity = true; activityAvailable = false
    }

    /** Optional server feature: without its migration, friends and chat carry on and nothing is shown. */
    private suspend fun refreshActivity(account: String) {
        try {
            val json = JSONObject(rpc("troit_friend_activity"))
            val rows = json.optJSONArray("items") ?: JSONArray()
            require(rows.length() <= 400) { "Unexpected activity size." }
            val playing = (0 until rows.length()).mapNotNull { index ->
                val row = rows.getJSONObject(index)
                // Another account wrote these fields: only a real song link is ever handed to the player.
                val url = SongShare.songUrl(row.getString("track_url")) ?: return@mapNotNull null
                row.getString("user_id") to Song(row.getString("title").take(500), row.optString("artist").take(500), url,
                    row.optString("artwork").takeIf { it.startsWith("https://") && it.length <= 4096 },
                    row.optLong("duration_seconds").coerceIn(0, 604800))
            }.toMap()
            if (!current(account)) return
            activity = playing; sharingActivity = json.optBoolean("sharing", true); activityAvailable = true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (current(account)) activity = emptyMap() }
    }

    fun setActivitySharing(enabled: Boolean) = mutate {
        rpc("troit_friend_activity_share", JSONObject().put("p_enabled", enabled))
    }

    /** Sends outside the open conversation, such as a song shared from a list. True once the server has it. */
    suspend fun sendTo(connection: SocialConnection, text: String): Boolean {
        resetForAccount()
        val account = userId ?: return false
        val normalized = runCatching { SocialRules.text(text) }.getOrElse { error = it.message; return false }
        return try {
            val response = rpc("troit_chat_send", JSONObject().put("p_peer_id", connection.peer.id)
                .put("p_message_id", UUID.randomUUID().toString()).put("p_text", normalized).put("p_sticker_id", JSONObject.NULL))
            val confirmed = SocialRules.decodeMessage(JSONObject(response))
            if (current(account) && _selected?.id == connection.id) _messages = SocialRules.mergeMessages(_messages, listOf(confirmed))
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(account)) fail(failure); false }
    }

    private suspend fun rpc(name: String, body: JSONObject = JSONObject()) = request("rpc/$name", "POST", body.toString())

    /** One bounded refresh. Parallel calls are skipped rather than queued behind a slow network. */
    suspend fun refresh(forceSnapshot: Boolean = false, waitForRead: Boolean = false) {
        resetForAccount()
        val account = userId ?: return
        if (waitForRead) reads.lock() else if (!reads.tryLock()) return
        if (!snapshotLoaded) loading = true
        try {
            if (forceSnapshot || !snapshotLoaded || System.currentTimeMillis() - lastSnapshotAt >= 15_000) {
                val response = rpc("troit_social_snapshot")
                val snapshot = withContext(Dispatchers.Default) { decodeSnapshot(response) }
                if (!current(account)) return
                _profile = snapshot.first; _connections = snapshot.second; _blocked = snapshot.third
                snapshotLoaded = true; lastSnapshotAt = System.currentTimeMillis()
                _selected?.let { selected ->
                    val retained = _connections.firstOrNull { it.id == selected.id && it.status == "accepted" }
                    if (retained == null) { _selected = null; _messages = emptyList(); hasOlder = false }
                    else _selected = retained
                }
                refreshActivity(account)
                if (!current(account)) return
            }
            val selected = _selected
            if (selected != null) {
                val latest = _messages.filterNot { it.pending || it.failed }.maxOfOrNull { it.sequence }
                val page = fetchMessages(selected.peer.id, after = latest)
                if (!current(account) || _selected?.id != selected.id) return
                idlePolls = if (page.isEmpty()) (idlePolls + 1).coerceAtMost(20) else 0
                _messages = SocialRules.mergeMessages(_messages, page)
                rememberFailures(selected.peer.id)
                if (latest == null) hasOlder = page.size == SocialRules.PAGE_SIZE
                if (_messages.size >= SocialRules.MAX_MESSAGES) hasOlder = false
            }
            if (current(account)) { setupRequired = false; failureCount = 0; error = null }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(account)) fail(failure)
        } finally {
            if (current(account)) loading = false
            reads.unlock()
        }
    }

    fun retry() { lastSnapshotAt = 0; scope.launch { refresh() } }

    fun saveProfile(handle: String, displayName: String) {
        val normalized = runCatching { SocialRules.handle(handle) to SocialRules.displayName(displayName) }
            .getOrElse { error = it.message; return }
        mutate {
            rpc("troit_profile_save", JSONObject().put("p_handle", normalized.first).put("p_display_name", normalized.second))
        }
    }

    fun requestFriend(handle: String) {
        val normalized = runCatching { SocialRules.handle(handle) }.getOrElse { error = it.message; return }
        if (normalized == profile?.handle) { error = "Enter another user's handle."; return }
        mutate { rpc("troit_friend_request", JSONObject().put("p_handle", normalized)) }
    }

    fun accept(connection: SocialConnection) = friendAction(connection, "accept")
    fun decline(connection: SocialConnection) = friendAction(connection, "decline")
    fun remove(connection: SocialConnection) = friendAction(connection, "remove")
    fun block(connection: SocialConnection) = friendAction(connection, "block")
    fun unblock(peerId: String) = mutate { rpc("troit_friend_unblock", JSONObject().put("p_peer_id", peerId)) }
    private fun friendAction(connection: SocialConnection, action: String) = mutate {
        rpc("troit_friend_action", JSONObject().put("p_connection_id", connection.id).put("p_action", action))
    }

    private fun mutate(action: suspend () -> Unit) {
        resetForAccount()
        val account = userId ?: run { error = "Sign in to use friends and chat."; return }
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                action()
                if (current(account)) { lastSnapshotAt = 0; refresh() }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { if (current(account)) fail(failure)
            } finally { if (current(account)) busy = false }
        }
    }

    fun openChat(connection: SocialConnection) {
        resetForAccount()
        if (connection.status != "accepted" || connections.none { it.id == connection.id && it.status == "accepted" }) {
            error = "Accept the friend request before chatting."; return
        }
        if (_selected?.id == connection.id) return
        _selected = connection
        _messages = failedByPeer[connection.peer.id].orEmpty()
        hasOlder = false; idlePolls = 0; error = null
        scope.launch { refresh() }
    }

    fun closeChat() {
        _selected?.let { rememberFailures(it.peer.id) }
        _selected = null; _messages = emptyList(); hasOlder = false; error = null
    }

    fun sendText(text: String) {
        val normalized = runCatching { SocialRules.text(text) }.getOrElse { error = it.message; return }
        enqueue(text = normalized)
    }

    fun sendSticker(id: String) {
        if (id !in StickerCatalog.ids) { error = "This sticker isn't supported. Update Troit to use it."; return }
        enqueue(stickerId = id)
    }

    private fun enqueue(text: String? = null, stickerId: String? = null) {
        resetForAccount()
        val account = userId ?: run { error = "Sign in to send a message."; return }
        val selected = _selected ?: return
        if (sending) return
        if (_messages.count { it.pending || it.failed } >= SocialRules.MAX_FAILED) {
            error = "Retry your unsent messages before sending more."; return
        }
        val message = ChatMessage(UUID.randomUUID().toString(), 0, account, selected.peer.id, text, stickerId,
            Instant.now().toString(), pending = true)
        _messages = SocialRules.mergeMessages(_messages, listOf(message))
        if (_messages.size >= SocialRules.MAX_MESSAGES) hasOlder = false
        send(message, account, selected.id)
    }

    fun retrySend(message: ChatMessage) {
        val account = userId ?: return
        val selected = _selected ?: return
        if (sending || message.senderId != account || !message.failed || selected.peer.id != message.recipientId) return
        val retried = message.copy(failed = false, pending = true)
        _messages = SocialRules.mergeMessages(_messages, listOf(retried))
        send(retried, account, selected.id)
    }

    private fun send(message: ChatMessage, account: String, connectionId: String) {
        sending = true; error = null
        scope.launch {
            try {
                val body = JSONObject().put("p_peer_id", message.recipientId).put("p_message_id", message.id)
                    .put("p_text", message.text ?: JSONObject.NULL).put("p_sticker_id", message.stickerId ?: JSONObject.NULL)
                val response = rpc("troit_chat_send", body)
                val confirmed = withContext(Dispatchers.Default) { SocialRules.decodeMessage(JSONObject(response)) }
                if (current(account)) {
                    if (_selected?.id == connectionId) _messages = SocialRules.mergeMessages(_messages, listOf(confirmed))
                    failedByPeer[message.recipientId] = failedByPeer[message.recipientId].orEmpty().filterNot { it.id == message.id }
                    idlePolls = 0
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (current(account)) {
                    val failed = message.copy(pending = false, failed = true)
                    failedByPeer[message.recipientId] = (failedByPeer[message.recipientId].orEmpty().filterNot { it.id == message.id } + failed).takeLast(SocialRules.MAX_FAILED)
                    trimFailedConversations()
                    if (_selected?.id == connectionId) _messages = SocialRules.mergeMessages(_messages, listOf(failed))
                    fail(failure)
                }
            } finally { if (current(account)) sending = false }
        }
    }

    fun loadOlder() {
        val account = userId ?: return
        val selected = _selected ?: return
        val before = _messages.filterNot { it.pending || it.failed }.minOfOrNull { it.sequence } ?: return
        if (_messages.size >= SocialRules.MAX_MESSAGES) { hasOlder = false; return }
        if (loadingOlder || !hasOlder) return
        loadingOlder = true
        scope.launch {
            try {
                val page = fetchMessages(selected.peer.id, before = before)
                if (current(account) && _selected?.id == selected.id) {
                    _messages = SocialRules.mergeMessages(_messages, page)
                    hasOlder = page.size == SocialRules.PAGE_SIZE && _messages.size < SocialRules.MAX_MESSAGES
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { if (current(account)) fail(failure)
            } finally { if (current(account)) loadingOlder = false }
        }
    }

    private suspend fun fetchMessages(peerId: String, before: Long? = null, after: Long? = null): List<ChatMessage> {
        val body = JSONObject().put("p_peer_id", peerId).put("p_limit", SocialRules.PAGE_SIZE)
            .put("p_before", before ?: JSONObject.NULL).put("p_after", after ?: JSONObject.NULL)
        val response = rpc("troit_chat_messages", body)
        return withContext(Dispatchers.Default) {
            val rows = JSONArray(response)
            require(rows.length() <= SocialRules.PAGE_SIZE) { "Unexpected message page size." }
            (0 until rows.length()).map { SocialRules.decodeMessage(rows.getJSONObject(it)) }
        }
    }

    private fun rememberFailures(peerId: String) {
        failedByPeer[peerId] = _messages.filter { it.failed || it.pending }.takeLast(SocialRules.MAX_FAILED)
        trimFailedConversations()
    }

    private fun trimFailedConversations() {
        while (failedByPeer.size > 20) failedByPeer.remove(failedByPeer.keys.first())
    }

    private fun fail(failure: Exception) {
        failureCount = (failureCount + 1).coerceAtMost(4)
        setupRequired = failure is AccountApiException && failure.setupRequired
        error = if (setupRequired) "Friends and chat need the latest server update. Your music still works."
            else failure.message?.take(240) ?: "Couldn't connect. Please try again."
    }

    private fun decodeSnapshot(response: String): Triple<SocialProfile?, List<SocialConnection>, List<SocialProfile>> {
        val json = JSONObject(response)
        val connections = json.optJSONArray("connections") ?: JSONArray()
        val blocked = json.optJSONArray("blocked") ?: JSONArray()
        require(connections.length() <= 400 && blocked.length() <= 200) { "Unexpected friend-list size." }
        return Triple(
            json.optJSONObject("profile")?.let(SocialRules::decodeProfile),
            (0 until connections.length()).map { index ->
                val row = connections.getJSONObject(index)
                SocialConnection(row.getString("id"), SocialRules.decodeProfile(row.getJSONObject("peer")), row.getString("status"), row.getString("requested_by"))
            }.distinctBy { it.id },
            (0 until blocked.length()).map { SocialRules.decodeProfile(blocked.getJSONObject(it)) }.distinctBy { it.id },
        )
    }
}
