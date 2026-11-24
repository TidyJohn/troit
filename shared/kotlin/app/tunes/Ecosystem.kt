package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class EcosystemDevice(
    val id: String, val instanceId: String, val name: String, val kind: String,
    val online: Boolean, val updatedAt: String, val intentRevision: Long = 0,
    val supportsPreload: Boolean = false,
) {
    fun isOnline(atMillis: Long = System.currentTimeMillis()): Boolean = online && runCatching {
        atMillis - Instant.parse(updatedAt).toEpochMilli() < 45_000L
    }.getOrDefault(false)

    internal companion object {
        fun decode(json: JSONObject) = EcosystemDevice(json.getString("device_id"), json.getString("instance_id"),
            json.getString("name"), json.getString("kind"), json.getBoolean("online"), json.getString("updated_at"),
            json.optLong("intent_revision"), json.optString("preload_instance_id") == json.getString("instance_id"))
    }
}

data class EcosystemQueue(
    val deviceId: String, val instanceId: String, val deviceName: String,
    val songs: List<Song>, val index: Int, val positionMs: Long, val playing: Boolean,
    val mode: PlaybackMode, val revision: Long, val updatedAt: String,
    val playbackRevision: Long = 0, val fresh: Boolean = true,
) {
    val song get() = songs.getOrNull(index)
    fun isFresh(atMillis: Long = System.currentTimeMillis()): Boolean = fresh && runCatching {
        atMillis - Instant.parse(updatedAt).toEpochMilli() < 45_000L
    }.getOrDefault(false)

    internal companion object {
        fun decode(json: JSONObject, devices: List<EcosystemDevice> = emptyList(), cachedSongs: List<Song>? = null) = EcosystemQueue(
            json.getString("device_id"), json.getString("instance_id"),
            devices.firstOrNull { it.id == json.getString("device_id") }?.name ?: json.optString("device_name", "Troit"),
            cachedSongs ?: Library.songsFromJson(json.getJSONArray("queue")), json.getInt("index"), json.getLong("position_ms"),
            json.getBoolean("playing"), PlaybackMode.valueOf(json.getString("mode")), json.getLong("revision"),
            json.getString("updated_at"), json.optLong("playback_revision"), json.optBoolean("fresh", true),
        )
    }
}

internal data class EcosystemCommand(
    val id: String, val kind: String, val songs: List<Song>, val index: Int, val positionMs: Long,
    val playing: Boolean, val mode: PlaybackMode, val expectedUrl: String?, val expectedIndex: Int,
    val expectedQueue: List<Song>, val expectedRevision: Long, val expectedIntentRevision: Long,
    val sourceDeviceId: String?, val sourceInstanceId: String?, val sourcePlaybackRevision: Long,
    val partyId: String? = null,
)

