package app.tunes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** A small current-track snapshot, shared with the Android widget; never contains a library or queue. */
@Immutable
data class ConnectSnapshot(
    val deviceId: String,
    val instanceId: String,
    val deviceName: String,
    val song: Song,
    val playing: Boolean,
    val positionMs: Long,
    val revision: Long,
    val updatedAt: String,
    val expiresAt: String,
    val fresh: Boolean,
    val queueIndex: Int = 0,
) {
    fun toJson(): String = json().toString()

    private fun json() = JSONObject()
        .put("device_id", deviceId).put("instance_id", instanceId).put("device_name", deviceName)
        .put("track_url", song.url).put("title", song.title).put("artist", song.artist)
        .put("artwork", song.artwork ?: JSONObject.NULL).put("duration_seconds", song.durationSeconds)
        .put("playing", playing).put("position_ms", positionMs).put("revision", revision)
        .put("updated_at", updatedAt).put("expires_at", expiresAt).put("fresh", fresh)
        .put("queue_index", queueIndex)

    /** The server freshness flag is authoritative when reading; expiry also protects persisted work. */
    fun isFresh(atMillis: Long = System.currentTimeMillis()): Boolean = fresh && runCatching {
        Instant.parse(expiresAt).toEpochMilli() > atMillis
    }.getOrDefault(false)

    /** Advance only a live playing snapshot, never beyond its duration or freshness window. */
    fun estimatedPositionMs(atMillis: Long = System.currentTimeMillis()): Long {
        val elapsed = if (playing && fresh) runCatching {
            val updated = Instant.parse(updatedAt).toEpochMilli()
            val validFor = (Instant.parse(expiresAt).toEpochMilli() - updated).coerceIn(0L, 45_000L)
            (atMillis - updated).coerceIn(0L, validFor)
        }.getOrDefault(0L) else 0L
        val limit = song.durationSeconds.takeIf { it > 0 }?.coerceAtMost(604800)?.times(1000) ?: 604800000L
        return (positionMs.coerceAtLeast(0) + elapsed).coerceAtMost(limit)
    }

    companion object {
        fun fromJson(text: String): ConnectSnapshot = fromJson(JSONObject(text))

        internal fun fromJson(json: JSONObject) = ConnectSnapshot(
            deviceId = json.getString("device_id"), instanceId = json.getString("instance_id"),
            deviceName = json.getString("device_name"),
            song = Song(json.getString("title"), json.getString("artist"), json.getString("track_url"),
                if (json.isNull("artwork")) null else json.getString("artwork"), json.optLong("duration_seconds")),
            playing = json.getBoolean("playing"), positionMs = json.getLong("position_ms"),
            revision = json.getLong("revision"), updatedAt = json.getString("updated_at"),
            expiresAt = json.getString("expires_at"), fresh = json.getBoolean("fresh"),
            queueIndex = json.optInt("queue_index"),
        )
    }
}

@Immutable
data class ConnectCommandResult(val id: String, val status: String) {
    val pending get() = status == "pending" || status == "claimed"
    val executed get() = status == "executed"
}

internal data class ConnectCommand(val id: String, val action: String, val trackUrl: String, val queueIndex: Int)

typealias ConnectTransport = suspend (path: String, body: String) -> String

