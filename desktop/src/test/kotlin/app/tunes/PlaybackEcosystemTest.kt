package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

class PlaybackEcosystemTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val time = Instant.parse("2026-11-15T00:00:10Z").toEpochMilli()
    private val song = Song("One song", "Artist", "https://www.youtube.com/watch?v=one", durationSeconds = 180)
    private fun remote(playing: Boolean = true) = ConnectSnapshot(
        "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003",
        "Living room TV", song, playing, 20_000, 3,
        "2026-11-15T00:00:00Z", "2026-11-15T00:00:45Z", true,
    )
    private fun fixture(): Pair<Sync, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = Files.createTempDirectory("troit-ecosystem").toFile()
        return Sync(dir, "Test", Library(File(dir, "library.json")), scope) to scope
    }
    private class Player(private val song: Song) : MusicPlayer {
        override var now = NowPlaying(listOf(song), 0, true)
        override var interactionRevision = 0L
        var pauses = 0
        var plays = 0
        var startedAt = -1L
        override fun positionMs() = 20_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) {
            plays++; startedAt = positionMs
            interactionRevision++; now = NowPlaying(songs, index, true)
        }
        override fun toggle() {
            interactionRevision++
            if (now.playing) pauses++
            now = now.copy(playing = !now.playing)
        }
        override fun next() {}
        override fun previous() {}
    }

    @Test fun progressUsesElapsedServerTimeAndCapsAtDuration() {
        assertEquals(30_000L, remote().estimatedPositionMs(time))
        assertEquals(20_000L, remote(false).estimatedPositionMs(time))
        assertEquals(180_000L, remote().copy(positionMs = 179_000).estimatedPositionMs(time))
        assertEquals(20_000L, remote().copy(fresh = false).estimatedPositionMs(time))
        assertEquals(20_000L, remote().estimatedPositionMs(time - 30_000))
        assertEquals(65_000L, remote().estimatedPositionMs(time + 100_000)) // Freeze at expiry, never jump backward.
    }

    @Test fun idleDeviceReadsTheActiveTvWithoutPublishingItsRestoredQueue() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val paths = mutableListOf<String>()
            val player = Player(song).apply { now = now.copy(playing = false) }
            val client = ConnectClient(sync, { path, _ -> paths += path; remote().toJson() }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me }, { time })
            connect.tick()
            assertEquals(remote(), connect.snapshot)
            assertEquals(listOf("rpc/troit_connect_state"), paths)
        } finally { scope.cancel() }
    }

    @Test fun freshTvTakeoverPausesPreviousAudioAndNeverBecomesNewLocalIntent() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val player = Player(song)
            val takeovers = mutableListOf<Boolean>()
            var active: ConnectSnapshot? = null
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("publish") -> {
                    val data = JSONObject(body)
                    val takeover = data.getBoolean("p_takeover")
                    takeovers += takeover
                    if (takeover) active = remote().copy(deviceId = data.getString("p_device_id"), instanceId = data.getString("p_instance_id"))
                    active?.toJson() ?: "null"
                }
                path.endsWith("state") -> active?.toJson() ?: "null"
                else -> "null"
            } }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me }, { time })
            connect.tick()
            active = remote()
            connect.tick()
            connect.tick()
            assertFalse(player.now.playing)
            assertEquals(1, player.pauses)
            assertEquals(listOf(true, false), takeovers)
            player.toggle()
            connect.tick()
            assertTrue(player.now.playing)
            assertEquals(listOf(true, false, true), takeovers)
        } finally { scope.cancel() }
    }

    @Test fun localIntentDuringOwnershipReadPreventsDelayedPause() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val player = Player(song)
            var read = false
            val client = ConnectClient(sync, { path, _ ->
                if (path.endsWith("state")) { read = true; player.play(listOf(song), 0); remote().toJson() }
                else "null"
            }, { me })
            val connect = PlaybackConnect(sync, player, "PC", client, { me }, { time })
            connect.tick()
            connect.tick()
            assertTrue(read)
            assertTrue(player.now.playing)
            assertEquals(0, player.pauses)
        } finally { scope.cancel() }
    }

    @Test fun failedUploadRetriesOneUuidAndExplicitActionUntilExecutionIsConfirmed() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val commands = mutableListOf<JSONObject>()
            var statusReads = 0
            var lastId = ""
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("state") -> remote(commands.isEmpty()).toJson()
                path.endsWith("command_status") -> {
                    statusReads++
                    if (statusReads == 1) "null"
                    else JSONObject().put("id", lastId).put("status", "executed").toString()
                }
                path.endsWith("command") -> {
                    val data = JSONObject(body); commands += data; lastId = data.getString("p_command_id")
                    if (commands.size == 1) throw IOException("accepted but response lost")
                    JSONObject().put("id", lastId).put("status", "pending").toString()
                }
                else -> error(path)
            } }, { me })
            val state = PlaybackRemoteState(sync, client, { me }, { time }, receiptPollMs = 1)
            state.refresh()
            assertTrue(state.sendControl("toggle"))
            assertEquals(2, commands.size)
            assertEquals(commands[0].toString(), commands[1].toString())
            assertEquals("pause", commands[1].getString("p_action"))
            assertFalse(state.busy)
            assertFalse(state.pending)
            assertEquals("Playback updated.", state.message)
            assertFalse(state.snapshot!!.playing)
        } finally { scope.cancel() }
    }

    @Test fun timeoutDoesNotReportSuccessAndLaterRefreshReconcilesReceipt() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var executed = false
            var commandId = ""
            val client = ConnectClient(sync, { path, body -> when {
                path.endsWith("state") -> remote().toJson()
                else -> {
                    commandId = JSONObject(body).optString("p_command_id", commandId)
                    JSONObject().put("id", commandId).put("status", if (executed) "executed" else "pending").toString()
                }
            } }, { me })
            val state = PlaybackRemoteState(sync, client, { me }, { time }, confirmationTimeoutMs = 5, receiptPollMs = 50)
            state.refresh()
            assertFalse(state.sendControl("next"))
            assertTrue(state.pending)
            assertFalse(state.busy)
            executed = true
            state.refresh()
            assertFalse(state.pending)
            assertEquals("Playback updated.", state.message)
        } finally { scope.cancel() }
    }

    @Test fun accountChangesDiscardLateControlReceiptsAndPlayback() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var owner: String? = me
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("state")) remote().toJson()
                else {
                    waiting.complete(Unit); release.await()
                    JSONObject().put("id", JSONObject(body).getString("p_command_id")).put("status", "executed").toString()
                }
            }, { owner })
            val state = PlaybackRemoteState(sync, client, { owner }, { time })
            state.refresh()
            val result = async { state.sendControl("pause") }
            waiting.await()
            owner = null
            state.resetForAccount()
            release.complete(Unit)
            assertFalse(result.await())
            assertNull(state.snapshot)
            assertNull(state.message)
            assertFalse(state.busy)
            assertFalse(state.pending)
        } finally { scope.cancel() }
    }

    @Test fun expiredSnapshotsNeverSubmitControls() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var commands = 0
            val client = ConnectClient(sync, { path, _ ->
                if (path.endsWith("state")) remote().copy(fresh = false).toJson()
                else { commands++; "null" }
            }, { me })
            val state = PlaybackRemoteState(sync, client, { me }, { time })
            state.refresh()
            assertFalse(state.sendControl("next"))
            assertEquals(0, commands)
            assertNotNull(state.error)
        } finally { scope.cancel() }
    }

    @Test fun oneControlWaitsForSlowRefreshAndRepeatedTapsCannotQueueSkips() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var stateReads = 0
            var commands = 0
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("state")) {
                    if (++stateReads == 2) { reading.complete(Unit); release.await() }
                    remote().toJson()
                } else {
                    commands++
                    JSONObject().put("id", JSONObject(body).getString("p_command_id")).put("status", "executed").toString()
                }
            }, { me })
            val state = PlaybackRemoteState(sync, client, { me }, { time })
            state.refresh()
            val refreshing = async { state.refresh() }
            reading.await()
            val click = async { state.sendControl("next") }
            yield()
            assertTrue(state.busy)
            assertFalse(state.sendControl("next"))
            assertEquals(0, commands)
            release.complete(Unit)
            refreshing.await()
            assertTrue(click.await())
            assertEquals(1, commands)
            assertFalse(state.busy)
        } finally { scope.cancel() }
    }

    @Test fun cancelledClickWaitingForRefreshReleasesItsReservation() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var stateReads = 0
            var commands = 0
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client = ConnectClient(sync, { path, body ->
                if (path.endsWith("state")) {
                    if (++stateReads == 2) { reading.complete(Unit); release.await() }
                    remote().toJson()
                } else {
                    commands++
                    JSONObject().put("id", JSONObject(body).getString("p_command_id")).put("status", "executed").toString()
                }
            }, { me })
            val state = PlaybackRemoteState(sync, client, { me }, { time })
            state.refresh()
            val refreshing = async { state.refresh() }
            reading.await()
            val click = async { state.sendControl("next") }
            yield()
            click.cancel(); click.join()
            assertFalse(state.busy)
            release.complete(Unit); refreshing.await()
            assertTrue(state.sendControl("next"))
            assertEquals(1, commands)
        } finally { scope.cancel() }
    }

    private class TransferFixture : AutoCloseable {
        val me = "00000000-0000-0000-0000-000000000001"
        val dir = Files.createTempDirectory("troit-transfer").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        init {
            File(dir, "session.json").writeText(JSONObject().put("access", "fixture").put("refresh", "fixture")
                .put("userId", me).put("email", "fixture@example.invalid").toString())
        }
        val sync = Sync(dir, "PC", Library(File(dir, "library.json")), scope, http)
        override fun close() { scope.cancel(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun transferStartsLocalAudioOnlyAfterConfirmedPauseAndUsesReportedPosition() = runBlocking {
        TransferFixture().use { fixture ->
            val events = mutableListOf<String>()
            val base = Instant.now()
            var active = remote().copy(updatedAt = base.toString(), expiresAt = base.plusSeconds(45).toString())
            var commandId = ""
            val player = Player(song).apply { now = now.copy(playing = false) }
            val client = ConnectClient(fixture.sync, { path, body -> when {
                path.endsWith("state") -> active.toJson()
                path.endsWith("command_status") -> {
                    events += "pause acknowledged"
                    active = active.copy(playing = false, positionMs = 23_000)
                    JSONObject().put("id", commandId).put("status", "executed").toString()
                }
                else -> {
                    assertEquals(0, player.plays)
                    assertEquals("pause", JSONObject(body).getString("p_action"))
                    events += "pause requested"; commandId = JSONObject(body).getString("p_command_id")
                    JSONObject().put("id", commandId).put("status", "pending").toString()
                }
            } })
            val state = PlaybackRemoteState(fixture.sync, client, receiptPollMs = 1)
            state.refresh()
            assertTrue(continueConnectedHere(fixture.sync, state, player, ListeningPreferences()))
            assertEquals(listOf("pause requested", "pause acknowledged"), events)
            assertEquals(1, player.plays)
            assertEquals(23_000L, player.startedAt)
        }
    }

    @Test fun transferRefusesChangedRemoteSongsAndAccountGenerations() = runBlocking {
        for (changeAccount in listOf(false, true)) TransferFixture().use { fixture ->
            val base = Instant.now()
            var active = remote().copy(updatedAt = base.toString(), expiresAt = base.plusSeconds(45).toString())
            val player = Player(song).apply { now = now.copy(playing = false) }
            val client = ConnectClient(fixture.sync, { path, body ->
                if (path.endsWith("state")) active.toJson()
                else {
                    active = active.copy(playing = false, song = song.copy(url = song.url + "changed"))
                    if (changeAccount) {
                        File(fixture.dir, "session.json").delete()
                        Sync(fixture.dir, "Other instance", Library(File(fixture.dir, "other.json")), fixture.scope, fixture.http).signOut()
                    }
                    JSONObject().put("id", JSONObject(body).getString("p_command_id")).put("status", "executed").toString()
                }
            })
            val state = PlaybackRemoteState(fixture.sync, client, receiptPollMs = 1)
            state.refresh()
            assertFalse(continueConnectedHere(fixture.sync, state, player, ListeningPreferences()))
            assertEquals(0, player.plays)
        }
    }

    @Test fun transferHonorsPreferencesBeforePausingTheOtherDevice() = runBlocking {
        TransferFixture().use { fixture ->
            val base = Instant.now()
            val blocked = remote().copy(song = song.copy(title = "Hindi music"), updatedAt = base.toString(), expiresAt = base.plusSeconds(45).toString())
            var commands = 0
            val client = ConnectClient(fixture.sync, { path, _ ->
                if (path.endsWith("state")) blocked.toJson() else { commands++; "null" }
            })
            val player = Player(song)
            val state = PlaybackRemoteState(fixture.sync, client)
            state.refresh()
            assertFalse(continueConnectedHere(fixture.sync, state, player, ListeningPreferences(excludedLanguages = setOf("hi"))))
            assertEquals(0, commands)
            assertEquals(0, player.plays)
        }
    }

    @Test fun newerLocalPlaybackCannotBeOverwrittenByDelayedTransfer() = runBlocking {
        TransferFixture().use { fixture ->
            val base = Instant.now()
            var active = remote().copy(updatedAt = base.toString(), expiresAt = base.plusSeconds(45).toString())
            val player = Player(song).apply { now = now.copy(playing = false) }
            val replacement = song.copy(title = "Chosen locally", url = song.url + "local")
            val client = ConnectClient(fixture.sync, { path, body ->
                if (path.endsWith("state")) active.toJson()
                else {
                    player.play(listOf(replacement), 0)
                    active = active.copy(playing = false)
                    JSONObject().put("id", JSONObject(body).getString("p_command_id")).put("status", "executed").toString()
                }
            })
            val state = PlaybackRemoteState(fixture.sync, client)
            state.refresh()
            assertFalse(continueConnectedHere(fixture.sync, state, player, ListeningPreferences()))
            assertEquals(replacement, player.now.song)
            assertEquals(1, player.plays)
        }
    }
}