/** Each RPC is scoped to the signed-in account; delayed replies can never cross an account change. */
class EcosystemClient(
    private val sync: Sync,
    private val request: ConnectTransport = { path, body -> sync.accountRequest(path, "POST", body, maximumRequestBytes = 2_000_000) },
    private val accountId: () -> String? = { sync.userId },
) {
    private var cachedQueue: EcosystemQueue? = null
    private var cachedOwner: String? = null
    private var cachedGeneration = Long.MIN_VALUE
    private var preloadHeartbeatAvailable = true
    private suspend fun rpc(name: String, body: JSONObject = JSONObject()): String {
        val owner = accountId() ?: throw IllegalStateException("Sign in to connect your devices.")
        val generation = sync.accountGeneration
        val response = request("rpc/troit_ecosystem_$name", body.toString())
        check(accountId() == owner && sync.accountGeneration == generation) { "The account changed. Refresh your devices." }
        return response
    }

    suspend fun devices(): List<EcosystemDevice> = JSONArray(rpc("devices")).let { rows ->
        require(rows.length() <= 20) { "Too many connected devices." }
        (0 until rows.length()).map { EcosystemDevice.decode(rows.getJSONObject(it)) }
    }

    suspend fun queue(devices: List<EcosystemDevice> = emptyList()): EcosystemQueue? {
        val owner = accountId(); val generation = sync.accountGeneration
        if (owner != cachedOwner || generation != cachedGeneration) {
            cachedQueue = null; cachedOwner = owner; cachedGeneration = generation
        }
        val previous = cachedQueue
        val response = rpc("queue", JSONObject().put("p_known_revision", previous?.revision ?: 0))
        if (response.trim() == "null") { cachedQueue = null; return null }
        val json = JSONObject(response)
        var cachedSongs: List<Song>? = null
        if (json.optBoolean("unchanged")) {
            if (previous != null && previous.revision == json.getLong("revision") &&
                previous.deviceId == json.getString("device_id") && previous.instanceId == json.getString("instance_id")) {
                cachedSongs = previous.songs
            } else {
                val full = rpc("queue", JSONObject().put("p_known_revision", 0))
                if (full.trim() == "null") { cachedQueue = null; return null }
                val fetched = EcosystemQueue.decode(JSONObject(full), devices)
                cachedQueue = fetched
                return fetched
            }
        }
        val fetched = EcosystemQueue.decode(json, devices, cachedSongs)
        require(fetched.songs.size <= MAX_QUEUE) { "The shared queue is too large." }
        cachedQueue = fetched
        return fetched
    }

    suspend fun rename(device: EcosystemDevice, name: String) {
        val clean = name.trim()
        require(clean.length in 1..64) { "Use between 1 and 64 characters for the device name." }
        rpc("rename", JSONObject().put("p_device_id", device.id).put("p_name", clean))
    }

    internal suspend fun heartbeat(deviceId: String, instanceId: String, name: String, kind: String, intent: Long) {
        val body = JSONObject().put("p_device_id", deviceId).put("p_instance_id", instanceId)
            .put("p_name", name.take(64)).put("p_kind", kind).put("p_intent_revision", intent.coerceIn(0, 9_000_000_000_000_000L))
        if (preloadHeartbeatAvailable) {
            try {
                rpc("heartbeat", body.put("p_supports_preload", true))
                return
            } catch (failure: AccountApiException) {
                if (!failure.setupRequired) throw failure
                preloadHeartbeatAvailable = false
                body.remove("p_supports_preload")
            }
        }
        rpc("heartbeat", body)
    }

    internal suspend fun publishQueue(deviceId: String, instanceId: String, now: NowPlaying, positionMs: Long, mode: PlaybackMode): Boolean {
        require(now.queue.size <= MAX_QUEUE) { "Shared queues support up to $MAX_QUEUE songs." }
        val response = rpc("publish_queue", JSONObject().put("p_device_id", deviceId).put("p_instance_id", instanceId)
            .put("p_queue", Library.songsToJson(now.queue)).put("p_index", now.index)
            .put("p_position_ms", positionMs.coerceIn(0, 604800000L)).put("p_playing", now.playing).put("p_mode", mode.name))
        if (response.trim() == "null") return false
        val returned = EcosystemQueue.decode(JSONObject(response))
        return returned.deviceId == deviceId && returned.instanceId == instanceId && returned.songs == now.queue &&
            returned.mode == mode && returned.index == now.index
    }

    internal suspend fun edit(id: String, queue: EcosystemQueue, operation: String, from: Int, to: Int, songs: List<Song>): ConnectCommandResult {
        UUID.fromString(id)
        require(operation in setOf("add_next", "add_last", "remove", "move")) { "Unsupported queue action." }
        return result(rpc("edit", JSONObject().put("p_command_id", id).put("p_device_id", queue.deviceId)
            .put("p_instance_id", queue.instanceId).put("p_expected_revision", queue.revision)
            .put("p_operation", operation).put("p_from", from).put("p_to", to).put("p_songs", Library.songsToJson(songs))
            .put("p_observed_at", queue.updatedAt)))
    }

    internal suspend fun transfer(id: String, queue: EcosystemQueue, target: EcosystemDevice): ConnectCommandResult {
        return requestTransfer("transfer", id, queue, target)
    }

    internal suspend fun preload(id: String, queue: EcosystemQueue, target: EcosystemDevice): ConnectCommandResult {
        return requestTransfer("preload", id, queue, target)
    }

    private suspend fun requestTransfer(method: String, id: String, queue: EcosystemQueue, target: EcosystemDevice): ConnectCommandResult {
        UUID.fromString(id)
        return result(rpc(method, JSONObject().put("p_command_id", id).put("p_device_id", queue.deviceId)
            .put("p_instance_id", queue.instanceId).put("p_expected_revision", queue.revision)
            .put("p_target_device_id", target.id).put("p_target_instance_id", target.instanceId)
            .put("p_observed_at", queue.updatedAt).put("p_expected_target_intent_revision", target.intentRevision)))
    }

    internal suspend fun status(id: String): ConnectCommandResult? = rpc("status", JSONObject().put("p_command_id", id)).let {
        if (it.trim() == "null") null else result(it)
    }

    internal suspend fun claim(deviceId: String, instanceId: String): EcosystemCommand? {
        val response = rpc("claim", JSONObject().put("p_device_id", deviceId).put("p_instance_id", instanceId))
        if (response.trim() == "null") return null
        val json = JSONObject(response)
        return EcosystemCommand(json.getString("id"), json.getString("kind"), Library.songsFromJson(json.getJSONArray("queue")),
            json.getInt("index"), json.getLong("position_ms"), json.getBoolean("playing"), PlaybackMode.valueOf(json.getString("mode")),
            if (json.isNull("expected_url")) null else json.getString("expected_url"), json.optInt("expected_index", -1),
            Library.songsFromJson(json.optJSONArray("expected_queue") ?: JSONArray()), json.getLong("expected_revision"),
            json.getLong("expected_intent_revision"), json.optString("source_device_id").takeIf { it.isNotBlank() && it != "null" },
            json.optString("source_instance_id").takeIf { it.isNotBlank() && it != "null" }, json.optLong("source_playback_revision"),
            json.optString("party_id").takeIf { it.isNotBlank() && it != "null" })
    }

    internal suspend fun ack(deviceId: String, instanceId: String, command: EcosystemCommand, status: String) {
        rpc("ack", JSONObject().put("p_command_id", command.id).put("p_device_id", deviceId)
            .put("p_instance_id", instanceId).put("p_result", status))
    }

    internal suspend fun activate(deviceId: String, instanceId: String, command: EcosystemCommand): ConnectSnapshot? {
        val response = rpc("activate", JSONObject().put("p_command_id", command.id).put("p_device_id", deviceId)
            .put("p_instance_id", instanceId))
        return if (response.trim() == "null") null else ConnectSnapshot.fromJson(response)
    }

    private fun result(text: String) = JSONObject(text).let { ConnectCommandResult(it.getString("id"), it.getString("status")) }
    companion object { const val MAX_QUEUE = 1000 }
}

