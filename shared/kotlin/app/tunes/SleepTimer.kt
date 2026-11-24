package app.tunes

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class SleepTimer(val id: String, val kind: String, val deadlineMs: Long?, val trackUrl: String,
    val queueIndex: Int, val deviceName: String, val local: Boolean = false, val deviceId: String? = null) {
    internal fun json() = JSONObject().put("id", id).put("kind", kind).put("deadline", deadlineMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("track_url", trackUrl).put("queue_index", queueIndex).put("device_name", deviceName).put("local", local).put("device_id", deviceId ?: JSONObject.NULL)
    internal companion object {
        fun decode(json: JSONObject, local: Boolean = false) = SleepTimer(json.getString("id"), json.getString("kind"),
            json.optString("deadline").takeIf { it.isNotBlank() && it != "null" }?.let { Instant.parse(it).toEpochMilli() },
            json.getString("track_url"), json.getInt("queue_index"), json.optString("device_name", "Current player"), local,
            json.optString("device_id").takeIf { it.isNotBlank() && it != "null" })
    }
}
internal data class SleepRead(val timer: SleepTimer?, val revision: Long, val serverTime: Long)
class SleepTimerClient(private val sync: Sync,
    private val request: ConnectTransport = { path, body -> sync.accountRequest(path, "POST", body) },
    private val accountId: () -> String? = { sync.userId },
) {
    private suspend fun rpc(name: String, body: JSONObject = JSONObject()): String {
        val owner = accountId() ?: error("Sign in to share a sleep timer."); val generation = sync.accountGeneration
        val text = request("rpc/troit_sleep_$name", body.toString())
        check(owner == accountId() && generation == sync.accountGeneration) { "The account changed." }
        return text
    }
    private fun decode(text: String): SleepRead = JSONObject(text).let {
        SleepRead(it.optJSONObject("timer")?.let { value -> SleepTimer.decode(value) }, it.getLong("revision"), Instant.parse(it.getString("server_time")).toEpochMilli())
    }
    internal suspend fun read() = decode(rpc("state"))
    internal suspend fun set(id: String, revision: Long, kind: String, minutes: Int) = decode(rpc("set", JSONObject()
        .put("p_request_id", id).put("p_expected_revision", revision).put("p_kind", kind).put("p_minutes", minutes)))
    internal suspend fun cancel(id: String) = decode(rpc("cancel", JSONObject().put("p_timer_id", id)))
    internal suspend fun claim(id: String, active: ConnectSnapshot, completed: Boolean): Pair<String, Int>? = rpc("claim", JSONObject()
        .put("p_timer_id", id).put("p_device_id", active.deviceId).put("p_instance_id", active.instanceId).put("p_completed", completed)).let {
        if (it.trim() == "null") null else JSONObject(it).let { reply -> reply.getString("track_url") to reply.getInt("queue_index") }
    }
    /** Carry the shared timer into a silently prepared receiver before it is allowed to start. */
    suspend fun armIncoming(player: MusicPlayer, allowed: () -> Boolean = { true }): Boolean {
        if (!allowed()) return false
        val read = try { read() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is AccountApiException && failure.setupRequired) return true
            return false
        }
        if (!allowed()) return false
        val target = read.timer ?: return true
        if (target.kind == "minutes") {
            val deadline = target.deadlineMs ?: return false
            if (deadline <= read.serverTime) return false
            player.setSleepDeadline(System.currentTimeMillis() + deadline - read.serverTime)
        } else if (target.trackUrl == player.now.song?.url && target.queueIndex == player.now.index) player.setStopAfterCurrent(true)
        return allowed()
    }
}

private object LocalSleepTimers {
    private val monitor = Any()
    private fun file(sync: Sync, owner: String?) = File(sync.dataDirectory, "sleep-timer-${owner?.let {
        java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    } ?: "guest"}.json")
    fun read(sync: Sync, owner: String?): SleepTimer? = synchronized(monitor) {
        runCatching { SleepTimer.decode(JSONObject(file(sync, owner).readText()), true) }.getOrNull()
    }
    fun write(sync: Sync, owner: String?, timer: SleepTimer?) = synchronized(monitor) {
        if (timer == null) file(sync, owner).delete() else file(sync, owner).writeAtomically(timer.json().toString())
        Unit
    }
    fun consume(sync: Sync, owner: String?, id: String): Boolean = synchronized(monitor) {
        val current = read(sync, owner)
        if (current?.id != id) false else { file(sync, owner).delete(); true }
    }
    fun whileCurrent(sync: Sync, owner: String?, id: String?, action: () -> Unit): Boolean = synchronized(monitor) {
        if (read(sync, owner)?.id != id) false else { action(); true }
    }
}

