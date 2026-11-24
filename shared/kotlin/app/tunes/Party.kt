package app.tunes

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PartyMember(val id: String, val name: String, val host: Boolean)
data class PartyRoom(val id: String, val code: String, val name: String, val hostId: String, val hostName: String,
    val isHost: Boolean, val members: List<PartyMember>, val queue: EcosystemQueue?)
data class PartyInvitation(val id: String, val code: String, val name: String, val hostName: String)

class PartyClient(private val sync: Sync,
    private val request: ConnectTransport = { path, body -> sync.accountRequest(path, "POST", body) },
    private val accountId: () -> String? = { sync.userId },
) {
    private var cache: PartyRoom? = null
    private var cacheOwner: String? = null
    private var cacheGeneration = Long.MIN_VALUE
    private suspend fun rpc(name: String, body: JSONObject = JSONObject()): String {
        val owner = accountId() ?: error("Sign in to join friends.")
        val generation = sync.accountGeneration
        val reply = request("rpc/troit_party_$name", body.toString())
        check(owner == accountId() && generation == sync.accountGeneration) { "The account changed." }
        return reply
    }
    suspend fun state(): PartyRoom? {
        if (cacheOwner != accountId() || cacheGeneration != sync.accountGeneration) {
            cache = null; cacheOwner = accountId(); cacheGeneration = sync.accountGeneration
        }
        var raw = rpc("state", JSONObject().put("p_known_revision", cache?.queue?.revision ?: 0))
        if (raw.trim() == "null") { cache = null; return null }
        var json = JSONObject(raw)
        var q = json.optJSONObject("queue")
        val previous = cache
        val previousQueue = previous?.queue
        if (q?.optBoolean("unchanged") == true && (previous == null || previous.id != json.getString("id") || previousQueue == null ||
            previousQueue.deviceId != q.getString("device_id") || previousQueue.instanceId != q.getString("instance_id") ||
            previousQueue.revision != q.getLong("revision"))) {
            raw = rpc("state", JSONObject().put("p_known_revision", 0)); if (raw.trim() == "null") { cache = null; return null }
            json = JSONObject(raw); q = json.optJSONObject("queue")
        }
        val queue = q?.let {
            EcosystemQueue.decode(it, cachedSongs = if (it.optBoolean("unchanged")) previous?.queue?.songs else null)
                .copy(deviceName = it.optString("device_name", "Host player"))
        }
        val members = json.getJSONArray("members")
        require(members.length() <= 20 && (queue?.songs?.size ?: 0) <= 1000)
        return PartyRoom(json.getString("id"), json.getString("code"), json.getString("name"), json.getString("host_id"),
            json.getString("host_name"), json.getBoolean("is_host"), (0 until members.length()).map { i ->
                members.getJSONObject(i).let { PartyMember(it.getString("id"), it.getString("name"), it.getBoolean("host")) }
            }, queue).also { cache = it }
    }
    suspend fun create(name: String) { require(name.trim().length in 1..64); rpc("create", JSONObject().put("p_name", name.trim())); cache = null }
    suspend fun inspect(code: String): PartyInvitation? {
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        require(normalized.matches(Regex("[0-9A-F]{8}"))) { "Enter the eight-character room code." }
        val text = rpc("inspect", JSONObject().put("p_code", normalized))
        return if (text.trim() == "null") null else JSONObject(text).let {
            PartyInvitation(it.getString("id"), it.getString("code"), it.getString("name"), it.getString("host_name"))
        }
    }
    suspend fun join(invite: PartyInvitation) { rpc("join", JSONObject().put("p_room_id", invite.id).put("p_code", invite.code)); cache = null }
    suspend fun leave(room: PartyRoom, remove: String? = null) {
        rpc("leave", JSONObject().put("p_room_id", room.id).put("p_remove_user", remove ?: JSONObject.NULL)); cache = null
    }
    suspend fun add(id: String, room: PartyRoom, songs: List<Song>, next: Boolean): ConnectCommandResult {
        UUID.fromString(id); val queue = room.queue ?: error("The host has no shared queue yet.")
        require(songs.size in 1..20 && queue.songs.size + songs.size <= 1000)
        val json = JSONObject(rpc("add", JSONObject().put("p_command_id", id).put("p_room_id", room.id)
            .put("p_expected_revision", queue.revision).put("p_expected_playback_revision", queue.playbackRevision)
            .put("p_songs", Library.songsToJson(songs)).put("p_observed_at", queue.updatedAt).put("p_play_next", next)))
        return ConnectCommandResult(json.getString("id"), json.getString("status"))
    }
    suspend fun status(id: String): ConnectCommandResult? = rpc("status", JSONObject().put("p_command_id", id)).let {
        if (it.trim() == "null") null else JSONObject(it).let { json -> ConnectCommandResult(json.getString("id"), json.getString("status")) }
    }
    suspend fun validateCommand(id: String): Boolean = rpc("validate_command", JSONObject().put("p_command_id", id)).trim() == "true"
}