/** Account-owned device/queue controller, observed by the same UI on phone, PC and TV. */
class EcosystemState(
    private val sync: Sync,
    private val remote: PlaybackRemoteState = PlaybackRemoteState(sync),
    private val client: EcosystemClient = EcosystemClient(sync),
    private val accountId: () -> String? = { sync.userId },
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmationTimeoutMs: Long = 12_000L,
    private val receiptPollMs: Long = 750L,
) {
    private data class Waiting(val id: String, val startedAt: Long)
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var waiting: Waiting? = null
    private var attempt: Any? = null
    private var failures = 0
    var devices by mutableStateOf<List<EcosystemDevice>>(emptyList()); private set
    var queue by mutableStateOf<EcosystemQueue?>(null); private set
    var busy by mutableStateOf(false); private set
    var pending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var setupRequired by mutableStateOf(false); private set
    val pollDelayMs: Long get() = when {
        accountId() == null -> 15_000L
        setupRequired -> 60_000L
        failures > 0 -> (2_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000)
        pending -> 1_000L
        else -> 3_000L
    }

    fun resetForAccount() {
        val account = accountId(); val epoch = sync.accountGeneration
        if (owner == account && generation == epoch) return
        owner = account; generation = epoch
        waiting = null; attempt = null; devices = emptyList(); queue = null
        busy = false; pending = false; error = null; message = null; failures = 0; setupRequired = false
    }

    private fun current(account: String?, epoch: Long) = account != null && owner == account && accountId() == account &&
        generation == epoch && sync.accountGeneration == epoch

    suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            resetForAccount(); val account = owner ?: return; val epoch = generation
            waiting?.let {
                val receipt = client.status(it.id)
                if (!current(account, epoch)) { resetForAccount(); return }
                if (receipt != null && !receipt.pending) finish(receipt)
                else if (clock() - it.startedAt >= 35_000) {
                    waiting = null; pending = false; message = "The device did not confirm the request. Refresh before trying again."
                }
            }
            read(account, epoch)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (owner != accountId() || generation != sync.accountGeneration) resetForAccount() else failed(failure)
        } finally { lock.unlock() }
    }

    private suspend fun read(account: String, epoch: Long) {
        val found = client.devices()
        val active = client.queue(found)
        if (!current(account, epoch)) { resetForAccount(); return }
        devices = found; queue = active; failures = 0; error = null; setupRequired = false
    }

    suspend fun renameDevice(device: EcosystemDevice, name: String): Boolean = mutate {
        client.rename(device, name)
        message = "Device renamed."
        true
    }

    suspend fun editQueue(operation: String, fromIndex: Int = -1, toIndex: Int = -1, songs: List<Song> = emptyList()): Boolean {
        val expected = queue ?: return false
        return mutate {
            val live = client.queue(devices)
            require(sameQueue(expected, live)) { "The queue changed. Refresh and try again." }
            require(expected.isFresh(clock())) { "The player is offline. Open Troit there to edit the queue." }
            require(operation in setOf("add_next", "add_last", "remove", "move")) { "Unsupported queue action." }
            if (operation == "remove" || operation == "move") require(fromIndex in expected.index + 1 until expected.songs.size) {
                "Only upcoming songs can be edited."
            }
            if (operation == "move") require(toIndex in expected.index + 1 until expected.songs.size) { "Choose an upcoming queue position." }
            if (operation.startsWith("add_")) require(songs.isNotEmpty() && expected.songs.size + songs.size <= EcosystemClient.MAX_QUEUE) {
                "Shared queues support up to ${EcosystemClient.MAX_QUEUE} songs."
            }
            confirm { id -> client.edit(id, expected, operation, fromIndex, toIndex, songs) }
        }
    }

    suspend fun transferTo(device: EcosystemDevice, localDestination: MusicPlayer? = null): Boolean {
        val expected = queue ?: return false
        return mutate {
            val target = requireNotNull(client.devices().firstOrNull { it.id == device.id && it.instanceId == device.instanceId && it.isOnline(clock()) }) {
                "That device is offline. Open Troit there first."
            }
            require(device.id != expected.deviceId) { "Playback is already on this device." }
            if (!target.supportsPreload && target.id == sync.deviceId && localDestination != null) {
                message = "Preparing ${target.name}…"
                val intent = localDestination.interactionRevision
                require(intent == target.intentRevision) { "Playback changed on this device. Try again." }
                val song = requireNotNull(expected.song)
                val ready = withTimeoutOrNull(confirmationTimeoutMs) { localDestination.preloadSource(song); true }
                require(ready == true && intent == localDestination.interactionRevision) {
                    "This device couldn't prepare the song. Try again."
                }
            } else if (target.supportsPreload) {
                message = "Preparing ${target.name}…"
                val id = UUID.randomUUID().toString()
                val result = withTimeoutOrNull(confirmationTimeoutMs) {
                    var receipt = client.preload(id, expected, target)
                    while (receipt.pending) {
                        delay(receiptPollMs)
                        receipt = client.status(id) ?: return@withTimeoutOrNull false
                    }
                    receipt.executed
                }
                require(result == true) { "${target.name} couldn't prepare this song. Try again." }
            }
            val before = client.queue(devices)
            require(sameQueue(expected, before) && before?.isFresh(clock()) == true) { "Playback changed. Refresh before switching devices." }
            // Capture the precise source before the pause: never pause a new song that appeared during refresh.
            remote.refresh()
            val source = remote.snapshot
            require(source != null && source.deviceId == expected.deviceId && source.instanceId == expected.instanceId &&
                source.song.url == expected.song?.url && source.queueIndex == expected.index && source.revision == expected.playbackRevision) {
                "Playback changed. Refresh before switching devices."
            }
            if (target.id == sync.deviceId && localDestination != null) {
                require(localDestination.interactionRevision == target.intentRevision) { "Playback changed on this device. Try again." }
            }
            // The queue read below includes the authoritative paused position.
            if (source.playing) require(remote.sendControl("pause", refreshAfterConfirmation = false)) {
                "The current device has not confirmed its pause. Playback was not moved."
            }
            val paused = if (source.playing) client.queue(devices) else before
            require(paused != null && sameQueue(expected, paused) && !paused.playing && paused.isFresh(clock())) {
                "Playback changed before the switch could finish."
            }
            confirm { id -> client.transfer(id, paused, target) }
        }
    }

    private suspend fun mutate(work: suspend () -> Boolean): Boolean {
        resetForAccount()
        if (busy || pending || owner == null) return false
        val account = owner!!; val epoch = generation; val token = Any()
        attempt = token; busy = true; var locked = false
        try {
            lock.lock(); locked = true
            if (!current(account, epoch) || attempt !== token) { resetForAccount(); return false }
            error = null
            val applied = work()
            if (current(account, epoch)) try { read(account, epoch) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (current(account, epoch)) failed(failure) else resetForAccount() }
            else resetForAccount()
            return applied && current(account, epoch)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (!current(account, epoch)) resetForAccount() else {
                message = null
                failed(failure)
                // Validation failures never uploaded a request. Uncertain transport outcomes in confirm retain it.
                if (failure is IllegalArgumentException) error = failure.message
            }
            return false
        } finally {
            if (attempt === token) { busy = false; attempt = null }
            if (locked) lock.unlock()
        }
    }

    private suspend fun confirm(upload: suspend (String) -> ConnectCommandResult): Boolean {
        val account = owner; val epoch = generation
        val request = Waiting(UUID.randomUUID().toString(), clock())
        waiting = request; pending = true; message = "Waiting for the playback device…"
        val receipt = withTimeoutOrNull(confirmationTimeoutMs) {
            var needsUpload = true
            while (current(account, epoch)) {
                try {
                    val result = if (needsUpload) upload(request.id) else client.status(request.id)
                    if (!current(account, epoch)) return@withTimeoutOrNull null
                    needsUpload = result == null
                    if (result != null && !result.pending) return@withTimeoutOrNull result
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    if (failure is AccountApiException) { waiting = null; pending = false; throw failure }
                    if (!current(account, epoch)) return@withTimeoutOrNull null
                    needsUpload = false
                }
                delay(receiptPollMs)
            }
            null
        }
        if (!current(account, epoch)) return false
        if (receipt == null) { message = "Still waiting for device confirmation."; return false }
        finish(receipt)
        return receipt.executed
    }

    private fun finish(result: ConnectCommandResult) {
        waiting = null; pending = false
        message = when (result.status) {
            "executed" -> "Playback updated."
            "stale" -> "The queue or device changed. Refresh and try again."
            "expired" -> "The device did not respond in time."
            else -> "The device could not apply this request."
        }
    }

    private fun failed(failure: Exception) {
        failures++
        setupRequired = failure is AccountApiException && failure.setupRequired
        error = if (setupRequired) "Device switching and shared queues need the Troit ecosystem database update."
            else "Couldn't update connected devices. Troit will retry automatically."
    }

    private fun sameQueue(expected: EcosystemQueue, actual: EcosystemQueue?) = actual != null &&
        actual.deviceId == expected.deviceId && actual.instanceId == expected.instanceId && actual.revision == expected.revision &&
        actual.playbackRevision == expected.playbackRevision && actual.index == expected.index && actual.song?.url == expected.song?.url
}