class SleepTimerState(private val sync: Sync, private val localPlayer: MusicPlayer? = null,
    private val client: SleepTimerClient = SleepTimerClient(sync), private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var initialized = false
    private var revision = 0L
    private var serverOffset = 0L
    private var operation = 0L
    var timer by mutableStateOf<SleepTimer?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var setupRequired by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    fun resetForAccount() {
        if (initialized && owner == sync.userId && generation == sync.accountGeneration) return
        if (initialized && owner != sync.userId) LocalSleepTimers.write(sync, owner, null)
        owner = sync.userId; generation = sync.accountGeneration; initialized = true
        operation++
        timer = LocalSleepTimers.read(sync, owner); revision = 0; serverOffset = 0; busy = false; error = null; message = null; setupRequired = false
    }
    fun remainingMs(): Long? = timer?.deadlineMs?.let { (it - clock() - if (timer?.local == true) 0 else serverOffset).coerceAtLeast(0) }
    private fun current(account: String?, epoch: Long) = owner == account && sync.userId == account && generation == epoch && sync.accountGeneration == epoch
    private fun apply(read: SleepRead) {
        revision = read.revision; serverOffset = read.serverTime - clock(); timer = read.timer
    }
    private fun armLocal(read: SleepRead) {
        if (localPlayer != null && read.timer?.deviceId == sync.deviceId && read.timer.kind == "end_of_track" &&
            localPlayer.now.song?.url == read.timer.trackUrl && localPlayer.now.index == read.timer.queueIndex) localPlayer.setStopAfterCurrent(true)
        if (localPlayer != null && read.timer?.deviceId == sync.deviceId && read.timer.kind == "minutes") {
            localPlayer.setSleepDeadline(read.timer.deadlineMs?.minus(serverOffset))
        }
    }
    suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            resetForAccount(); val account = owner; val epoch = generation
            val local = withContext(Dispatchers.IO) { LocalSleepTimers.read(sync, account) }
            if (account == null) { timer = local; return }
            try {
                val read = client.read()
                if (current(account, epoch)) {
                    if (local != null && read.timer == null) timer = local else {
                        if (read.timer != null) LocalSleepTimers.write(sync, account, null)
                        apply(read)
                    }
                    error = null; setupRequired = false
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current(account, epoch)) { timer = local; fail(failure) }
            }
        } finally { lock.unlock() }
    }
    suspend fun setMinutes(minutes: Int): Boolean { require(minutes in setOf(15, 30, 60)); return set("minutes", minutes) }
    suspend fun setEndOfTrack(): Boolean = set("end_of_track", 0)
    private suspend fun set(kind: String, minutes: Int): Boolean {
        resetForAccount(); if (busy) return false
        val account = owner; val epoch = generation; val attempt = ++operation; busy = true; var locked = false
        try {
            lock.lock(); locked = true
            if (!current(account, epoch)) return false
            val id = UUID.randomUUID().toString()
            if (account != null && !setupRequired) try {
                val read = client.set(id, revision, kind, minutes)
                if (!current(account, epoch)) return false
                LocalSleepTimers.write(sync, account, null); apply(read); armLocal(read); error = null
                message = "Sleep timer shared with your connected devices."
                return true
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure !is AccountApiException || !failure.setupRequired) { fail(failure); return false }
                fail(failure)
            }
            val now = localPlayer?.now ?: error("Start playback on this device first.")
            val song = now.song ?: error("Start playback on this device first.")
            if (!current(account, epoch)) return false
            val local = SleepTimer(id, kind, if (kind == "minutes") clock() + minutes * 60_000L else null,
                song.url, now.index, "This device", true, sync.deviceId)
            LocalSleepTimers.write(sync, account, local); timer = local
            localPlayer.setStopAfterCurrent(kind == "end_of_track")
            localPlayer.setSleepDeadline(local.deadlineMs)
            message = "Timer set on this device. It stays active while Troit is running."
            return true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(account, epoch)) fail(failure); return false
        } finally { if (operation == attempt) busy = false; if (locked) lock.unlock() }
    }
    suspend fun cancel(): Boolean {
        resetForAccount(); if (busy) return false
        val target = timer ?: return true; val account = owner; val epoch = generation
        val attempt = ++operation; busy = true; var locked = false
        try {
            lock.lock(); locked = true
            if (!current(account, epoch)) return false
            var latest: SleepRead? = null
            if (target.local) {
                LocalSleepTimers.consume(sync, account, target.id)
                timer = LocalSleepTimers.read(sync, account)
            } else latest = client.cancel(target.id)
            if (!current(account, epoch)) return false
            latest?.let(::apply)
            localPlayer?.setStopAfterCurrent(false); localPlayer?.setSleepDeadline(null)
            if (timer != null) {
                if (timer?.local == true) {
                    localPlayer?.setSleepDeadline(timer?.deadlineMs)
                    localPlayer?.setStopAfterCurrent(timer?.kind == "end_of_track")
                } else latest?.let(::armLocal)
            }
            error = null; message = if (timer == null) "Sleep timer cancelled." else "A different timer was set. The current timer is still active."
            return true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { if (current(account, epoch)) fail(failure); return false
        } finally { if (operation == attempt) busy = false; if (locked) lock.unlock() }
    }
    private fun fail(failure: Exception) {
        setupRequired = failure is AccountApiException && failure.setupRequired
        error = if (setupRequired) "Shared sleep timers need the listening sessions server update. Local timers still work."
            else failure.message?.take(160) ?: "Couldn't update this sleep timer. Try again."
    }
}

