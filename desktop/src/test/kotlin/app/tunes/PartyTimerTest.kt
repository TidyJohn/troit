package app.tunes

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class PartyTimerTest {
    private val user = "00000000-0000-0000-0000-000000000001"
    private val device = "00000000-0000-0000-0000-000000000010"
    private val runtime = "00000000-0000-0000-0000-000000000011"
    private val timerId = "00000000-0000-0000-0000-000000000020"
    private val start = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private val song = Song("Fixture song", "Fixture artist", "https://example.invalid/fixture", durationSeconds = 240)
    private class Player(song: Song) : MusicPlayer {
        override var now = NowPlaying(listOf(song), 0, playing = true)
        override var interactionRevision = 0L
        override var completedTrackRevision = 0L
        var stopAtEnd = false; var deadline: Long? = null; var pauses = 0
        override fun setStopAfterCurrent(enabled: Boolean) { stopAtEnd = enabled }
        override fun setSleepDeadline(deadlineMillis: Long?) { deadline = deadlineMillis }
        override fun positionMs() = 20_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) { interactionRevision++; now = NowPlaying(songs, index, true) }
        override fun toggle() { interactionRevision++; now = now.copy(playing = !now.playing) }
        override fun next() { interactionRevision++; now = now.copy(index = 1) }
        override fun previous() = Unit
        override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean {
            if (!allowed() || now.song?.url != expectedUrl || now.index != expectedIndex) return false
            if (action == "pause") { pauses++; now = now.copy(playing = false) }
            return true
        }
    }
    private class Fixture(val dir: File, val scope: CoroutineScope, val sync: Sync) : AutoCloseable { override fun close() { scope.cancel() } }
    private fun fixture(signedIn: Boolean = true): Fixture {
        val dir = Files.createTempDirectory("troit-party-timer").toFile()
        File(dir, "device.txt").writeText(device)
        if (signedIn) File(dir, "session.json").writeText(JSONObject().put("access", "fixture-access").put("refresh", "fixture-refresh")
            .put("userId", user).put("email", "fixture@example.invalid").toString())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Fixture").body("{}".toResponseBody("application/json".toMediaType())).build() }.build()
        return Fixture(dir, scope, Sync(dir, "Fixture PC", Library(File(dir, "library.json")), scope, http))
    }
    private fun active(time: Long = start) = ConnectSnapshot(device, runtime, "Fixture PC", song, true, 20_000, 1,
        Instant.ofEpochMilli(time).toString(), Instant.ofEpochMilli(time + 45_000).toString(), true)
    private fun timerRead(time: Long, kind: String = "minutes", deadline: Long = start + 60_000, revision: Long = 1, id: String = timerId): String =
        JSONObject().put("revision", revision).put("server_time", Instant.ofEpochMilli(time).toString()).put("timer", JSONObject()
            .put("id", id).put("kind", kind).put("deadline", if (kind == "minutes") Instant.ofEpochMilli(deadline).toString() else JSONObject.NULL)
            .put("track_url", song.url).put("queue_index", 0).put("device_id", device).put("device_name", "Fixture PC")).toString()
    private fun room(queue: JSONObject? = null) = JSONObject().put("id", timerId).put("code", "ABCD1234").put("name", "Fixture room")
        .put("host_id", user).put("host_name", "Host").put("is_host", false)
        .put("members", JSONArray().put(JSONObject().put("id", user).put("name", "Host").put("host", true)))
        .put("queue", queue ?: JSONObject.NULL)
    private fun queue(unchanged: Boolean = false) = JSONObject().put("device_id", device).put("instance_id", runtime)
        .put("revision", 3).put("queue", if (unchanged) JSONObject.NULL else Library.songsToJson(listOf(song, song)))
        .put("unchanged", unchanged).put("index", 0).put("position_ms", 20_000).put("playing", true).put("mode", "STOP")
        .put("updated_at", Instant.ofEpochMilli(start).toString()).put("playback_revision", 1).put("fresh", true)

    @Test fun partyConditionalReadsKeepDuplicateSongsWithoutDownloadingAgain() = runBlocking {
        fixture().use { f ->
            val revisions = mutableListOf<Long>()
            val client = PartyClient(f.sync, { _, body -> val revision = JSONObject(body).getLong("p_known_revision"); revisions += revision; room(queue(revision == 3L)).toString() })
            assertEquals(listOf(song, song), client.state()!!.queue!!.songs)
            assertEquals(listOf(song, song), client.state()!!.queue!!.songs)
            assertEquals(listOf(0L, 3L), revisions)
        }
    }
    @Test fun partyAccountChangeDiscardsInflightPrivateRoom() = runBlocking {
        fixture().use { f ->
            val ready = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val client = PartyClient(f.sync, { _, _ -> ready.complete(Unit); release.await(); room().toString() })
            val pending = async { runCatching { client.state() } }; ready.await(); f.sync.signOut(); release.complete(Unit)
            assertTrue(pending.await().isFailure)
        }
    }
    @Test fun guestCannotAskClientToPlayNextOrModifyHostControls() = runBlocking {
        fixture().use { f ->
            var additions = 0
            val client = PartyClient(f.sync, { path, _ -> if (path.endsWith("_add")) { additions++; error("Should not upload") } else room(queue()).toString() })
            val state = PartyState(f.sync, client); state.refresh()
            assertFalse(state.addSongs(listOf(song), playNext = true)); assertEquals(0, additions)
        }
    }
    @Test fun partyAdditionRetriesKeepUuidAndWaitForExecution() = runBlocking {
        fixture().use { f ->
            val ids = mutableListOf<String>(); var uploads = 0; var reads = 0
            val client = PartyClient(f.sync, { path, body -> when {
                path.endsWith("_state") -> room(queue()).toString()
                path.endsWith("_add") -> { val id = JSONObject(body).getString("p_command_id"); ids += id; if (++uploads == 1) throw java.io.IOException("Lost upload") else JSONObject().put("id", id).put("status", "executed").toString() }
                path.endsWith("_status") -> { reads++; "null" }
                else -> error("Unexpected fixture path")
            } })
            val state = PartyState(f.sync, client, confirmationTimeoutMs = 4_000); state.refresh()
            assertTrue(state.addSongs(listOf(song))); assertTrue(reads > 0)
            assertEquals(2, ids.size); assertEquals(ids.first(), ids.last()); assertFalse(state.pending)
            assertEquals("Song added to the host queue.", state.message)
        }
    }
    @Test fun guestLocalTimerPersistsForServiceAndFiresOnlyOnce() = runBlocking {
        fixture(false).use { f ->
            var time = start; val player = Player(song)
            val state = SleepTimerState(f.sync, player, clock = { time })
            assertTrue(state.setMinutes(15)); assertTrue(state.timer!!.local); assertEquals(start + 900_000, player.deadline)
            val service = SleepTimerReceiver(f.sync, player, clock = { time }); service.tick(null)
            time += 900_000; service.tick(null)
            assertEquals(1, player.pauses); assertFalse(player.now.playing)
            player.toggle(); service.tick(null); assertEquals(1, player.pauses)
            state.refresh(); assertNull(state.timer)
        }
    }
    @Test fun sharedTimerArmsNativeDeadlineAndOnlyActiveReceiverConsumesIt() = runBlocking {
        fixture().use { f ->
            var time = start; var claims = 0; val player = Player(song)
            val client = SleepTimerClient(f.sync, { path, _ -> if (path.endsWith("_state")) timerRead(time)
                else { claims++; JSONObject().put("track_url", song.url).put("queue_index", 0).toString() } })
            val receiver = SleepTimerReceiver(f.sync, player, client, { time })
            receiver.tick(active(time)); assertEquals(start + 60_000, player.deadline)
            time += 60_000; receiver.tick(active(time))
            assertEquals(1, claims); assertEquals(1, player.pauses)
            player.toggle(); receiver.tick(active(time)); assertEquals(1, player.pauses)
        }
    }
    @Test fun endTimerUsesNativeCompletionCounterEvenRepeatOneKeepsSameUrl() = runBlocking {
        fixture().use { f ->
            var completed = false; val player = Player(song)
            val client = SleepTimerClient(f.sync, { path, body -> if (path.endsWith("_state")) timerRead(start, "end_of_track")
                else { completed = JSONObject(body).getBoolean("p_completed"); JSONObject().put("track_url", song.url).put("queue_index", 0).toString() } })
            val receiver = SleepTimerReceiver(f.sync, player, client, { start }); receiver.tick(active())
            assertTrue(player.stopAtEnd); player.completedTrackRevision++; player.now = player.now.copy(playing = false)
            receiver.tick(active()); assertTrue(completed); assertEquals(1, player.pauses); assertFalse(player.stopAtEnd)
        }
    }
    @Test fun aNewLocalActionDuringTimerClaimPreventsLatePause() = runBlocking {
        fixture().use { f ->
            var time = start; val player = Player(song)
            val client = SleepTimerClient(f.sync, { path, _ -> if (path.endsWith("_state")) timerRead(time)
                else { player.interactionRevision++; JSONObject().put("track_url", song.url).put("queue_index", 0).toString() } })
            val receiver = SleepTimerReceiver(f.sync, player, client, { time }); receiver.tick(active(time))
            time += 60_000; receiver.tick(active(time)); assertEquals(0, player.pauses)
        }
    }
    @Test fun cancellingAnOldTimerKeepsNewTimerCreatedByAnotherDevice() = runBlocking {
        fixture().use { f ->
            val player = Player(song); val replacement = "00000000-0000-0000-0000-000000000021"
            val client = SleepTimerClient(f.sync, { path, _ -> if (path.endsWith("_state")) timerRead(start)
                else timerRead(start, deadline = start + 120_000, revision = 3, id = replacement) })
            val state = SleepTimerState(f.sync, player, client, { start }); state.refresh()
            assertTrue(state.cancel()); assertEquals(replacement, state.timer?.id); assertNotNull(player.deadline)
        }
    }
    @Test fun cachedAuthorizedCountdownStillFiresThroughTemporaryNetworkLoss() = runBlocking {
        fixture().use { f ->
            var time = start; var offline = false; val player = Player(song)
            val client = SleepTimerClient(f.sync, { _, _ -> if (offline) throw java.io.IOException("Offline fixture") else timerRead(time) })
            val receiver = SleepTimerReceiver(f.sync, player, client, { time }); receiver.tick(active(start))
            offline = true; time += 60_000; receiver.tick(active(start))
            assertEquals(1, player.pauses); player.toggle(); receiver.tick(active(start)); assertEquals(1, player.pauses)
        }
    }
    @Test fun localCancellationDuringAccountReadCannotRearmTheCancelledTimer() = runBlocking {
        localChangeDuringAccountRead(replace = false)
    }
    @Test fun localReplacementDuringAccountReadKeepsItsOwnDeadlineAndCompletionFence() = runBlocking {
        localChangeDuringAccountRead(replace = true)
    }
    private suspend fun localChangeDuringAccountRead(replace: Boolean) = coroutineScope {
        for (offline in listOf(false, true)) for (endOfTrack in listOf(false, true)) fixture().use { f ->
            var time = start; val player = Player(song)
            val missing = SleepTimerClient(f.sync, { _, _ -> throw AccountApiException(404, "PGRST202", "Fixture migration missing") })
            val state = SleepTimerState(f.sync, player, missing, { time })
            assertTrue(if (endOfTrack) state.setEndOfTrack() else state.setMinutes(15))
            val original = state.timer!!.id
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
            val client = SleepTimerClient(f.sync, { _, _ ->
                if (++reads == 2) { entered.complete(Unit); release.await() }
                if (offline && reads >= 2) throw java.io.IOException("Fixture connection lost")
                JSONObject().put("revision", 0).put("server_time", Instant.ofEpochMilli(time).toString()).put("timer", JSONObject.NULL).toString()
            })
            val receiver = SleepTimerReceiver(f.sync, player, client, { time })
            receiver.tick(active(time))
            val pending = async { receiver.tick(active(time)) }; entered.await()
            if (replace) {
                assertTrue(state.setMinutes(30)); assertNotEquals(original, state.timer!!.id)
            } else assertTrue(state.cancel())
            release.complete(Unit); pending.await()
            assertFalse("A late account read must not restore the old completion fence", player.stopAtEnd)
            assertEquals(if (replace) start + 1_800_000 else null, player.deadline)
            time += 900_000; player.completedTrackRevision++; receiver.tick(active(time))
            assertEquals("The cancelled/replaced local timer must never pause playback", 0, player.pauses)
            assertEquals(if (replace) start + 1_800_000 else null, player.deadline)
        }
    }
}