/** Authenticated RPCs only. Workers keep the original snapshot and UUID when retrying a control. */
class ConnectClient(
    private val sync: Sync,
    private val request: ConnectTransport = { path, body -> sync.accountRequest(path, "POST", body) },
    private val accountId: () -> String? = { sync.userId },
) {
    private suspend fun rpc(name: String, body: JSONObject = JSONObject()): String {
        val owner = accountId() ?: throw IllegalStateException("Sign in to use playback controls.")
        val generation = sync.accountGeneration
        val result = request("rpc/troit_connect_$name", body.toString())
        check(owner == accountId() && generation == sync.accountGeneration) { "Account changed. Refresh the widget and try again." }
        return result
    }

    suspend fun state(): ConnectSnapshot? = decodeSnapshot(rpc("state"))

    suspend fun command(snapshot: ConnectSnapshot, action: String, commandId: String = UUID.randomUUID().toString()): ConnectCommandResult {
        UUID.fromString(commandId)
        // Toggle is converted once, before upload, so network retries cannot toggle a second time.
        val explicit = if (action == "toggle") if (snapshot.playing) "pause" else "play" else action
        require(explicit in setOf("previous", "next", "play", "pause")) { "Unsupported playback control." }
        val body = JSONObject().put("p_command_id", commandId).put("p_device_id", snapshot.deviceId)
            .put("p_instance_id", snapshot.instanceId).put("p_expected_revision", snapshot.revision)
            .put("p_expected_url", snapshot.song.url).put("p_observed_at", snapshot.updatedAt)
            .put("p_action", explicit)
        // Server checks freshness after checking the UUID: an already accepted retry still returns its receipt.
        return decodeResult(JSONObject(rpc("command", body)))
    }

    suspend fun commandStatus(commandId: String): ConnectCommandResult? {
        UUID.fromString(commandId)
        val response = rpc("command_status", JSONObject().put("p_command_id", commandId))
        return if (response.trim() == "null") null else decodeResult(JSONObject(response))
    }

    internal suspend fun publish(deviceId: String, instanceId: String, deviceName: String, now: NowPlaying, positionMs: Long, takeover: Boolean): ConnectSnapshot? {
        val song = now.song ?: return null
        return decodeSnapshot(rpc("publish", JSONObject()
            .put("p_device_id", deviceId).put("p_instance_id", instanceId)
            .put("p_device_name", deviceName.take(64).ifBlank { "Troit" })
            .put("p_track_url", song.url).put("p_title", song.title.take(500).ifBlank { "Unknown track" })
            .put("p_artist", song.artist.take(500))
            .put("p_artwork", song.artwork?.takeIf { it.startsWith("https://") && it.length <= 4096 } ?: JSONObject.NULL)
            .put("p_duration_seconds", song.durationSeconds.coerceIn(0, 604800))
            .put("p_queue_index", now.index.coerceAtLeast(0)).put("p_playing", now.playing)
            .put("p_position_ms", positionMs.coerceIn(0, 604800000)).put("p_takeover", takeover)))
    }

    internal suspend fun claim(deviceId: String, instanceId: String): ConnectCommand? {
        val response = rpc("claim", JSONObject().put("p_device_id", deviceId).put("p_instance_id", instanceId))
        if (response.trim() == "null") return null
        val json = JSONObject(response)
        return ConnectCommand(json.getString("id"), json.getString("action"), json.getString("track_url"), json.getInt("queue_index"))
    }

    internal suspend fun ack(deviceId: String, instanceId: String, command: ConnectCommand, result: String) {
        rpc("ack", JSONObject().put("p_command_id", command.id).put("p_device_id", deviceId)
            .put("p_instance_id", instanceId).put("p_result", result))
    }

    private fun decodeSnapshot(text: String) = if (text.trim() == "null") null else ConnectSnapshot.fromJson(text)
    private fun decodeResult(json: JSONObject) = ConnectCommandResult(json.getString("id"), json.getString("status"))
}

/**
 * A single lightweight app-owned poller. Call tick on the player's owning thread (Main on Android).
 * Desktop continues polling while minimized/paused so a phone can resume it. This class owns no loop.
 */
