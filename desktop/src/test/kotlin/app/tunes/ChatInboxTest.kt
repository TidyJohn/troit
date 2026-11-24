package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChatInboxTest {
    private val me = "account-me"
    private val peer = "peer-one"
    private fun profile(id: String) = JSONObject().put("id", id).put("handle", id).put("display_name", id)
    private fun message(sender: String, sequence: Long, recipient: String = me) = JSONObject()
        .put("id", "$sender-$sequence").put("seq", sequence).put("sender_id", sender)
        .put("recipient_id", recipient).put("text", "Message $sequence")
        .put("sticker_id", JSONObject.NULL).put("created_at", "2026-11-13T00:00:00Z")
    private fun row(sender: String, sequence: Long, recipient: String = me) = JSONObject()
        .put("peer", profile(sender)).put("message", message(sender, sequence, recipient))
    private class Fixture {
        val dir = Files.createTempDirectory("troit-chat-inbox-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sync = Sync(dir, "Test", Library(File(dir, "library.json")), scope)
        var owner: String? = "account-me"
        fun inbox(transport: SocialTransport) = ChatInbox(sync, dir, transport) { owner }
        fun close() { scope.cancel(); dir.deleteRecursively() }
    }

    @Test
    fun initialHistoryIsSilentAndNewMessagesFromEveryPeerNotifyOnce() = runBlocking {
        val f = Fixture()
        try {
            var response = JSONArray().put(row(peer, 5)).toString()
            val inbox = f.inbox { _, _, _ -> response }
            assertTrue(inbox.poll().isEmpty())
            assertTrue(inbox.unreadPeerIds.isEmpty())
            response = JSONArray().put(row(peer, 6)).put(row("peer-two", 7)).toString()
            assertEquals(setOf(peer, "peer-two"), inbox.poll().map { it.peer.id }.toSet())
            assertEquals(setOf(peer, "peer-two"), inbox.unreadPeerIds)
            assertTrue(inbox.poll().isEmpty())
            assertTrue(f.inbox { _, _, _ -> response }.poll().isEmpty())
            assertFalse(f.dir.listFiles()!!.filter { it.name.startsWith("chat-inbox-") }
                .single().readText().contains("Message"))
        } finally { f.close() }
    }

    @Test
    fun emptyInitialInboxStillNotifiesOnFirstMessageAndActiveChatStaysRead() = runBlocking {
        val f = Fixture()
        try {
            var response = "[]"
            val inbox = f.inbox { _, _, _ -> response }
            assertTrue(inbox.poll().isEmpty())
            response = JSONArray().put(row(peer, 1)).toString()
            assertEquals(1, inbox.poll().size)
            inbox.markRead(peer)
            assertTrue(inbox.unreadPeerIds.isEmpty())
            val restored = f.inbox { _, _, _ -> response }
            restored.resetForAccount()
            assertTrue(restored.unreadPeerIds.isEmpty())
            response = JSONArray().put(row(peer, 2)).toString()
            assertTrue(restored.poll(activePeerId = peer).isEmpty())
            assertTrue(restored.unreadPeerIds.isEmpty())
            assertTrue(restored.poll().isEmpty())
        } finally { f.close() }
    }

    @Test
    fun staleResponseCannotLeakAcrossAccountSwitchAndAccountsKeepTheirCursors() = runBlocking {
        val f = Fixture()
        try {
            var response = "[]"
            var gate: CompletableDeferred<Unit>? = null
            val started = CompletableDeferred<Unit>()
            val inbox = f.inbox { _, _, _ ->
                val captured = response
                gate?.let { started.complete(Unit); it.await() }
                captured
            }
            inbox.poll()
            response = JSONArray().put(row(peer, 1)).toString()
            gate = CompletableDeferred()
            val pending = async { inbox.poll() }
            started.await()
            f.owner = "other-account"
            inbox.resetForAccount()
            gate!!.complete(Unit)
            assertTrue(pending.await().isEmpty())
            assertTrue(inbox.unreadPeerIds.isEmpty())
            gate = null
            response = "[]"
            assertTrue(inbox.poll().isEmpty())
            f.owner = me
            response = JSONArray().put(row(peer, 1)).toString()
            assertEquals(1, inbox.poll().size)
            f.owner = null
            assertTrue(inbox.unreadPeerIds.isEmpty())
            assertTrue(inbox.poll().isEmpty())
        } finally { f.close() }
    }

    @Test
    fun overlappingInstancesDoNotDeliverTheSameMessageTwice() = runBlocking {
        val f = Fixture()
        try {
            var response = "[]"
            val first = f.inbox { _, _, _ -> response }
            val second = f.inbox { _, _, _ -> response }
            first.poll()
            response = JSONArray().put(row(peer, 1)).toString()
            val a = async(Dispatchers.Default) { first.poll() }
            val b = async(Dispatchers.Default) { second.poll() }
            assertEquals(1, a.await().size + b.await().size)
        } finally { f.close() }
    }

    @Test
    fun legacyRotationBaselinesEachPeerIncludingEmptyConversations() = runBlocking {
        val f = Fixture()
        try {
            val peers = (1..14).map { "peer-$it" }
            val messages = peers.associateWith { 1L }.toMutableMap()
            messages[peers[0]] = 0
            val connections = JSONArray(peers.map { JSONObject().put("status", "accepted").put("peer", profile(it)) })
            var reads = 0
            val inbox = f.inbox { path, _, body -> when (path) {
                "rpc/troit_chat_inbox" -> throw AccountApiException(404, "PGRST202", "Missing RPC")
                "rpc/troit_social_snapshot" -> JSONObject().put("connections", connections).toString()
                "rpc/troit_chat_messages" -> {
                    reads++
                    val sender = JSONObject(body!!).getString("p_peer_id")
                    val sequence = messages.getValue(sender)
                    if (sequence == 0L) "[]" else JSONArray().put(message(sender, sequence)).toString()
                }
                else -> error(path)
            } }
            assertTrue(inbox.poll().isEmpty())
            assertEquals(12, reads)
            // Peers 13/14 contain historical messages but were absent from the first batch.
            assertTrue(inbox.poll().isEmpty())
            messages[peers[0]] = 2
            val alerts = inbox.poll()
            assertEquals(listOf(peers[0]), alerts.map { it.peer.id })
            assertEquals(setOf(peers[0]), inbox.unreadPeerIds)
        } finally { f.close() }
    }

    @Test
    fun peersNoLongerInInboxLoseUnreadStateAndInvalidRecipientIsRejected() = runBlocking {
        val f = Fixture()
        try {
            var response = "[]"
            val inbox = f.inbox { _, _, _ -> response }
            inbox.poll()
            response = JSONArray().put(row(peer, 1)).toString()
            inbox.poll()
            assertEquals(setOf(peer), inbox.unreadPeerIds)
            response = "[]"
            inbox.poll()
            assertTrue(inbox.unreadPeerIds.isEmpty())
            response = JSONArray().put(row(peer, 2, "someone-else")).toString()
            assertTrue(runCatching { inbox.poll() }.isFailure)
            assertTrue(inbox.unreadPeerIds.isEmpty())
        } finally { f.close() }
    }
}
