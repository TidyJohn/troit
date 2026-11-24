package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SocialStateTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val peer = "00000000-0000-0000-0000-000000000002"
    private val connection = "00000000-0000-0000-0000-000000000003"
    private fun snapshot(owner: String = me) = """{"profile":{"id":"$owner","handle":"my_handle","display_name":"Me"},"connections":[{"id":"$connection","peer":{"id":"$peer","handle":"friend_handle","display_name":"Friend"},"status":"accepted","requested_by":"$owner"}],"blocked":[]}"""
    private fun sync(scope: CoroutineScope): Sync {
        val dir = Files.createTempDirectory("troit-social-test").toFile()
        return Sync(dir, "Test", Library(File(dir, "library.json")), scope)
    }
    private suspend fun until(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(5) }

    @Test
    fun validationRejectsEmptyOversizedAndNonHandleInputsBeforeTransport() {
        assertEquals("my_handle", SocialRules.handle(" MY_Handle "))
        assertEquals("Me", SocialRules.displayName(" Me "))
        for (invalid in listOf("", "ab", "hello world", "één", "a".repeat(25))) {
            assertTrue(runCatching { SocialRules.handle(invalid) }.isFailure)
        }
        assertTrue(runCatching { SocialRules.text(" \n ") }.isFailure)
        assertTrue(runCatching { SocialRules.text("x".repeat(2001)) }.isFailure)
        assertEquals(2000, SocialRules.text("x".repeat(2000)).length)
    }

    @Test
    fun reconciliationIsBoundedDeduplicatedAndConfirmedRetriesReplacePending() {
        fun message(index: Int) = ChatMessage("message-$index", index.toLong(), me, peer, text = "Message $index")
        val pending = message(700).copy(sequence = 0, pending = true)
        val first = SocialRules.mergeMessages((1..500).map(::message), listOf(pending))
        assertEquals(300, first.size)
        assertTrue(first.last().pending)
        val confirmed = SocialRules.mergeMessages(first, listOf(message(700), message(700)))
        assertEquals(300, confirmed.size)
        assertEquals(1, confirmed.count { it.id == "message-700" })
        assertFalse(confirmed.last().pending)
        assertTrue(confirmed.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
    }

    @Test
    fun duplicateRefreshIsSkippedAndSigningOutHidesOldAccountData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var owner: String? = me
            val gate = CompletableDeferred<String>()
            var calls = 0
            val social = SocialState(sync(scope), scope, { _, _, _ -> calls++; gate.await() }, { owner })
            val first = launch { social.refresh() }
            until { calls == 1 }
            social.refresh()
            assertEquals(1, calls)
            owner = null
            social.resetForAccount()
            gate.complete(snapshot())
            first.join()
            assertNull(social.profile)
            assertTrue(social.connections.isEmpty())
            assertTrue(social.messages.isEmpty())
            assertFalse(social.loading)
        } finally { scope.cancel() }
    }

    @Test
    fun switchingAccountCannotAdoptAnOldInFlightResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var owner: String? = me
            val gate = CompletableDeferred<String>()
            val social = SocialState(sync(scope), scope, { _, _, _ -> gate.await() }, { owner })
            val initial = launch { social.refresh() }
            until { social.loading }
            owner = peer
            // Accessors hide the previous account immediately, before the next network poll.
            assertNull(social.profile)
            social.resetForAccount()
            gate.complete(snapshot())
            initial.join()
            assertNull(social.profile)
            assertTrue(social.connections.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun failedMessageRetriesUseTheSameUuidAndDoNotDuplicateTheBubble() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val sentIds = mutableListOf<String>()
            val social = SocialState(sync(scope), scope, { path, _, body ->
                when (path) {
                    "rpc/troit_social_snapshot" -> snapshot()
                    "rpc/troit_chat_messages" -> "[]"
                    "rpc/troit_chat_send" -> {
                        val request = JSONObject(body!!)
                        val id = request.getString("p_message_id")
                        sentIds += id
                        if (sentIds.size == 1) throw IOException("Offline")
                        JSONObject().put("id", id).put("seq", 1).put("sender_id", me).put("recipient_id", peer)
                            .put("text", request.getString("p_text")).put("sticker_id", JSONObject.NULL)
                            .put("created_at", "2026-11-11T12:00:00Z").toString()
                    }
                    else -> error("Unexpected request: $path")
                }
            }, { me })
            social.refresh()
            social.openChat(social.friends.single())
            until { !social.loading }
            social.sendText("Hello")
            until { !social.sending && social.messages.any { it.failed } }
            assertEquals("Offline", social.error)
            social.retrySend(social.messages.single())
            until { !social.sending }
            assertEquals(2, sentIds.size)
            assertEquals(sentIds[0], sentIds[1])
            assertEquals(1, social.messages.size)
            assertEquals(1L, social.messages.single().sequence)
            assertFalse(social.messages.single().failed)
        } finally { scope.cancel() }
    }

    @Test
    fun oversizedServerSnapshotsAreRejectedWithoutCrashingOrRetainingData() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val snapshot = JSONObject(snapshot())
            val rows = snapshot.getJSONArray("connections")
            val row = rows.getJSONObject(0)
            repeat(400) { rows.put(row) }
            val social = SocialState(sync(scope), scope, { _, _, _ -> snapshot.toString() }, { me })
            social.refresh()
            assertTrue(social.connections.isEmpty())
            assertNotNull(social.error)
            assertTrue(social.pollDelayMs >= 10_000)
        } finally { scope.cancel() }
    }
}
