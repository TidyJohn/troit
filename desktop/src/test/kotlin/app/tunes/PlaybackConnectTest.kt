package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PlaybackConnectTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val commandId = "00000000-0000-0000-0000-000000000002"
    private val songs = listOf(Song("First", "Artist", "https://www.youtube.com/watch?v=first"),
        Song("Second", "Artist", "https://www.youtube.com/watch?v=second"))

    private class Player(initial: NowPlaying) : MusicPlayer {
        override var now = initial
        override var interactionRevision = 0L
        var nexts = 0; var previouses = 0; var toggles = 0
        override fun positionMs() = 1000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) { interactionRevision++; now = NowPlaying(songs, index, true) }
        override fun toggle() { interactionRevision++; toggles++; now = now.copy(playing = !now.playing) }
        override fun next() { interactionRevision++; nexts++; now = now.copy(index = (now.index + 1).coerceAtMost(now.queue.lastIndex)) }
        override fun previous() { interactionRevision++; previouses++; now = now.copy(index = (now.index - 1).coerceAtLeast(0)) }
    }

    private fun sync(): Pair<Sync, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("troit-connect-test").toFile()
        return Sync(dir, "Test PC", Library(File(dir, "library.json")), scope) to scope
    }

    private fun snapshot() = ConnectSnapshot(me, commandId, "My PC", songs.first(), true, 1000, 7,
        "2026-11-11T01:00:00Z", "2026-11-11T01:00:45Z", true)

    private fun claim(action: String, id: String = commandId) = JSONObject().put("id", id).put("action", action)
        .put("track_url", songs.first().url).put("queue_index", 0).put("revision", 7).toString()

    @Test
    fun persistedSnapshotRetainsTargetAndExpiresLocally() {
        val snapshot = snapshot()
        assertEquals(snapshot, ConnectSnapshot.fromJson(snapshot.toJson()))
        val instant = java.time.Instant.parse("2026-11-11T01:00:30Z").toEpochMilli()
        assertTrue(snapshot.isFresh(instant))
        assertFalse(snapshot.isFresh(instant + 15_000))
        assertFalse(snapshot.copy(fresh = false).isFresh(instant))
        assertFalse(snapshot.copy(expiresAt = "invalid").isFresh(instant))
    }

    @Test
    fun retriesKeepStableUuidExactDeviceAndAnExplicitPauseAction() = runBlocking {
        val (sync, scope) = sync()
        try {
            val bodies = mutableListOf<JSONObject>()
            val client = ConnectClient(sync, { path, body ->
                assertEquals("rpc/troit_connect_command", path)
                bodies += JSONObject(body)
                JSONObject().put("id", commandId).put("status", "pending").toString()
            }, { me })
            repeat(2) { assertTrue(client.command(snapshot(), "toggle", commandId).pending) }
            assertEquals(2, bodies.size)
            bodies.forEach {
                assertEquals(commandId, it.getString("p_command_id"))
                assertEquals(snapshot().instanceId, it.getString("p_instance_id"))
                assertEquals(snapshot().deviceId, it.getString("p_device_id"))
                assertEquals(snapshot().updatedAt, it.getString("p_observed_at"))
                assertEquals(7L, it.getLong("p_expected_revision"))
                assertEquals("pause", it.getString("p_action"))
            }
            assertTrue(runCatching { client.command(snapshot(), "delete", commandId) }.isFailure)
            assertEquals(2, bodies.size)
        } finally { scope.cancel() }
    }

    @Test
    fun lateAccountResponseCannotBecomeAnotherAccountsWidgetState() = runBlocking {
        val (sync, scope) = sync()
        try {
            var owner: String? = me
            val waiting = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            val client = ConnectClient(sync, { _, _ -> waiting.complete(Unit); response.await() }, { owner })
            val request = async { runCatching { client.state() } }
            waiting.await()
            owner = "another-account"
            response.complete(snapshot().toJson())
            assertTrue(request.await().isFailure)
        } finally { scope.cancel() }
    }

    @Test
    fun onlyGenuinePlaybackPromotesADeviceAndHeartbeatsNeverRetakeOwnership() = runBlocking {
        val (sync, scope) = sync()
        try {
            var time = 100_000L
            val publishes = mutableListOf<JSONObject>()
            val player = Player(NowPlaying(songs, 0, false))
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("publish")) publishes += JSONObject(body)
                "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me }, { time })
            connect.tick()
            assertTrue(publishes.isEmpty())
            player.toggle()
            connect.tick()
            time += 15_000
            connect.tick()
            player.toggle()
            connect.tick()
            player.toggle()
            connect.tick()
            player.next()
            connect.tick()
            assertEquals(listOf(true, false, false, true, true), publishes.map { it.getBoolean("p_takeover") })
            assertTrue(publishes.all { !it.has("queue") && !it.has("p_queue") })
            assertEquals(2_000L, connect.pollDelayMs) // Paused desktop ownership is still controllable.
        } finally { scope.cancel() }
    }

    @Test
    fun failedInitialPublishRetriesThePlaybackTakeoverInsteadOfSendingAnIdleHeartbeat() = runBlocking {
        val (sync, scope) = sync()
        try {
            val takeovers = mutableListOf<Boolean>()
            val player = Player(NowPlaying(songs, 0, true))
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("publish")) {
                    takeovers += JSONObject(body).getBoolean("p_takeover")
                    if (takeovers.size == 1) throw IOException("offline")
                }
                "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            connect.tick()
            assertTrue(connect.pollDelayMs > 2_000)
            connect.tick()
            assertEquals(listOf(true, true), takeovers)
            assertNull(connect.error)
        } finally { scope.cancel() }
    }

    @Test
    fun supersededPcAutoplayCannotStealThePointerButExplicitPlaybackCan() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val takeovers = mutableListOf<Boolean>()
            var active = "PC"
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("publish")) {
                    val takeover = JSONObject(body).getBoolean("p_takeover")
                    takeovers += takeover
                    if (takeover) active = "PC"
                }
                "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            connect.tick()
            active = "Phone"
            // Natural end-of-track advances the index without a user's interaction revision.
            player.now = player.now.copy(index = 1)
            connect.tick()
            assertEquals("Phone", active)
            player.previous()
            connect.tick()
            assertEquals("PC", active)
            assertEquals(listOf(true, false, true), takeovers)
        } finally { scope.cancel() }
    }

    @Test
    fun explicitPlaybackIntentSurvivesLoadingUntilTheNewTrackActuallyPlays() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val takeovers = mutableListOf<Boolean>()
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("publish")) takeovers += JSONObject(body).getBoolean("p_takeover")
                "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            connect.tick()
            player.next()
            player.now = player.now.copy(playing = false, loading = true)
            connect.tick()
            player.now = player.now.copy(playing = true)
            connect.tick()
            player.now = player.now.copy(loading = false)
            connect.tick()
            assertEquals(listOf(true, false, false, true), takeovers)
        } finally { scope.cancel() }
    }

    @Test
    fun playerTrackIsRecheckedAfterClaimNetworkResponseBeforeApplyingNext() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val acks = mutableListOf<String>()
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("claim") -> { player.now = player.now.copy(index = 1); claim("next") }
                path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                else -> "null"
            } }, { me })
            PlaybackConnect(sync, player, "PC", client, { me }).tick()
            assertEquals(0, player.nexts)
            assertEquals(listOf("stale"), acks)
        } finally { scope.cancel() }
    }

    @Test
    fun lostAcknowledgementCannotReplayAnAlreadyClaimedSkip() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            var claims = 0
            val client = ConnectClient(sync, { path, _ -> when {
                path.endsWith("claim") -> if (++claims == 1) claim("next") else "null"
                path.endsWith("ack") -> throw IOException("ack response lost")
                else -> "null"
            } }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            connect.tick()
            connect.tick()
            assertEquals(1, player.nexts)
            assertEquals(2, claims)
            assertNull(connect.error)
        } finally { scope.cancel() }
    }

    @Test
    fun stalledPlayerQueueIsCancelledAndAcknowledgedStaleWithoutKillingThePoller() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            var cancelled = false
            val stalled = object : MusicPlayer by player {
                override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean {
                    try { return CompletableDeferred<Boolean>().await() }
                    finally { cancelled = true }
                }
            }
            var claims = 0
            val acks = mutableListOf<String>()
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("claim") -> if (++claims == 1) claim("next") else "null"
                path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                else -> "null"
            } }, { me })
            val connect = PlaybackConnect(sync, stalled, "PC", client, { me }, controlTimeoutMs = 5)
            connect.tick()
            connect.tick()
            assertTrue(cancelled)
            assertEquals(listOf("stale"), acks)
            assertEquals(0, player.nexts)
            assertEquals(2, claims)
            assertNull(connect.error)
            assertEquals(2_000L, connect.pollDelayMs)
        } finally { scope.cancel() }
    }

    @Test
    fun anotherSyncInstanceLogoutInvalidatesQueuedControlEvenWithTheSameCachedOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("troit-connect-generation-test").toFile()
        val sync = Sync(dir, "PC", Library(File(dir, "library.json")), scope)
        val other = Sync(dir, "Widget", Library(File(dir, "widget-library.json")), scope)
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val queued = object : MusicPlayer by player {
                override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean {
                    waiting.complete(Unit)
                    release.await()
                    if (!allowed()) return false
                    player.next()
                    return true
                }
            }
            var claims = 0
            val instances = mutableListOf<String>()
            val acks = mutableListOf<String>()
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("publish") -> { instances += JSONObject(body).getString("p_instance_id"); "null" }
                path.endsWith("claim") -> if (++claims == 1) claim("next") else "null"
                path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                else -> "null"
            } }, { me }) // Deliberately retain the old userId, as an independent worker can do.
            val connect = PlaybackConnect(sync, queued, "PC", client, { me })
            val pending = async { connect.tick() }
            waiting.await()
            other.signOut() // Empty local fixture session: no HTTP request is made.
            release.complete(Unit)
            pending.await()
            assertEquals(0, player.nexts)
            assertTrue("Logout must discard the old account's acknowledgement as well as its control", acks.isEmpty())
            connect.tick()
            assertEquals(2, instances.size)
            assertNotEquals("Same-user session generation changes need a new command owner runtime", instances[0], instances[1])
        } finally { scope.cancel() }
    }

    @Test
    fun explicitPlayAndPauseAreIdempotentOnThePlayer() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val commands = ArrayDeque(listOf("play", "pause", "pause"))
            val client = ConnectClient(sync, { path, _ ->
                if (path.endsWith("claim") && commands.isNotEmpty()) claim(commands.removeFirst()) else "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            repeat(3) { connect.tick() }
            assertEquals(1, player.toggles)
            assertFalse(player.now.playing)
        } finally { scope.cancel() }
    }

    @Test
    fun signingOutWhileClaimIsInFlightDoesNotControlThePreviousAccountsSong() = runBlocking {
        val (sync, scope) = sync()
        try {
            var owner: String? = me
            val player = Player(NowPlaying(songs, 0, true))
            val client = ConnectClient(sync, { path, _ ->
                if (path.endsWith("claim")) { owner = null; claim("next") } else "null"
            }, { owner })
            val connect = PlaybackConnect(sync, player, "PC", client, { owner })
            connect.tick()
            assertEquals(0, player.nexts)
            connect.tick()
            assertNull(connect.error)
        } finally { scope.cancel() }
    }

    @Test
    fun nextPublishesTheActualNewTrackBeforeAcknowledgingExecution() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val events = mutableListOf<String>()
            var claims = 0
            val client = ConnectClient(sync, { path, body ->
                when {
                    path.endsWith("publish") -> { events += "publish:${JSONObject(body).getString("p_track_url")}"; "null" }
                    path.endsWith("claim") -> { events += "claim"; if (++claims == 1) claim("next") else "null" }
                    path.endsWith("ack") -> { events += "ack:${JSONObject(body).getString("p_result")}"; "null" }
                    else -> "null"
                }
            }, { me })
            PlaybackConnect(sync, player, "PC", client, { me }).tick()
            assertEquals(listOf("publish:${songs[0].url}", "claim", "publish:${songs[1].url}", "ack:executed"), events)
            assertEquals(1, player.nexts)
        } finally { scope.cancel() }
    }

    @Test
    fun failedPostControlPublicationRetriesMetadataWithoutRepeatingTheSkip() = runBlocking {
        val (sync, scope) = sync()
        try {
            val player = Player(NowPlaying(songs, 0, true))
            val published = mutableListOf<String>()
            val acks = mutableListOf<String>()
            var claims = 0
            var publications = 0
            val client = ConnectClient(sync, { path, body ->
                when {
                    path.endsWith("publish") -> {
                        if (++publications == 2) throw IOException("Metadata connection interrupted")
                        published += JSONObject(body).getString("p_track_url")
                        "null"
                    }
                    path.endsWith("claim") -> if (++claims == 1) claim("next") else "null"
                    path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                    else -> "null"
                }
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            connect.tick()
            connect.tick()
            assertEquals(listOf(songs[0].url, songs[1].url), published)
            assertEquals(listOf("executed"), acks)
            assertEquals(1, player.nexts)
            assertNull(connect.error)
        } finally { scope.cancel() }
    }

    @Test
    fun concurrentTicksShareOneClaimLoopAndMissingSchemaBacksOff() = runBlocking {
        val (sync, scope) = sync()
        try {
            val waiting = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            var publishes = 0
            val player = Player(NowPlaying(songs, 0, true))
            val client = ConnectClient(sync, { path, _ ->
                if (path.endsWith("publish")) { publishes++; waiting.complete(Unit); response.await() } else "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me })
            val first = async { connect.tick() }
            waiting.await()
            connect.tick()
            assertEquals(1, publishes)
            response.complete("null")
            first.await()
            val missing = PlaybackConnect(sync, player, "PC", ConnectClient(sync, { _, _ ->
                throw AccountApiException(404, "PGRST202", "not installed")
            }, { me }), { me })
            missing.tick()
            assertTrue(missing.setupRequired)
            assertEquals(60_000L, missing.pollDelayMs)
        } finally { scope.cancel() }
    }
}