/** Owned by the audio service / desktop engine, so closing a dialog never disables the timer. */
class SleepTimerReceiver(private val sync: Sync, private val player: MusicPlayer,
    private val client: SleepTimerClient = SleepTimerClient(sync), private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private var owner: String? = null
    private var generation = Long.MIN_VALUE
    private var initialized = false
    private var lastCompleted = player.completedTrackRevision
    private var retryAt = 0L
    private var cached: SleepTimer? = null
    private var offset = 0L
    private var wasOwner = false
    private var armedId: String? = null
    private var armedDeadline: Long? = null
    private var armedIntent = player.interactionRevision
    private var firedId: String? = null
    suspend fun tick(active: ConnectSnapshot?) {
        if (!lock.tryLock()) return
        val completed = player.completedTrackRevision
        try {
            val account = sync.userId; val epoch = sync.accountGeneration
            if (!initialized || owner != account || generation != epoch) {
                if (initialized && owner != account) LocalSleepTimers.write(sync, owner, null)
                owner = account; generation = epoch; initialized = true; player.setStopAfterCurrent(false); player.setSleepDeadline(null); retryAt = 0
                cached = null; wasOwner = false; armedId = null; armedDeadline = null; firedId = null
            }
            fun allowed() = sync.userId == account && sync.accountGeneration == epoch
            val localBeforeRead = withContext(Dispatchers.IO) { LocalSleepTimers.read(sync, account) }
            var serverNow = clock() + offset
            var online = false
            if (account != null && clock() >= retryAt) {
                try {
                    val read = client.read(); if (!allowed()) return
                    cached = read.timer
                    offset = read.serverTime - clock(); serverNow = read.serverTime; online = true
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { retryAt = clock() + 10_000 }
            }
            // Local UI can cancel or replace a timer while the account request is suspended.
            // Never restore the pre-request file or discard a replacement using that old read.
            var local = withContext(Dispatchers.IO) { LocalSleepTimers.read(sync, account) }
            if (!allowed()) return
            if (online && cached != null && local != null && local.id == localBeforeRead?.id) {
                LocalSleepTimers.consume(sync, account, local.id)
                local = LocalSleepTimers.read(sync, account)
            }
            val currentTimer = local ?: cached
            if (currentTimer == null) {
                LocalSleepTimers.whileCurrent(sync, account, null) { player.setStopAfterCurrent(false); player.setSleepDeadline(null) }
                wasOwner = false; armedId = null; return
            }
            if (firedId == currentTimer.id) {
                player.setStopAfterCurrent(false); player.setSleepDeadline(null)
                if (online && account != null) { client.cancel(currentTimer.id); cached = null }
                return
            }
            if (currentTimer.local) serverNow = clock()
            val freshOwner = active?.takeIf { it.isFresh(if (currentTimer.local) clock() else serverNow) }
            val owns = currentTimer.local || freshOwner?.deviceId == sync.deviceId ||
                (!online && freshOwner == null && wasOwner && armedId == currentTimer.id)
            if (freshOwner != null) wasOwner = freshOwner.deviceId == sync.deviceId
            val now = player.now
            val anchor = now.song?.url == currentTimer.trackUrl && now.index == currentTimer.queueIndex
            fun updatePlayer(action: () -> Unit): Boolean = if (currentTimer.local)
                LocalSleepTimers.whileCurrent(sync, account, currentTimer.id, action) else { action(); true }
            fun clearConsumedPlayer() {
                val clear = {
                    player.setStopAfterCurrent(false); player.setSleepDeadline(null)
                }
                if (currentTimer.local) LocalSleepTimers.whileCurrent(sync, account, null, clear) else clear()
            }
            if (!updatePlayer { player.setStopAfterCurrent(owns && currentTimer.kind == "end_of_track" && anchor) }) return
            if (!owns) { player.setSleepDeadline(null); return }
            val isNew = armedId != currentTimer.id
            if (isNew) {
                armedId = currentTimer.id; armedIntent = player.interactionRevision
                armedDeadline = currentTimer.deadlineMs?.minus(if (currentTimer.local) 0 else offset)
            } else if (online && !currentTimer.local && currentTimer.deadlineMs != null) {
                // Remove initial request latency from the alarm estimate; never push a timer later.
                val estimate = currentTimer.deadlineMs - offset
                if (armedDeadline == null || estimate < armedDeadline!!) armedDeadline = estimate
            }
            if (currentTimer.kind == "minutes" && serverNow < (currentTimer.deadlineMs ?: Long.MAX_VALUE)) {
                armedIntent = player.interactionRevision
                if (!updatePlayer { player.setSleepDeadline(armedDeadline) }) return
            } else if (currentTimer.kind != "minutes" && !updatePlayer { player.setSleepDeadline(null) }) return
            if (currentTimer.kind == "end_of_track" && !anchor) {
                if (currentTimer.local) LocalSleepTimers.consume(sync, account, currentTimer.id) else client.cancel(currentTimer.id)
                clearConsumedPlayer(); return
            }
            val due = if (currentTimer.kind == "minutes") serverNow >= (currentTimer.deadlineMs ?: Long.MAX_VALUE)
                else completed != lastCompleted && anchor
            if (!due) return
            val intent = player.interactionRevision
            firedId = currentTimer.id
            if (currentTimer.kind == "minutes" && (isNew || intent != armedIntent)) {
                // A past timer discovered after restart, or a newer user action after the native alarm,
                // must not pause a newly resumed song again.
                if (currentTimer.local) LocalSleepTimers.consume(sync, account, currentTimer.id) else if (online) client.cancel(currentTimer.id)
                clearConsumedPlayer(); return
            }
            val target = if (currentTimer.local) {
                if (LocalSleepTimers.consume(sync, account, currentTimer.id)) now.song?.url?.let { it to now.index } else null
            } else if (online) active?.let { client.claim(currentTimer.id, it, completed != lastCompleted && anchor) }
            else now.song?.url?.let { it to now.index }
            if (!allowed()) return
            if (target != null) {
                // Countdown timers stop music, including a natural queue advance during the RPC.
                // Explicit new user intent still wins through the revision guard.
                val live = player.now
                val actual = if (currentTimer.kind == "minutes") live.song?.url?.let { it to live.index } else target
                if (actual != null) player.control(actual.first, actual.second, "pause") {
                    allowed() && player.interactionRevision == intent && (!currentTimer.local || LocalSleepTimers.read(sync, account) == null)
                }
            }
            clearConsumedPlayer()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { retryAt = clock() + 5_000 }
        finally { lastCompleted = completed; lock.unlock() }
    }
}