class PlaybackConnect(
    private val sync: Sync,
    private val player: MusicPlayer,
    private val deviceName: String,
    private val client: ConnectClient = ConnectClient(sync),
    private val accountId: () -> String? = { sync.userId },
    private val clock: () -> Long = System::currentTimeMillis,
    private val controlTimeoutMs: Long = 10_000L,
) {
    // The extended receiver is independent of v1 Connect availability and also registers idle devices.
    private val ecosystem = EcosystemReceiver(sync, player, deviceName)
    private val sleepTimer = SleepTimerReceiver(sync, player)
    private data class Report(val url: String, val index: Int, val playing: Boolean)
    private val lock = Mutex()
    private var owner: String? = null
    private var ownerGeneration = Long.MIN_VALUE
    private var instanceId = UUID.randomUUID().toString()
    private var sent: Report? = null
    private var sentAt = 0L
    private var localSession = false
    private var observedInteraction = player.interactionRevision
    private var pendingTakeover = false
    private var failures = 0
    var snapshot by mutableStateOf<ConnectSnapshot?>(null)
        private set
    var setupRequired by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val pollDelayMs: Long get() = when {
        setupRequired -> 60_000L
        failures > 0 -> (2_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L)
        ecosystem.awaitingTransfer -> 350L
        accountId() == null -> if (player.now.song != null) 2_000L else 15_000L
        else -> 2_000L
    }

    suspend fun tick() {
        if (!lock.tryLock()) return
        try {
            val account = accountId()
            val generation = sync.accountGeneration
            if (owner != account || ownerGeneration != generation) {
                owner = account
                ownerGeneration = generation
                instanceId = UUID.randomUUID().toString()
                sent = null; sentAt = 0L; localSession = false
                observedInteraction = player.interactionRevision; pendingTakeover = false
                failures = 0; setupRequired = false; error = null; snapshot = null
            }
            if (account == null) return
            val now = player.now
            val report = now.song?.let { Report(it.url, now.index, now.playing) }
            // A restored paused queue is not a playback session and must not replace another device.
            if (now.playing && report != null && !localSession) {
                localSession = true
                pendingTakeover = true
            }
            if (observedInteraction != player.interactionRevision) {
                observedInteraction = player.interactionRevision
                pendingTakeover = true
            }
            val takeover = pendingTakeover && now.playing && !now.loading
            val intentBeforeRequest = player.interactionRevision
            if (localSession && report != null && report.url.startsWith("https://") && report.url.length <= 2048 &&
                (report != sent || takeover || clock() - sentAt >= 15_000L)) {
                val published = client.publish(sync.deviceId, instanceId, deviceName, now, player.positionMs(), takeover)
                if (owner != accountId() || generation != sync.accountGeneration) return
                snapshot = published
                sent = report; sentAt = clock()
                if (takeover) pendingTakeover = false
            } else {
                val current = client.state()
                if (owner != accountId() || generation != sync.accountGeneration) return
                snapshot = current
            }
            val active = snapshot
            if (!localSession && active?.deviceId == sync.deviceId && active.instanceId == instanceId &&
                active.song.url == player.now.song?.url && active.queueIndex == player.now.index) {
                // A transfer may atomically activate this runtime while still paused. Its lost network
                // response must not leave that real prepared session unable to accept Play later.
                localSession = true
            }
            if (active != null && active.isFresh(clock()) &&
                (active.deviceId != sync.deviceId || active.instanceId != instanceId) &&
                !pendingTakeover && player.interactionRevision == intentBeforeRequest && player.now.playing) {
                // A fresh takeover moves audio to one device. An explicit local action made during
                // the request wins on the next tick; a stale/failed read never pauses local music.
                val beforePause = player.now
                val url = beforePause.song?.url
                if (url != null) withTimeoutOrNull(controlTimeoutMs) {
                    player.control(url, beforePause.index, "pause") {
                        owner == account && accountId() == account && generation == sync.accountGeneration &&
                            player.interactionRevision == intentBeforeRequest
                    }
                }
                sentAt = 0L
            }
            if (!localSession) {
                failures = 0; setupRequired = false; error = null
                return
            }
            val command = client.claim(sync.deviceId, instanceId)
            if (owner != accountId() || generation != sync.accountGeneration) return
            if (command != null) {
                // Claim is consumed. The player validates on its owning queue just before applying it.
                val result = try {
                    val applied = withTimeoutOrNull(controlTimeoutMs) {
                        player.control(command.trackUrl, command.queueIndex, command.action) {
                            owner == account && accountId() == account && generation == sync.accountGeneration
                        }
                    } ?: false
                    if (applied) "executed" else "stale"
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    "failed"
                }
                sentAt = 0L
                if (owner != accountId() || generation != sync.accountGeneration) return
                if (result == "executed") {
                    // Publish the player's actual result before acknowledging. A widget must not see an
                    // executed receipt followed by the previous song while waiting for our next tick.
                    val after = player.now
                    val afterSong = after.song
                    if (afterSong != null) try {
                        val published = client.publish(sync.deviceId, instanceId, deviceName, after, player.positionMs(), takeover = false)
                        if (owner != accountId() || generation != sync.accountGeneration) return
                        snapshot = published
                        sent = Report(afterSong.url, after.index, after.playing)
                        sentAt = clock()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        // The control has already happened. Keep its truthful receipt and retry metadata
                        // on the next tick; never repeat the audio command to repair a failed publication.
                        sentAt = 0L
                    }
                }
                if (owner != accountId() || generation != sync.accountGeneration) return
                client.ack(sync.deviceId, instanceId, command, result)
            }
            failures = 0; setupRequired = false; error = null
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            failures++
            setupRequired = failure is AccountApiException && failure.setupRequired
            error = if (setupRequired) "Connected playback needs the Troit Connect database update."
                else "Playback controls couldn't connect. They will retry automatically."
        } finally {
            try {
                // Production transports read the actual cached account. Fixture account overrides used by
                // legacy v1 tests must not accidentally start an unrelated real network connection.
                if (sync.userId != null && sync.isCurrentAccount) {
                    ecosystem.tick(instanceId, snapshot)
                    val consumed = ecosystem.consumedInteractionRevision
                    if (consumed != null && consumed == player.interactionRevision) {
                        observedInteraction = consumed
                        pendingTakeover = false
                        if (ecosystem.acceptedTransfer) localSession = true
                    }
                }
                if (sync.isCurrentAccount) sleepTimer.tick(snapshot)
                else { player.setStopAfterCurrent(false); player.setSleepDeadline(null) }
            } finally { lock.unlock() }
        }
    }
}
