package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ListeningHistoryEntry(val id: String, val song: Song, val playedAt: Long,
    val positionMs: Long, val deviceName: String, val sequence: Long = 0)

internal data class ListeningStats(val plays: Int, val songs: List<Pair<Song, Int>>, val artists: List<Pair<String, Int>>)

/**
 * Counted from the play history the device already keeps, so it works for guests and needs no server.
 * ponytail: limited to the newest [ListeningLibraryState.MAX_HISTORY] plays, and a shared credit such as
 * "A & B" counts as its own artist. Count on the server if all-time numbers matter.
 */
internal fun listeningStats(history: List<ListeningHistoryEntry>, since: Long = 0, top: Int = 10): ListeningStats {
    val plays = history.filter { it.playedAt >= since }
    return ListeningStats(plays.size,
        plays.groupBy { it.song.url }.values.map { it.first().song to it.size }.sortedByDescending { it.second }.take(top),
        plays.filter { it.song.artist.isNotBlank() }.groupingBy { it.song.artist }.eachCount().toList()
            .sortedByDescending { it.second }.take(top))
}

private data class ListeningLike(val song: Song, val liked: Boolean, val revision: Long, val updatedAt: Long)
private data class ListeningLikeOperation(val id: String, val song: Song, val liked: Boolean,
    var expectedRevision: Long, var predecessor: String? = null, var attempted: Boolean = false)
private class ListeningData {
    val likes = LinkedHashMap<String, ListeningLike>()
    val operations = mutableListOf<ListeningLikeOperation>()
    val history = LinkedHashMap<String, ListeningHistoryEntry>()
    val pendingHistory = LinkedHashSet<String>()
    var likesCursor = 0L
    var historyCursor = 0L
}
private data class ListeningMarker(var id: String, var url: String, var index: Int, var position: Long,
    var lastTick: Long, var listened: Long, var playing: Boolean, var qualified: Boolean, var savedAt: Long)