/** The one receiver belongs to the actual audio engine, including idle devices with no previous queue. */
class EcosystemReceiver(
    private val sync: Sync, private val player: MusicPlayer, private val deviceName: String,
    private val kind: String = when { deviceName.contains("TV", true) -> "tv"; deviceName.contains("PC", true) -> "pc"; else -> "phone" },
    private val client: EcosystemClient = EcosystemClient(sync),
    private val connectClient: ConnectClient = ConnectClient(sync),
    private val accountId: () -> String? = { sync.userId },
    private val clock: () -> Long = System::currentTimeMillis,
    private val preferences: suspend () -> ListeningPreferences = {
        withContext(Dispatchers.IO) { RegionPreferences(File(sync.dataDirectory, "listening-preferences.json")).settings }
    },
    private val incomingTimer: suspend (MusicPlayer, () -> Boolean) -> Boolean = { target, allowed ->
        if (sync.userId == null) true else SleepTimerClient(sync).armIncoming(target, allowed)
    },
    private val partyCommandAllowed: suspend (String) -> Boolean = { PartyClient(sync).validateCommand(it) },
    private val monotonicClock: () -> Long = System::nanoTime,
) {
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var runtime: String? = null
    private var heartbeatAt = Long.MIN_VALUE
    private var sentIntent = Long.MIN_VALUE
    private var sentQueue: Pair<List<Song>, PlaybackMode>? = null
    private var activeBefore = false
    private var retryAt = 0L
    private var fastPollUntil = 0L
    internal val awaitingTransfer get() = clock() < fastPollUntil
    var setupRequired by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    /** Only this exact revision was generated by preparing a remote transfer, rather than local UI. */
    var consumedInteractionRevision: Long? = null; private set
    var acceptedTransfer: Boolean = false; private set

    suspend fun tick(instanceId: String, active: ConnectSnapshot?) {
        if (!lock.tryLock()) return
        try {
            consumedInteractionRevision = null; acceptedTransfer = false
            val account = accountId(); val epoch = sync.accountGeneration
            if (owner != account || generation != epoch || runtime != instanceId) {
                owner = account; generation = epoch; runtime = instanceId
                heartbeatAt = Long.MIN_VALUE; sentIntent = Long.MIN_VALUE; sentQueue = null; activeBefore = false
                setupRequired = false; error = null; retryAt = 0; fastPollUntil = 0
            }
            if (account == null || clock() < retryAt) return
            fun allowed() = accountId() == account && sync.accountGeneration == epoch && runtime == instanceId
            val intent = player.interactionRevision
            if (heartbeatAt == Long.MIN_VALUE || clock() - heartbeatAt >= 15_000 || sentIntent != intent) {
                client.heartbeat(sync.deviceId, instanceId, deviceName, kind, intent)
                if (!allowed()) return
                heartbeatAt = clock(); sentIntent = intent
            }
            val now = player.now
            val owns = active?.deviceId == sync.deviceId && active.instanceId == instanceId &&
                active.song.url == now.song?.url && active.queueIndex == now.index
            val report = now.queue to player.playbackMode
            if (owns && now.song != null && (report != sentQueue || !activeBefore)) {
                val accepted = client.publishQueue(sync.deviceId, instanceId, now, player.positionMs(), player.playbackMode)
                if (!allowed()) return
                sentQueue = if (accepted) report else null
            }
            activeBefore = owns
            val command = client.claim(sync.deviceId, instanceId)
            if (!allowed()) return
            if (command != null) {
                fastPollUntil = 0L
                val localIntent = player.interactionRevision
                val current = player.now
                var result = "stale"
                try {
                    val validPayload = command.songs.size in 1..EcosystemClient.MAX_QUEUE && command.index in command.songs.indices
                    if (validPayload && command.expectedIntentRevision == localIntent) {
                        val settings = preferences()
                        if (!allowed()) return
                        val permitted = command.songs.all(settings::allows)
                        val source = connectClient.state()
                        if (!allowed()) return
                        val sourceMatches = if (command.kind == "transfer" || command.kind == "preload") source != null && source.isFresh(clock()) &&
                            (command.kind == "preload" || !source.playing) &&
                            source.deviceId == command.sourceDeviceId && source.instanceId == command.sourceInstanceId &&
                            source.revision == command.sourcePlaybackRevision && source.song.url == command.songs[command.index].url &&
                            source.queueIndex == command.index
                        else command.kind == "edit" && source?.deviceId == sync.deviceId && source.instanceId == instanceId &&
                            source.isFresh(clock()) && current.song?.url == command.expectedUrl && current.index == command.expectedIndex
                        // A queued native edit must not reuse room authorization after a stalled engine.
                        // Include validation latency: a delayed response can already hold stale authorization.
                        val partyValidationStartedAt = if (command.partyId != null) monotonicClock() else null
                        val partyAllowed = command.partyId == null || partyCommandAllowed(command.id)
                        if (permitted && sourceMatches && command.kind == "preload") {
                            val ready = withTimeoutOrNull(10_000L) { player.preloadSource(command.songs[command.index]); true } == true
                            result = if (ready && allowed() && player.interactionRevision == localIntent) "executed" else "failed"
                            if (result == "executed") fastPollUntil = clock() + 10_000L
                        } else if (permitted && sourceMatches && partyAllowed) {
                            val prepared = withTimeoutOrNull(10_000L) {
                                player.replaceQueue(if (command.kind == "edit") command.expectedQueue else null,
                                    command.songs, command.index, command.positionMs,
                                    if (command.kind == "transfer") false else command.playing, command.mode) {
                                    allowed() && player.interactionRevision == localIntent &&
                                        (command.partyId == null || (partyValidationStartedAt != null &&
                                            monotonicClock() - partyValidationStartedAt in 0L..1_000_000_000L))
                                }
                            } ?: false
                            var applied = prepared
                            if (prepared && command.kind == "transfer") {
                                val preparedIntent = player.interactionRevision
                                consumedInteractionRevision = preparedIntent
                                val ready = withTimeoutOrNull(10_000L) {
                                    while (allowed() && player.interactionRevision == preparedIntent && player.now.error == null && player.now.loading) delay(50)
                                    allowed() && player.interactionRevision == preparedIntent && player.now.error == null && !player.now.loading
                                } ?: false
                                applied = false
                                if (ready && incomingTimer(player) { allowed() && player.interactionRevision == preparedIntent }) {
                                    // Activate atomically validates the source after all target loading. It
                                    // never starts audio. A retry of a lost response keeps the same command.
                                    val activated = try { client.activate(sync.deviceId, instanceId, command) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) {
                                            if (failure is AccountApiException || !allowed() || player.interactionRevision != preparedIntent) throw failure
                                            client.activate(sync.deviceId, instanceId, command)
                                        }
                                    if (!allowed()) return
                                    if (activated != null && activated.deviceId == sync.deviceId && activated.instanceId == instanceId &&
                                        activated.song.url == command.songs[command.index].url && activated.queueIndex == command.index) {
                                        acceptedTransfer = true
                                        fun ownsPointer(pointer: ConnectSnapshot?) = pointer != null && pointer.isFresh(clock()) &&
                                            pointer.deviceId == sync.deviceId && pointer.instanceId == instanceId &&
                                            pointer.song.url == command.songs[command.index].url && pointer.queueIndex == command.index
                                        val livePointer = connectClient.state()
                                        if (!allowed()) return
                                        if (ownsPointer(livePointer)) {
                                            applied = if (command.playing) withTimeoutOrNull(10_000L) {
                                                player.control(command.songs[command.index].url, command.index, "play") {
                                                    allowed() && player.interactionRevision == preparedIntent
                                                }
                                            } ?: false else player.interactionRevision == preparedIntent
                                            if (applied) {
                                                val publication = try {
                                                    connectClient.publish(sync.deviceId, instanceId, deviceName, player.now, player.positionMs(), takeover = false)
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) {
                                                    player.control(command.songs[command.index].url, command.index, "pause") {
                                                        allowed() && player.interactionRevision == preparedIntent
                                                    }
                                                    throw failure
                                                }
                                                if (!ownsPointer(publication)) {
                                                    player.control(command.songs[command.index].url, command.index, "pause") {
                                                        allowed() && player.interactionRevision == preparedIntent
                                                    }
                                                    applied = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            result = if (applied) "executed" else "stale"
                            if (applied) {
                                sentQueue = null
                                val after = player.now
                                val accepted = client.publishQueue(sync.deviceId, instanceId, after, player.positionMs(), player.playbackMode)
                                if (!allowed()) return
                                sentQueue = if (accepted) after.queue to player.playbackMode else null
                                activeBefore = true
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { result = if (result == "executed") "executed" else "failed" }
                if (!allowed()) return
                client.ack(sync.deviceId, instanceId, command, result)
            }
            setupRequired = false; error = null; retryAt = 0
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            setupRequired = failure is AccountApiException && failure.setupRequired
            error = if (setupRequired) "Connected devices need the Troit ecosystem database update."
                else "Device connection interrupted. It will retry automatically."
            retryAt = clock() + if (setupRequired) 60_000 else 5_000
        } finally { lock.unlock() }
    }
}
