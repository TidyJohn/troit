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
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

class EcosystemV2Test {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val sourceId = "00000000-0000-0000-0000-000000000002"
    private val sourceRuntime = "00000000-0000-0000-0000-000000000003"
    private val targetRuntime = "00000000-0000-0000-0000-000000000004"
    private val commandId = "00000000-0000-0000-0000-000000000005"
    private val time = Instant.parse("2026-11-15T01:00:00Z").toEpochMilli()
    private val songs = listOf(
        Song("First", "Artist", "https://example.invalid/first", durationSeconds = 180),
        Song("Repeated", "Artist", "https://example.invalid/repeated", durationSeconds = 200),
        Song("Repeated", "Artist", "https://example.invalid/repeated", durationSeconds = 200),
    )
    private fun fixture(): Pair<Sync, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val directory = Files.createTempDirectory("troit-ecosystem-v2").toFile()
        return Sync(directory, "PC", Library(File(directory, "library.json")), scope) to scope
    }
    private fun source(playing: Boolean = false) = ConnectSnapshot(sourceId, sourceRuntime, "Living room TV", songs[0],
        playing, 42_000, 7, Instant.ofEpochMilli(time).toString(), Instant.ofEpochMilli(time + 45_000).toString(), true)
    private fun queue(playing: Boolean = false, revision: Long = 3) = JSONObject()
        .put("device_id", sourceId).put("instance_id", sourceRuntime).put("revision", revision)
        .put("queue", Library.songsToJson(songs)).put("index", 0).put("position_ms", 42_000)
        .put("playing", playing).put("mode", "REPEAT_ALL").put("playback_revision", 7)
        .put("updated_at", Instant.ofEpochMilli(time).toString()).put("fresh", true)
    private fun device(id: String = sourceId, runtime: String = sourceRuntime, name: String = "Living room TV") = JSONObject()
        .put("device_id", id).put("instance_id", runtime).put("name", name).put("kind", "tv")
        .put("online", true).put("updated_at", Instant.ofEpochMilli(time).toString()).put("intent_revision", 0)
    private fun receipt(id: String, status: String) = JSONObject().put("id", id).put("status", status).toString()
    private fun publication(body: String): String = JSONObject(body).let { request -> queue(request.getBoolean("p_playing"))
        .put("device_id", request.getString("p_device_id")).put("instance_id", request.getString("p_instance_id"))
        .put("queue", request.getJSONArray("p_queue")).put("index", request.getInt("p_index"))
        .put("mode", request.getString("p_mode")).put("position_ms", request.getLong("p_position_ms")).toString() }
    private fun command(kind: String, intent: Long = 0, queue: List<Song> = songs) = JSONObject()
        .put("id", commandId).put("kind", kind).put("queue", Library.songsToJson(queue)).put("index", 0)
        .put("position_ms", 42_000).put("playing", true).put("mode", "REPEAT_ALL")
        .put("expected_url", songs[0].url).put("expected_index", 0).put("expected_queue", Library.songsToJson(songs))
        .put("expected_revision", 3).put("expected_intent_revision", intent)
        .put("source_device_id", sourceId).put("source_instance_id", sourceRuntime).put("source_playback_revision", 7).toString()

    private class Player : MusicPlayer {
        override var now = NowPlaying()
        override var interactionRevision = 0L
        var mode = PlaybackMode.STOP
        override val playbackMode get() = mode
        override fun setPlaybackMode(mode: PlaybackMode) { this.mode = mode }
        var applications = 0
        var position = 0L
        var expected: List<Song>? = null
        var onPrepare: (() -> Unit)? = null
        var preloadedSong: Song? = null
        override suspend fun preloadSource(song: Song) { preloadedSong = song }
        override fun positionMs() = position
        override fun play(songs: List<Song>, index: Int, positionMs: Long) {
            interactionRevision++; now = NowPlaying(songs, index, true); position = positionMs
        }
        override fun toggle() { interactionRevision++; now = now.copy(playing = !now.playing) }
        override fun next() {}
        override fun previous() {}
        override suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean {
            if (!allowed() || now.song?.url != expectedUrl || now.index != expectedIndex) return false
            if (action == "play") now = now.copy(playing = true)
            else if (action == "pause") now = now.copy(playing = false)
            else return false
            return true
        }
        override suspend fun replaceQueue(expectedQueue: List<Song>?, songs: List<Song>, index: Int, positionMs: Long,
            playing: Boolean, mode: PlaybackMode, allowed: () -> Boolean): Boolean {
            if (!allowed() || (expectedQueue != null && now.queue != expectedQueue)) return false
            applications++; expected = expectedQueue; this.mode = mode
            if (expectedQueue == null) { interactionRevision++; position = positionMs }
            now = now.copy(queue = songs, index = index, playing = playing)
            if (expectedQueue == null) onPrepare?.invoke()
            return true
        }
    }

    @Test fun conditionalQueueReadsKeepAllDuplicateOccurrencesAndClearCacheAcrossAccounts() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var owner = me
            val revisions = mutableListOf<Long>()
            val client = EcosystemClient(sync, { _, body ->
                val known = JSONObject(body).getLong("p_known_revision"); revisions += known
                queue().apply { if (known == 3L) { put("queue", JSONObject.NULL); put("unchanged", true); put("position_ms", 45_000) } }.toString()
            }, { owner })
            assertEquals(songs, client.queue()!!.songs)
            assertEquals(songs, client.queue()!!.songs)
            assertEquals(45_000, client.queue()!!.positionMs)
            owner = "00000000-0000-0000-0000-000000000009"
            assertEquals(songs, client.queue()!!.songs)
            assertEquals(listOf(0L, 3L, 3L, 0L), revisions)
        } finally { scope.cancel() }
    }

    @Test fun idleReceiverRegistersWithoutPublishingARestoredQueueAndDoesNotRepeatHeartbeats() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val calls = mutableListOf<String>()
            var clock = time
            val player = Player().apply { now = NowPlaying(songs, 0) }
            val client = EcosystemClient(sync, { path, _ -> calls += path.substringAfterLast('_'); "null" }, { me })
            val receiver = EcosystemReceiver(sync, player, "Android TV", client = client, accountId = { me }, clock = { clock })
            receiver.tick(targetRuntime, source())
            receiver.tick(targetRuntime, source())
            assertEquals(listOf("heartbeat", "claim", "claim"), calls)
            clock += 15_000
            receiver.tick(targetRuntime, source())
            assertEquals(2, calls.count { it == "heartbeat" })
            assertFalse(calls.any { it == "queue" })
        } finally { scope.cancel() }
    }

    @Test fun receiverPublishesWholeQueueOnlyOnContentOrModeChangeNotEveryTrackHeartbeat() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val uploads = mutableListOf<JSONObject>()
            val player = Player().apply { now = NowPlaying(songs, 0, true) }
            val client = EcosystemClient(sync, { path, body -> if (path.endsWith("publish_queue")) {
                uploads += JSONObject(body); publication(body)
            } else "null" }, { me })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, accountId = { me }, clock = { time })
            val active = source().copy(deviceId = sync.deviceId, instanceId = targetRuntime)
            receiver.tick(targetRuntime, active)
            player.now = player.now.copy(index = 1)
            receiver.tick(targetRuntime, active.copy(song = songs[1], queueIndex = 1))
            assertEquals(1, uploads.size)
            player.mode = PlaybackMode.REPEAT_ONE
            receiver.tick(targetRuntime, active.copy(song = songs[1], queueIndex = 1))
            assertEquals(2, uploads.size)
            assertEquals(songs, Library.songsFromJson(uploads[0].getJSONArray("p_queue")))
        } finally { scope.cancel() }
    }

    @Test fun transferAppliesFullQueuePositionAndModeAndPublishesBeforeAcknowledging() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val events = mutableListOf<String>()
            val player = Player()
            var activated = false
            val client = EcosystemClient(sync, { path, body ->
                events += path.substringAfter("troit_ecosystem_")
                when {
                    path.endsWith("claim") -> command("transfer")
                    path.endsWith("activate") -> {
                        assertFalse("The target must prepare silently before atomic activation", player.now.playing)
                        activated = true
                        source().copy(deviceId = sync.deviceId, instanceId = targetRuntime).toJson()
                    }
                    path.endsWith("publish_queue") -> publication(body)
                    path.endsWith("ack") -> { assertEquals("executed", JSONObject(body).getString("p_result")); "null" }
                    else -> "null"
                }
            }, { me })
            val connect = ConnectClient(sync, { path, _ ->
                if (path.endsWith("publish")) { events += "active-publish"; source().copy(deviceId = sync.deviceId, instanceId = targetRuntime).toJson() }
                else if (activated) source().copy(deviceId = sync.deviceId, instanceId = targetRuntime).toJson() else source().toJson()
            }, { me })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                accountId = { me }, clock = { time }, preferences = { ListeningPreferences() })
            receiver.tick(targetRuntime, source())
            assertEquals(songs, player.now.queue)
            assertEquals(42_000L, player.position)
            assertEquals(PlaybackMode.REPEAT_ALL, player.playbackMode)
            assertEquals(1, player.applications)
            assertNull(player.expected)
            assertEquals(listOf("heartbeat", "claim", "activate", "active-publish", "publish_queue", "ack"), events)
            assertEquals(1L, receiver.consumedInteractionRevision)
            assertTrue(receiver.acceptedTransfer)
        } finally { scope.cancel() }
    }

    @Test fun preloadReceiptMeansSourceIsAvailableWithoutTakingOwnershipOrChangingLocalQueue() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val player = Player()
            val events = mutableListOf<String>()
            val client = EcosystemClient(sync, { path, body ->
                events += path.substringAfter("troit_ecosystem_")
                when {
                    path.endsWith("claim") -> command("preload")
                    path.endsWith("ack") -> { assertEquals("executed", JSONObject(body).getString("p_result")); "null" }
                    else -> "null"
                }
            }, { me })
            val connect = ConnectClient(sync, { _, _ -> source(true).toJson() }, { me })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                accountId = { me }, clock = { time }, preferences = { ListeningPreferences() })
            receiver.tick(targetRuntime, source(true))
            assertEquals(songs.first(), player.preloadedSong)
            assertEquals(listOf("heartbeat", "claim", "ack"), events)
            assertTrue(receiver.awaitingTransfer)
            assertFalse(receiver.acceptedTransfer)
            assertNull(player.now.song)
            assertEquals(0L, player.interactionRevision)
        } finally { scope.cancel() }
    }

    @Test fun delayedTransferNeverOverwritesNewLocalPlaybackIntent() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val player = Player()
            val acks = mutableListOf<String>()
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("claim") -> { player.interactionRevision++; command("transfer") }
                path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                else -> "null"
            } }, { me })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, accountId = { me }, clock = { time })
            receiver.tick(targetRuntime, source())
            assertEquals(0, player.applications)
            assertEquals(listOf("stale"), acks)
        } finally { scope.cancel() }
    }

    @Test fun resumedSourceAndReceiverMusicFiltersRejectTransferBeforeNativePlayback() = runBlocking {
        val (sync, scope) = fixture()
        try {
            for (resumed in listOf(false, true)) {
                val player = Player()
                val acks = mutableListOf<String>()
                val payload = if (resumed) songs else songs.map { it.copy(title = "Hindi songs") }
                val client = EcosystemClient(sync, { path, body -> when {
                    path.endsWith("claim") -> command("transfer", queue = payload)
                    path.endsWith("ack") -> { acks += JSONObject(body).getString("p_result"); "null" }
                    else -> "null"
                } }, { me })
                val connect = ConnectClient(sync, { _, _ -> source(playing = resumed).toJson() }, { me })
                EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect, accountId = { me }, clock = { time },
                    preferences = { ListeningPreferences(excludedLanguages = setOf("hi")) }).tick(targetRuntime, source())
                assertEquals(0, player.applications)
                assertEquals(listOf("stale"), acks)
            }
        } finally { scope.cancel() }
    }

    @Test fun accountChangeDuringClaimDiscardsPlaybackAndAcknowledgement() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var owner: String? = me
            val player = Player()
            var acknowledgements = 0
            val client = EcosystemClient(sync, { path, _ -> when {
                path.endsWith("claim") -> { owner = null; command("transfer") }
                path.endsWith("ack") -> { acknowledgements++; "null" }
                else -> "null"
            } }, { owner })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, accountId = { owner }, clock = { time })
            receiver.tick(targetRuntime, source())
            assertEquals(0, player.applications)
            assertEquals(0, acknowledgements)
        } finally { scope.cancel() }
    }

    @Test fun upcomingEditPreservesCurrentPositionAndDuplicateQueueOccurrences() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val player = Player().apply { now = NowPlaying(songs, 0, true); position = 30_000 }
            val edited = listOf(songs[0], songs[2])
            val client = EcosystemClient(sync, { path, _ -> if (path.endsWith("claim")) command("edit", queue = edited) else "null" }, { me })
            val active = source(true).copy(deviceId = sync.deviceId, instanceId = targetRuntime)
            val connect = ConnectClient(sync, { _, _ -> active.toJson() }, { me })
            EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                accountId = { me }, clock = { time }, preferences = { ListeningPreferences() }).tick(targetRuntime, active)
            assertEquals(songs, player.expected)
            assertEquals(edited, player.now.queue)
            assertEquals(30_000L, player.position)
            assertEquals(0L, player.interactionRevision)
        } finally { scope.cancel() }
    }

    @Test fun uncertainEditUploadRetriesStableUuidAndOriginalObservedSnapshot() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val uploads = mutableListOf<JSONObject>()
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("devices") -> JSONArray().put(device()).toString()
                path.endsWith("queue") -> queue(true).toString()
                path.endsWith("edit") -> {
                    uploads += JSONObject(body)
                    if (uploads.size == 1) throw IOException("lost upload reply")
                    receipt(uploads.last().getString("p_command_id"), "executed")
                }
                path.endsWith("status") -> "null"
                else -> "null"
            } }, { me })
            val state = EcosystemState(sync, client = client, accountId = { me }, clock = { time }, receiptPollMs = 1)
            state.refresh()
            assertTrue(state.editQueue("remove", fromIndex = 2))
            assertEquals(2, uploads.size)
            assertEquals(uploads[0].toString(), uploads[1].toString())
            assertEquals(2, uploads[0].getInt("p_from"))
            assertEquals(queue().getString("updated_at"), uploads[0].getString("p_observed_at"))
            assertFalse(state.pending)
        } finally { scope.cancel() }
    }

    @Test fun timeoutRemainsPendingUntilTheReceiverReceiptConfirmsWithoutReupload() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var complete = false
            var uploaded: String? = null
            var uploads = 0
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("devices") -> JSONArray().put(device()).toString()
                path.endsWith("queue") -> queue(true).toString()
                path.endsWith("edit") -> { uploads++; uploaded = JSONObject(body).getString("p_command_id"); receipt(uploaded!!, "pending") }
                path.endsWith("status") -> receipt(uploaded!!, if (complete) "executed" else "claimed")
                else -> "null"
            } }, { me })
            val state = EcosystemState(sync, client = client, accountId = { me }, clock = { time }, confirmationTimeoutMs = 5, receiptPollMs = 1)
            state.refresh()
            assertFalse(state.editQueue("remove", fromIndex = 2))
            assertTrue(state.pending)
            assertFalse(state.editQueue("remove", fromIndex = 1))
            complete = true; state.refresh()
            assertFalse(state.pending)
            assertEquals(1, uploads)
            assertEquals("Playback updated.", state.message)
        } finally { scope.cancel() }
    }

    @Test fun clickWaitsForInFlightRefreshAndCannotQueueMultipleEdits() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var blocked = false
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var uploads = 0
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("devices") -> { if (blocked) { entered.complete(Unit); release.await() }; JSONArray().put(device()).toString() }
                path.endsWith("queue") -> queue(true).toString()
                path.endsWith("edit") -> { uploads++; receipt(JSONObject(body).getString("p_command_id"), "executed") }
                else -> "null"
            } }, { me })
            val state = EcosystemState(sync, client = client, accountId = { me }, clock = { time }, receiptPollMs = 1)
            state.refresh(); blocked = true
            val refresh = async { state.refresh() }; entered.await()
            val edit = async { state.editQueue("remove", fromIndex = 2) }
            kotlinx.coroutines.yield()
            assertTrue(state.busy)
            assertFalse(state.editQueue("remove", fromIndex = 1))
            blocked = false; release.complete(Unit); refresh.await()
            assertTrue(edit.await()); assertEquals(1, uploads)
        } finally { scope.cancel() }
    }

    @Test fun switchWaitsForConfirmedPauseAndRetainsTargetIntentCapturedBeforeThePause() = runBlocking {
        val (sync, scope) = fixture()
        try {
            for (targetChanged in listOf(false, true)) {
                var paused = false
                var targetIntent = 4L
                val events = mutableListOf<String>()
                val connect = ConnectClient(sync, { path, body -> when {
                    path.endsWith("state") -> source(playing = !paused).toJson()
                    path.endsWith("command") -> {
                        events += "pause"; assertEquals("pause", JSONObject(body).getString("p_action"))
                        paused = true
                        if (targetChanged) targetIntent++
                        receipt(JSONObject(body).getString("p_command_id"), "executed")
                    }
                    else -> "null"
                } }, { me })
                val remote = PlaybackRemoteState(sync, connect, { me }, { time }, receiptPollMs = 1)
                val client = EcosystemClient(sync, { path, body -> when {
                    path.endsWith("devices") -> JSONArray().put(device()).put(device(sync.deviceId, targetRuntime, "My PC")
                        .put("intent_revision", targetIntent)).toString()
                    path.endsWith("queue") -> queue(playing = !paused).toString()
                    path.endsWith("transfer") -> {
                        events += "transfer"
                        assertTrue("Never enqueue a switch while the source is still playing", paused)
                        val payload = JSONObject(body)
                        assertEquals(4L, payload.getLong("p_expected_target_intent_revision"))
                        if (payload.getLong("p_expected_target_intent_revision") != targetIntent)
                            throw AccountApiException(409, "40001", "The target user chose another song")
                        receipt(payload.getString("p_command_id"), "executed")
                    }
                    else -> "null"
                } }, { me })
                val state = EcosystemState(sync, remote, client, { me }, { time }, receiptPollMs = 1)
                state.refresh()
                val target = state.devices.first { it.id == sync.deviceId }
                assertEquals(!targetChanged, state.transferTo(target))
                assertEquals(listOf("pause", "transfer"), events)
                assertFalse(state.pending)
            }
        } finally { scope.cancel() }
    }

    @Test fun sourceTrackChangeDuringPausePreventsAFullQueueSwitch() = runBlocking {
        val (sync, scope) = fixture()
        try {
            var paused = false
            var transfers = 0
            val connect = ConnectClient(sync, { path, body -> when {
                path.endsWith("state") -> source(playing = !paused).toJson()
                path.endsWith("command") -> { paused = true; receipt(JSONObject(body).getString("p_command_id"), "executed") }
                else -> "null"
            } }, { me })
            val remote = PlaybackRemoteState(sync, connect, { me }, { time }, receiptPollMs = 1)
            val client = EcosystemClient(sync, { path, _ -> when {
                path.endsWith("devices") -> JSONArray().put(device()).put(device(sync.deviceId, targetRuntime, "My PC")).toString()
                path.endsWith("queue") -> queue(playing = !paused).apply { if (paused) put("index", 1).put("playback_revision", 8) }.toString()
                path.endsWith("transfer") -> { transfers++; receipt(commandId, "executed") }
                else -> "null"
            } }, { me })
            val state = EcosystemState(sync, remote, client, { me }, { time }, receiptPollMs = 1)
            state.refresh()
            assertFalse(state.transferTo(state.devices.first { it.id == sync.deviceId }))
            assertEquals(0, transfers)
        } finally { scope.cancel() }
    }

    @Test fun destinationPreloadsWhileSourcePlaysAndFailureLeavesItPlaying() = runBlocking {
        val (sync, scope) = fixture()
        try {
            for (ready in listOf(true, false)) {
                val events = mutableListOf<String>()
                var paused = false
                val connect = ConnectClient(sync, { path, body -> when {
                    path.endsWith("state") -> source(playing = !paused).toJson()
                    path.endsWith("command") -> {
                        events += "pause"; paused = true
                        receipt(JSONObject(body).getString("p_command_id"), "executed")
                    }
                    else -> "null"
                } }, { me })
                val client = EcosystemClient(sync, { path, body -> when {
                    path.endsWith("devices") -> JSONArray().put(device()).put(device(sync.deviceId, targetRuntime, "PC")
                        .put("preload_instance_id", targetRuntime)).toString()
                    path.endsWith("queue") -> queue(playing = !paused).toString()
                    path.endsWith("preload") -> {
                        events += "resolve"
                        assertFalse(paused)
                        receipt(JSONObject(body).getString("p_command_id"), if (ready) "executed" else "failed")
                    }
                    path.endsWith("transfer") -> {
                        events += "transfer"
                        receipt(JSONObject(body).getString("p_command_id"), "executed")
                    }
                    else -> error("Unexpected RPC $path")
                } }, { me })
                val remote = PlaybackRemoteState(sync, connect, { me }, { time }, receiptPollMs = 1)
                val state = EcosystemState(sync, remote, client, { me }, { time }, receiptPollMs = 1)
                state.refresh()
                assertEquals(ready, state.transferTo(state.devices.last()))
                assertEquals(if (ready) listOf("resolve", "pause", "transfer") else listOf("resolve"), events)
            }
        } finally { scope.cancel() }
    }

    @Test fun rejectedOrPrematureQueuePublicationIsRetriedAfterTheActiveTrackCatchesUp() = runBlocking {
        val (sync, scope) = fixture()
        try {
            val changed = songs.mapIndexed { index, song -> if (index == 0) song.copy(title = "New first", url = "https://example.invalid/new") else song }
            val player = Player().apply { now = NowPlaying(changed, 0, true) }
            var uploads = 0
            val client = EcosystemClient(sync, { path, body -> when {
                path.endsWith("publish_queue") -> {
                    uploads++
                    if (uploads == 1) queue(true).put("device_id", sync.deviceId).put("instance_id", targetRuntime).toString()
                    else publication(body)
                }
                else -> "null"
            } }, { me })
            val receiver = EcosystemReceiver(sync, player, "PC", client = client, accountId = { me }, clock = { time })
            val old = source(true).copy(deviceId = sync.deviceId, instanceId = targetRuntime)
            receiver.tick(targetRuntime, old)
            assertEquals("An old v1 track cannot validate a new native queue", 0, uploads)
            val active = old.copy(song = changed[0])
            receiver.tick(targetRuntime, active)
            receiver.tick(targetRuntime, active)
            receiver.tick(targetRuntime, active)
            assertEquals("A rejected upload retries once; an accepted upload is then cached", 2, uploads)
        } finally { scope.cancel() }
    }

    @Test fun sourceResumeDuringPreparationOrActivationNeverStartsOrRetakesTargetAudio() = runBlocking {
        val (sync, scope) = fixture()
        try {
            for (stage in listOf("prepare", "after-activation", "after-play")) {
                var prepared = false
                var activated = false
                var published = false
                val player = Player().apply { onPrepare = { prepared = true } }
                val acknowledgements = mutableListOf<String>()
                val client = EcosystemClient(sync, { path, body -> when {
                    path.endsWith("claim") -> command("transfer")
                    path.endsWith("activate") -> {
                        assertTrue(prepared)
                        assertFalse(player.now.playing)
                        activated = true
                        if (stage == "prepare") "null" else source().copy(deviceId = sync.deviceId, instanceId = targetRuntime).toJson()
                    }
                    path.endsWith("ack") -> { acknowledgements += JSONObject(body).getString("p_result"); "null" }
                    else -> "null"
                } }, { me })
                val connect = ConnectClient(sync, { path, body -> when {
                    path.endsWith("publish") -> {
                        published = true
                        assertFalse("A transfer must never unconditionally reclaim after activation", JSONObject(body).getBoolean("p_takeover"))
                        source(true).toJson()
                    }
                    activated && stage == "after-play" -> source().copy(deviceId = sync.deviceId, instanceId = targetRuntime).toJson()
                    activated -> source(true).toJson()
                    else -> source().toJson()
                } }, { me })
                val receiver = EcosystemReceiver(sync, player, "PC", client = client, connectClient = connect,
                    accountId = { me }, clock = { time }, preferences = { ListeningPreferences() })
                receiver.tick(targetRuntime, source())
                assertFalse("The target stays paused after a newer source intent at $stage", player.now.playing)
                assertEquals(listOf("stale"), acknowledgements)
                assertEquals(stage == "after-play", published)
            }
        } finally { scope.cancel() }
    }
}