class PartyState(private val sync: Sync, private val client: PartyClient = PartyClient(sync),
    private val accountId: () -> String? = { sync.userId }, private val confirmationTimeoutMs: Long = 12_000,
) {
    val signedIn get() = accountId() != null
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var pendingId: String? = null
    private var pendingAt = 0L
    private var operation = 0L
    var room by mutableStateOf<PartyRoom?>(null); private set
    var invitation by mutableStateOf<PartyInvitation?>(null); private set
    var busy by mutableStateOf(false); private set
    var pending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var setupRequired by mutableStateOf(false); private set
    fun resetForAccount() {
        if (owner == accountId() && generation == sync.accountGeneration) return
        owner = accountId(); generation = sync.accountGeneration; room = null; invitation = null
        operation++
        pendingId = null; pending = false; busy = false; error = null; message = null; setupRequired = false
    }
    private fun current(account: String?, epoch: Long) = account != null && owner == account && accountId() == account && generation == epoch && sync.accountGeneration == epoch
    suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            resetForAccount(); val account = owner ?: return; val epoch = generation
            pendingId?.let { id ->
                client.status(id)?.let { if (!it.pending) finish(it) }
                if (pending && System.currentTimeMillis() - pendingAt >= 35_000) {
                    pending = false; pendingId = null; message = "The host did not confirm the request. Refresh before trying again."
                }
            }
            val found = client.state()
            if (current(account, epoch)) { room = found; error = null; setupRequired = false } else resetForAccount()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { fail(failure) } finally { lock.unlock() }
    }
    private suspend fun mutate(work: suspend () -> Unit): Boolean {
        resetForAccount(); if (busy || pending) return false
        if (owner == null) { error = "Sign in to host a room or join friends."; return false }
        val account = owner; val epoch = generation; val attempt = ++operation; busy = true; var locked = false
        try {
            lock.lock(); locked = true
            if (!current(account, epoch)) return false
            work()
            if (!current(account, epoch)) { resetForAccount(); return false }
            val found = client.state()
            if (!current(account, epoch)) { resetForAccount(); return false }
            room = found; setupRequired = false; error = null; return true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(account, epoch)) fail(failure) else resetForAccount(); return false
        } finally { if (operation == attempt) busy = false; if (locked) lock.unlock() }
    }
    suspend fun create(name: String) = mutate { client.create(name) }
    suspend fun inspect(code: String) = mutate {
        invitation = client.inspect(code) ?: throw IllegalArgumentException("This room is unavailable. Only accepted friends can join.")
    }
    suspend fun join() = mutate { client.join(invitation ?: error("Check a room code first.")); invitation = null }
    suspend fun leave() = mutate { room?.let { client.leave(it) }; invitation = null }
    suspend fun end() = leave()
    suspend fun removeMember(id: String) = mutate { val current = room ?: return@mutate; require(current.isHost); client.leave(current, id) }
    suspend fun addSongs(songs: List<Song>, playNext: Boolean = false): Boolean {
        val expected = room ?: return false
        val account = accountId(); val epoch = sync.accountGeneration
        var executed = false
        val accepted = mutate {
            require(!playNext || expected.isHost) { "Only the host can insert songs next." }
            val id = UUID.randomUUID().toString(); pendingId = id; pending = true; pendingAt = System.currentTimeMillis()
            var upload = true
            val receipt = withTimeoutOrNull(confirmationTimeoutMs) {
                while (current(account, epoch)) {
                    val result = try { if (upload) client.add(id, expected, songs, playNext) else client.status(id) }
                    catch (failure: Exception) {
                        if (failure is CancellationException || failure is AccountApiException) throw failure
                        upload = false; delay(750); continue
                    }
                    upload = result == null
                    if (result != null && !result.pending) return@withTimeoutOrNull result
                    delay(750)
                }
                null
            }
            if (receipt != null) { finish(receipt); executed = receipt.executed } else message = "Waiting for the host player to confirm the song."
        }
        return accepted && executed
    }
    private fun finish(receipt: ConnectCommandResult) {
        pendingId = null; pending = false
        message = when (receipt.status) {
            "executed" -> "Song added to the host queue."
            "stale" -> "The host queue or room changed. Refresh and try again."
            "expired" -> "The host player did not respond in time."
            else -> "The host could not add this song."
        }
    }
    private fun fail(failure: Exception) {
        setupRequired = failure is AccountApiException && failure.setupRequired
        error = if (setupRequired) "Party queues need the listening sessions server update."
        else if (failure is IllegalArgumentException) failure.message else "Couldn't update this listening room. Try again."
        if (failure is AccountApiException) { pendingId = null; pending = false }
    }
}
