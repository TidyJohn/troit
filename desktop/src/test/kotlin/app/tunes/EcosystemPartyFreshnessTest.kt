package app.tunes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class EcosystemPartyFreshnessTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val runtime = "00000000-0000-0000-0000-000000000002"
    private val commandId = "00000000-0000-0000-0000-000000000003"
    private val partyId = "00000000-0000-0000-0000-000000000004"
    private val now = Instant.parse("2026-11-15T01:00:00Z").toEpochMilli()
    private val current = Song("Fixture current", "Fixture artist", "https://example.invalid/current", durationSeconds = 180)
    private val added = Song("Fixture added", "Fixture artist", "https://example.invalid/added", durationSeconds = 180)

    private class QueuedNativePlayer(private val beforeApply: () -> Unit) : MusicPlayer {
        override var now = NowPlaying()
        var applications = 0
        override fun positionMs() = 30_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) = error("Unexpected play")
        override fun toggle() = error("Unexpected toggle")
        override fun next() = error("Unexpected next")
        override fun previous() = error("Unexpected previous")
        override suspend fun replaceQueue(expectedQueue: List<Song>?, songs: List<Song>, index: Int,
            positionMs: Long, playing: Boolean, mode: PlaybackMode, allowed: () -> Boolean): Boolean {
            if (!allowed()) return false
            beforeApply()
            if (!allowed() || now.queue != expectedQueue) return false
            applications++
            now = now.copy(queue = songs, index = index, playing = playing)
            return true
        }
    }

    @Test fun partyAuthorizationExpiresAtActualNativeApplyWhileOrdinaryEditsRemainValid() = runBlocking {
        // Both server-validation latency and native-worker stalls consume the authorization window.
        for ((party, validationMs, delayMs) in listOf(Triple(true, 0L, 0L), Triple(true, 0L, 999L),
            Triple(true, 0L, 1_000L), Triple(true, 0L, 1_001L), Triple(true, 1_001L, 0L),
            Triple(true, 600L, 401L), Triple(false, 0L, 5_000L))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val directory = Files.createTempDirectory("troit-party-freshness").toFile()
            try {
                val sync = Sync(directory, "PC", Library(File(directory, "library.json")), scope)
                var monotonicTime = 1_000_000L
                val player = QueuedNativePlayer { monotonicTime += delayMs * 1_000_000 }.apply {
                    now = NowPlaying(listOf(current), 0, true)
                }
                val active = ConnectSnapshot(sync.deviceId, runtime, "PC", current, true, 30_000,
                    7, Instant.ofEpochMilli(now).toString(), Instant.ofEpochMilli(now + 45_000).toString(), true)
                val payload = JSONObject().put("id", commandId).put("kind", "edit")
                    .put("queue", Library.songsToJson(listOf(current, added))).put("index", 0)
                    .put("position_ms", 30_000).put("playing", true).put("mode", "STOP")
                    .put("expected_url", current.url).put("expected_index", 0)
                    .put("expected_queue", Library.songsToJson(listOf(current)))
                    .put("expected_revision", 3).put("expected_intent_revision", 0)
                if (party) payload.put("party_id", partyId)
                val acknowledgements = mutableListOf<String>()
                var validations = 0
                val client = EcosystemClient(sync, { path, body -> when {
                    path.endsWith("claim") -> payload.toString()
                    path.endsWith("ack") -> { acknowledgements += JSONObject(body).getString("p_result"); "null" }
                    else -> "null"
                } }, { owner })
                val connect = ConnectClient(sync, { _, _ -> active.toJson() }, { owner })
                val receiver = EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                    accountId = { owner }, clock = { now }, preferences = { ListeningPreferences() },
                    partyCommandAllowed = { id ->
                        assertEquals(commandId, id); validations++
                        monotonicTime += validationMs * 1_000_000
                        true
                    }, monotonicClock = { monotonicTime })
                receiver.tick(runtime, active)
                val accepted = !party || validationMs + delayMs <= 1_000
                assertEquals("party=$party validation=$validationMs delay=$delayMs", if (accepted) 1 else 0, player.applications)
                assertEquals(if (party) 1 else 0, validations)
                assertEquals(listOf(if (accepted) "executed" else "stale"), acknowledgements)
                assertEquals(if (accepted) listOf(current, added) else listOf(current), player.now.queue)
            } finally { scope.cancel() }
        }
    }

    @Test fun rejectedPartyValidationNeverEntersTheNativeQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val directory = Files.createTempDirectory("troit-party-denied").toFile()
        try {
            val sync = Sync(directory, "PC", Library(File(directory, "library.json")), scope)
            val player = QueuedNativePlayer { fail("Unauthorized edit entered the native queue") }.apply {
                now = NowPlaying(listOf(current), 0, true)
            }
            val active = ConnectSnapshot(sync.deviceId, runtime, "PC", current, true, 30_000,
                7, Instant.ofEpochMilli(now).toString(), Instant.ofEpochMilli(now + 45_000).toString(), true)
            val payload = JSONObject().put("id", commandId).put("kind", "edit").put("party_id", partyId)
                .put("queue", Library.songsToJson(listOf(current, added))).put("index", 0)
                .put("position_ms", 30_000).put("playing", true).put("mode", "STOP")
                .put("expected_url", current.url).put("expected_index", 0)
                .put("expected_queue", Library.songsToJson(listOf(current)))
                .put("expected_revision", 3).put("expected_intent_revision", 0)
            val acknowledgements = mutableListOf<String>()
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("claim") -> payload.toString()
                path.endsWith("ack") -> { acknowledgements += JSONObject(body).getString("p_result"); "null" }
                else -> "null"
            } }, { owner })
            val connect = ConnectClient(sync, { _, _ -> active.toJson() }, { owner })
            EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                accountId = { owner }, clock = { now }, preferences = { ListeningPreferences() },
                partyCommandAllowed = { false }).tick(runtime, active)
            assertEquals(0, player.applications)
            assertEquals(listOf("stale"), acknowledgements)
        } finally { scope.cancel() }
    }
}