/** Local journals and remote cursors are isolated by account; no guest data silently joins a new account. */
class ListeningLibraryState(
    private val sync: Sync,
    private val dataDir: File,
    private val scope: CoroutineScope,
    private val deviceName: String = "This device",
    private val request: ConnectTransport = { path, body -> sync.accountRequest(path, "POST", body) },
    private val accountId: () -> String? = { sync.userId },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val network = Mutex()
    @Volatile private var owner: String? = null
    @Volatile private var generation = Long.MIN_VALUE
    private var initialized = false
    private var failures = 0
    private var nextBackgroundSyncAt = 0L
    private var likedUrls by mutableStateOf<Set<String>>(emptySet())
    var likedSongs by mutableStateOf<List<Song>>(emptyList()); private set
    var history by mutableStateOf<List<ListeningHistoryEntry>>(emptyList()); private set
    var pending by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var setupRequired by mutableStateOf(false); private set
    val signedIn get() = accountId() != null
    val pollDelayMs: Long get() = when {
        accountId() == null -> 15_000L
        setupRequired -> 60_000L
        failures > 0 -> (5_000L shl failures.coerceAtMost(4)).coerceAtMost(60_000L)
        else -> 15_000L
    }

    init { resetForAccount() }

    fun isLiked(song: Song): Boolean = song.url in likedUrls

    fun resetForAccount() {
        synchronized(storageMonitor) {
        val account = accountId(); val epoch = sync.accountGeneration
        if (initialized && account == owner && epoch == generation) return
        owner = account; generation = epoch; initialized = true
        busy = false; error = null; message = null; setupRequired = false; failures = 0; nextBackgroundSyncAt = 0
        publish(load())
        }
    }

    fun toggleLike(song: Song) {
        try {
        resetForAccount()
        val account = owner; val epoch = generation
        val normalized = runCatching { normalizeListeningSong(song) }.getOrElse { error = it.message; return }
        synchronized(storageMonitor) {
            if (!current(account, epoch)) { resetForAccount(); return }
            val data = load()
            val desired = !renderLikes(data).any { it.url == normalized.url }
            if (desired && renderLikes(data).size >= MAX_LIKED) { error = "Liked songs supports up to $MAX_LIKED tracks."; return }
            if (owner == null) {
                if (desired) data.likes[normalized.url] = ListeningLike(normalized, true, 0, clock())
                else data.likes.remove(normalized.url)
            }
            else {
                if (data.operations.size >= MAX_PENDING_LIKES) { error = "Connect to sync your pending likes before adding more."; return }
                val predecessor = data.operations.lastOrNull { it.song.url == normalized.url }?.id
                data.operations += ListeningLikeOperation(UUID.randomUUID().toString(), normalized, desired,
                    data.likes[normalized.url]?.revision ?: 0, predecessor)
            }
            save(data); publish(data)
        }
        error = null; message = null
        if (owner != null) scope.launch { refresh() }
        } catch (_: Exception) { error = "Couldn't save this like on the device. Please try again." }
    }

    /** Call from the actual player owner, not from a remote observer or a restored paused screen. */
    suspend fun recordPlayback(now: NowPlaying, positionMs: Long) {
        try {
        resetForAccount()
        val account = owner; val epoch = generation
        val song = now.song
        synchronized(storageMonitor) {
            if (!current(account, epoch)) return
            val file = markerFile()
            val time = clock()
            val marker = markers[file.absolutePath] ?: loadMarker(file)?.also { markers[file.absolutePath] = it }
            if (song == null || !now.playing || now.loading) {
                if (marker != null && marker.playing) {
                    marker.playing = false; marker.lastTick = time; marker.position = positionMs.coerceAtLeast(0)
                    saveMarker(file, marker, time)
                }
                return
            }
            val clean = runCatching { normalizeListeningSong(song) }.getOrNull() ?: return
            val position = positionMs.coerceIn(0, 604800000)
            val restarted = marker != null && marker.qualified && position <= 2_000 && marker.position > position + 3_000
            val changed = marker == null || marker.url != clean.url || marker.index != now.index || restarted || time - marker.lastTick > 21_600_000
            val live = if (changed) ListeningMarker(UUID.randomUUID().toString(), clean.url, now.index,
                position, time, 0, true, false, 0).also { markers[file.absolutePath] = it; saveMarker(file, it, time) }
            else marker!!
            if (!changed && live.playing) live.listened += (time - live.lastTick).coerceIn(0, 5_000)
            live.position = position; live.lastTick = time; live.playing = true
            val duration = clean.durationSeconds.takeIf { it > 0 }?.times(1000) ?: 10_000
            val threshold = minOf(5_000L, (duration / 2).coerceAtLeast(500))
            if (!live.qualified && live.listened >= threshold) {
                val data = load()
                val entry = ListeningHistoryEntry(live.id, clean, time, position, deviceName.take(64).ifBlank { "Troit" })
                if (entry.id !in data.history) {
                    data.history[entry.id] = entry
                    if (account != null) { data.pendingHistory += entry.id; nextBackgroundSyncAt = 0 }
                    trimHistory(data)
                    save(data); publish(data)
                }
                live.qualified = true
                saveMarker(file, live, time)
            } else if (time - live.savedAt >= 30_000) saveMarker(file, live, time)
        }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "Couldn't save listening history on this device. Playback will continue."
        } finally {
            if (owner != null && clock() >= nextBackgroundSyncAt) {
                nextBackgroundSyncAt = clock() + pollDelayMs
                synchronized(storageMonitor) { publish(load()) }
                if (pending) scope.launch { refresh() }
            }
        }
    }

    suspend fun refresh() {
        if (!network.tryLock()) return
        try {
            resetForAccount()
            val account = owner; val epoch = generation
            synchronized(storageMonitor) {
                val data = load()
                val expired = data.pendingHistory.filter { id -> data.history[id]?.playedAt?.let { it < clock() - 2_592_000_000L } != false }
                if (expired.isNotEmpty()) {
                    data.pendingHistory.removeAll(expired.toSet()); save(data)
                    message = "Older offline listening history stays on this device."
                }
                publish(data)
            }
            if (account == null) return
            busy = true
            for (ignored in 0 until 20) {
                val operation = synchronized(storageMonitor) {
                    val data = load()
                    val first = data.operations.firstOrNull()
                    if (first != null && !first.attempted) { first.attempted = true; save(data) }
                    first?.copy()
                } ?: break
                val reply = rpc("set_like", JSONObject().put("p_operation_id", operation.id)
                    .put("p_song", songJson(operation.song)).put("p_liked", operation.liked)
                    .put("p_expected_revision", operation.expectedRevision), account, epoch)
                if (!current(account, epoch)) return
                synchronized(storageMonitor) {
                    if (!current(account, epoch)) { resetForAccount(); return }
                    val data = load()
                    val row = reply.optJSONObject("row")?.let(::decodeLike)
                    if (row != null) mergeLike(data, row)
                    if (reply.optBoolean("conflict")) {
                        data.operations.removeAll { it.song.url == operation.song.url }
                        message = "A liked song changed on another device. Its newer choice was kept; tap the heart again to change it."
                    } else {
                        data.operations.removeAll { it.id == operation.id }
                        data.operations.filter { it.predecessor == operation.id && !it.attempted }.forEach {
                            it.predecessor = null; it.expectedRevision = row?.revision ?: operation.expectedRevision
                        }
                    }
                    save(data); publish(data)
                }
            }
            for (ignored in 0 until 20) {
                val event = synchronized(storageMonitor) { val data = load(); data.pendingHistory.firstOrNull()?.let(data.history::get) }
                    ?: break
                rpc("record", JSONObject().put("p_event_id", event.id).put("p_song", songJson(event.song))
                    .put("p_played_at", event.playedAt).put("p_position_ms", event.positionMs).put("p_device_name", event.deviceName), account, epoch)
                if (!current(account, epoch)) return
                synchronized(storageMonitor) {
                    if (!current(account, epoch)) { resetForAccount(); return }
                    val data = load(); data.pendingHistory.remove(event.id); save(data); publish(data)
                }
            }
            for (page in 0 until 50) {
                val cursor = synchronized(storageMonitor) { load().likesCursor }
                val reply = rpc("likes", JSONObject().put("p_after_seq", cursor).put("p_limit", 100), account, epoch)
                if (!current(account, epoch)) return
                synchronized(storageMonitor) {
                    if (!current(account, epoch)) { resetForAccount(); return }
                    val data = load(); val rows = reply.getJSONArray("items")
                    require(rows.length() <= 100)
                    repeat(rows.length()) { mergeLike(data, decodeLike(rows.getJSONObject(it))) }
                    data.likesCursor = maxOf(data.likesCursor, reply.getLong("cursor"))
                    if (rows.length() > 0 || reply.getLong("cursor") != cursor) save(data)
                    publish(data)
                }
                if (!reply.optBoolean("has_more")) break
            }
            for (page in 0 until 5) {
                val cursor = synchronized(storageMonitor) { load().historyCursor }
                val reply = rpc("history", JSONObject().put("p_after_seq", cursor).put("p_limit", 100), account, epoch)
                if (!current(account, epoch)) return
                synchronized(storageMonitor) {
                    if (!current(account, epoch)) { resetForAccount(); return }
                    val data = load(); val rows = reply.getJSONArray("items")
                    require(rows.length() <= 100)
                    repeat(rows.length()) { val row = decodeHistory(rows.getJSONObject(it)); data.history[row.id] = row }
                    data.historyCursor = maxOf(data.historyCursor, reply.getLong("cursor"))
                    trimHistory(data)
                    if (rows.length() > 0 || reply.getLong("cursor") != cursor) save(data)
                    publish(data)
                }
                if (!reply.optBoolean("has_more")) break
            }
            failures = 0; setupRequired = false; error = null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (owner != accountId() || generation != sync.accountGeneration) resetForAccount()
            else {
                failures++
                setupRequired = failure is AccountApiException && failure.setupRequired
                error = if (setupRequired) "Likes and history need the listening database update. Your changes remain on this device."
                    else "Couldn't sync likes and history. Your changes are saved and will retry."
            }
        } finally { busy = false; network.unlock() }
    }

    private suspend fun rpc(name: String, body: JSONObject, account: String, epoch: Long): JSONObject {
        check(current(account, epoch)) { "Account changed." }
        val reply = request("rpc/troit_listening_$name", body.toString())
        check(current(account, epoch)) { "Account changed." }
        return JSONObject(reply)
    }
    private fun current(account: String?, epoch: Long) = accountId() == account && owner == account && generation == epoch && sync.accountGeneration == epoch
    private fun scopeName() = owner?.let { hash(it) } ?: "guest"
    private fun dataFile() = File(dataDir, "listening-${scopeName()}.json")
    private fun markerFile() = File(dataDir, "listening-marker-${scopeName()}.json")

    private fun load(): ListeningData = ListeningData().also { data ->
        runCatching {
            val file = dataFile(); if (!file.isFile || file.length() > 64_000_000) return@runCatching
            val json = JSONObject(file.readText())
            json.optJSONArray("likes")?.let { rows -> repeat(minOf(rows.length(), 5000)) { mergeLike(data, decodeLike(rows.getJSONObject(it))) } }
            json.optJSONArray("operations")?.let { rows -> repeat(minOf(rows.length(), MAX_PENDING_LIKES)) {
                val row = rows.getJSONObject(it)
                data.operations += ListeningLikeOperation(row.getString("id"), decodeSong(row.getJSONObject("song")), row.getBoolean("liked"),
                    row.getLong("expected"), row.optString("predecessor").takeIf { id -> id.isNotBlank() && id != "null" }, row.optBoolean("attempted"))
            } }
            json.optJSONArray("history")?.let { rows -> repeat(minOf(rows.length(), MAX_HISTORY)) { val row = decodeHistory(rows.getJSONObject(it)); data.history[row.id] = row } }
            json.optJSONArray("pending_history")?.let { rows -> repeat(minOf(rows.length(), MAX_HISTORY)) { val id = rows.getString(it); if (id in data.history) data.pendingHistory += id } }
            data.likesCursor = json.optLong("likes_cursor"); data.historyCursor = json.optLong("history_cursor")
        }
    }

    private fun save(data: ListeningData) {
        dataDir.mkdirs()
        val json = JSONObject().put("likes_cursor", data.likesCursor).put("history_cursor", data.historyCursor)
            .put("likes", JSONArray(data.likes.values.map { row -> JSONObject().put("song", songJson(row.song)).put("liked", row.liked)
                .put("revision", row.revision).put("local_updated", row.updatedAt) }))
            .put("operations", JSONArray(data.operations.map { row -> JSONObject().put("id", row.id).put("song", songJson(row.song))
                .put("liked", row.liked).put("expected", row.expectedRevision).put("predecessor", row.predecessor ?: JSONObject.NULL).put("attempted", row.attempted) }))
            .put("history", JSONArray(data.history.values.map(::historyJson))).put("pending_history", JSONArray(data.pendingHistory.toList()))
        dataFile().writeAtomically(json.toString())
    }
    private fun publish(data: ListeningData) {
        likedSongs = renderLikes(data)
        likedUrls = likedSongs.mapTo(HashSet()) { it.url }
        history = data.history.values.sortedWith(compareByDescending<ListeningHistoryEntry> { it.playedAt }.thenByDescending { it.sequence }).take(MAX_HISTORY)
        pending = data.operations.isNotEmpty() || data.pendingHistory.isNotEmpty()
    }
    private fun renderLikes(data: ListeningData): List<Song> {
        val visible = data.likes.values.filter { it.liked }.sortedBy { it.updatedAt }.associateTo(LinkedHashMap()) { it.song.url to it.song }
        data.operations.forEach { if (it.liked) visible[it.song.url] = it.song else visible.remove(it.song.url) }
        return visible.values.toList().asReversed().take(MAX_LIKED)
    }
    private fun mergeLike(data: ListeningData, row: ListeningLike) {
        val old = data.likes[row.song.url]
        if (old == null || row.revision >= old.revision) data.likes[row.song.url] = row
    }
    private fun decodeLike(row: JSONObject) = ListeningLike(decodeSong(row.getJSONObject("song")), row.getBoolean("liked"), row.getLong("revision"),
        row.optLong("local_updated").takeIf { it > 0 } ?: row.getLong("revision"))
    private fun decodeHistory(row: JSONObject) = ListeningHistoryEntry(row.getString("id"), decodeSong(row.getJSONObject("song")),
        row.getLong("played_at"), row.getLong("position_ms"), row.getString("device_name"), row.optLong("seq"))
    private fun historyJson(row: ListeningHistoryEntry) = JSONObject().put("id", row.id).put("song", songJson(row.song))
        .put("played_at", row.playedAt).put("position_ms", row.positionMs).put("device_name", row.deviceName).put("seq", row.sequence)
    private fun trimHistory(data: ListeningData) {
        val keep = data.history.values.sortedWith(compareByDescending<ListeningHistoryEntry> { it.playedAt }.thenByDescending { it.sequence }).take(MAX_HISTORY).mapTo(HashSet()) { it.id }
        data.history.keys.retainAll(keep); data.pendingHistory.retainAll(keep)
    }
    private fun loadMarker(file: File): ListeningMarker? = runCatching {
        val row = JSONObject(file.readText())
        ListeningMarker(row.getString("id"), row.getString("url"), row.getInt("index"), row.getLong("position"), row.getLong("last_tick"),
            row.getLong("listened"), row.getBoolean("playing"), row.getBoolean("qualified"), row.optLong("saved_at"))
    }.getOrNull()
    private fun saveMarker(file: File, marker: ListeningMarker, time: Long) {
        dataDir.mkdirs(); marker.savedAt = time
        file.writeAtomically(JSONObject().put("id", marker.id).put("url", marker.url).put("index", marker.index).put("position", marker.position)
            .put("last_tick", marker.lastTick).put("listened", marker.listened).put("playing", marker.playing).put("qualified", marker.qualified).put("saved_at", time).toString())
    }

    companion object {
        const val MAX_LIKED = 1000
        const val MAX_HISTORY = 500
        private const val MAX_PENDING_LIKES = 2000
        private val storageMonitor = Any()
        private val markers = object : LinkedHashMap<String, ListeningMarker>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ListeningMarker>?) = size > 32
        }
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private fun normalizeListeningSong(song: Song): Song {
    require(song.url.startsWith("https://") && song.url.length <= 2048) { "This song cannot be saved to listening history." }
    val clean = song.copy(title = song.title.take(500).ifBlank { "Unknown song" }, artist = song.artist.take(500),
        artwork = song.artwork?.takeIf { it.startsWith("https://") && it.length <= 4096 }, durationSeconds = song.durationSeconds.coerceIn(0, 604800))
    require(songJson(clean).toString().toByteArray(Charsets.UTF_8).size <= 8192) { "This song's metadata is too large to sync." }
    return clean
}
private fun songJson(song: Song): JSONObject = Library.songsToJson(listOf(song)).getJSONObject(0)
private fun decodeSong(json: JSONObject): Song = Library.songsFromJson(JSONArray().put(json)).single()
