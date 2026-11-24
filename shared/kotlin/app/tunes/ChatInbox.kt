package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ChatAlert(val peer: SocialProfile, val message: ChatMessage) {
    val preview: String get() = message.text?.take(180) ?: "Sent a sticker"
}

/** One bounded inbox read for every conversation, independent of the visible music screen.
 * Only cursors and unread peer IDs are stored; message previews are never written to disk.
 * Activity and Android workers share a lock and re-read the cursor before delivering an alert.
 */
class ChatInbox(
    private val sync: Sync,
    private val dataDir: File,
    private val request: SocialTransport = { path, method, body -> sync.accountRequest(path, method, body) },
    private val accountId: () -> String? = { sync.userId },
) {
    private data class InboxBatch(
        val alerts: List<ChatAlert>,
        val peerIds: Set<String>,
        val checkedPeerIds: Set<String> = peerIds,
        val baselinePerPeer: Boolean = false,
    )
    private var owner: String? = null
    private var unread by mutableStateOf<Set<String>>(emptySet())
    val unreadPeerIds: Set<String> get() = if (owner == accountId()) unread else emptySet()
    private var legacyServer = false
    private var legacyOffset = 0
    private val monitor = locks.getOrPut(dataDir.absoluteFile.normalize().path) { Mutex() }
    private val diskLock = diskLocks.getOrPut(dataDir.absoluteFile.normalize().path) { Any() }

    private fun file(account: String) = File(dataDir,
        "chat-inbox-${UUID.nameUUIDFromBytes(account.toByteArray(Charsets.UTF_8))}.json")
    private fun load(account: String): JSONObject = runCatching { JSONObject(file(account).readText()) }.getOrElse { JSONObject() }

    fun resetForAccount() {
        val account = accountId()
        if (owner == account) return
        owner = account
        unread = if (account == null) emptySet() else synchronized(diskLock) { unread(load(account)) }
        legacyServer = false; legacyOffset = 0
    }

    fun markRead(peerId: String) {
        resetForAccount()
        val account = accountId() ?: return
        synchronized(diskLock) {
            val state = load(account)
            val remaining = unread(state) - peerId
            state.put("unread", JSONArray(remaining.toList()))
            file(account).writeAtomically(state.toString())
            if (accountId() == account) unread = remaining
        }
    }

    suspend fun poll(activePeerId: String? = null): List<ChatAlert> = monitor.withLock {
        resetForAccount()
        val account = accountId() ?: return@withLock emptyList()
        val generation = sync.accountGeneration
        val incoming = withContext(Dispatchers.IO) {
            if (legacyServer) legacyInbox(account) else try {
                decode(request("rpc/troit_chat_inbox", "POST", "{}"), account)
            } catch (failure: AccountApiException) {
                if (!failure.setupRequired) throw failure
                // Existing installations keep working before the additive inbox migration is applied.
                legacyServer = true
                legacyInbox(account)
            }
        }
        if (accountId() != account || sync.accountGeneration != generation) return@withLock emptyList()
        synchronized(diskLock) {
            if (accountId() != account || sync.accountGeneration != generation) return@synchronized emptyList()
            val state = load(account)
            val initialized = state.optBoolean("initialized")
            val cursors = state.optJSONObject("cursors") ?: JSONObject()
            // A native snapshot contains all peers with incoming messages; a legacy snapshot
            // contains all accepted peers even when only one rotating batch was read.
            cursors.keys().asSequence().toList().filterNot { it in incoming.peerIds }.forEach(cursors::remove)
            val unseen = unread(state).filterTo(linkedSetOf()) { it in incoming.peerIds }
            val alerts = incoming.alerts.filter { alert ->
                val peer = alert.peer.id
                val ready = if (incoming.baselinePerPeer) cursors.has(peer) else initialized
                val previous = cursors.optLong(peer, 0)
                val fresh = alert.message.sequence > previous
                cursors.put(peer, maxOf(previous, alert.message.sequence))
                if (peer == activePeerId) unseen.remove(peer)
                else if (ready && fresh) unseen.add(peer)
                ready && fresh && peer != activePeerId
            }
            // Empty conversations need a zero cursor, so their first later message alerts.
            incoming.checkedPeerIds.forEach { if (!cursors.has(it)) cursors.put(it, 0) }
            // A first connection establishes a baseline instead of alerting for historical chat.
            state.put("initialized", true).put("cursors", cursors)
                .put("unread", JSONArray(unseen.take(300)))
            file(account).writeAtomically(state.toString())
            unread = unseen.take(300).toSet()
            alerts
        }
    }

    private fun decode(response: String, account: String): InboxBatch {
        val rows = JSONArray(response)
        require(rows.length() <= 300) { "Unexpected inbox size." }
        val alerts = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val peer = SocialRules.decodeProfile(row.getJSONObject("peer"))
            val message = SocialRules.decodeMessage(row.getJSONObject("message"))
            require(message.recipientId == account && message.senderId == peer.id) { "Unexpected inbox recipient." }
            ChatAlert(peer, message)
        }.distinctBy { it.peer.id }
        return InboxBatch(alerts, alerts.mapTo(linkedSetOf()) { it.peer.id })
    }

    /** Compatibility path: at most 12 conversations per tick, rotating through larger friend lists. */
    private suspend fun legacyInbox(account: String): InboxBatch {
        val snapshot = JSONObject(request("rpc/troit_social_snapshot", "POST", "{}"))
        val connections = snapshot.optJSONArray("connections") ?: JSONArray()
        require(connections.length() <= 400)
        val peers = (0 until connections.length()).map { connections.getJSONObject(it) }
            .filter { it.optString("status") == "accepted" }
            .map { SocialRules.decodeProfile(it.getJSONObject("peer")) }
            .distinctBy { it.id }.take(300)
        if (peers.isEmpty()) return InboxBatch(emptyList(), emptySet(), baselinePerPeer = true)
        val selected = (0 until minOf(12, peers.size)).map { peers[(legacyOffset + it) % peers.size] }
        legacyOffset = (legacyOffset + selected.size) % peers.size
        val alerts = selected.mapNotNull { peer ->
            val body = JSONObject().put("p_peer_id", peer.id).put("p_limit", 50)
            val rows = JSONArray(request("rpc/troit_chat_messages", "POST", body.toString()))
            require(rows.length() <= 50)
            val latest = (0 until rows.length()).map { SocialRules.decodeMessage(rows.getJSONObject(it)) }
                .filter { it.recipientId == account && it.senderId == peer.id }.maxByOrNull { it.sequence }
            latest?.let { ChatAlert(peer, it) }
        }
        return InboxBatch(alerts, peers.mapTo(linkedSetOf()) { it.id },
            selected.mapTo(linkedSetOf()) { it.id }, baselinePerPeer = true)
    }

    private fun unread(state: JSONObject): Set<String> {
        val rows = state.optJSONArray("unread") ?: return emptySet()
        return (0 until minOf(300, rows.length())).map { rows.getString(it) }.toSet()
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val diskLocks = ConcurrentHashMap<String, Any>()
    }
}
