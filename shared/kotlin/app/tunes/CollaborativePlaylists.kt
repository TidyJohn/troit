package app.tunes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val LocalCollaborativePlaylists = staticCompositionLocalOf<CollaborativePlaylists?> { null }

@Immutable
data class CollaborativeMember(
    val id: String,
    val handle: String,
    val displayName: String,
    val status: String,
    val role: String,
) {
    val profile get() = SocialProfile(id, handle, displayName)
}

/** Summary rows have empty songs/members; only the selected server-authorized detail is retained. */
@Immutable
data class CollaborativePlaylist(
    val id: String,
    override val title: String,
    val ownerId: String,
    val revision: Long,
    val songCount: Int,
    override val artwork: String? = null,
    val memberCount: Int = 1,
    val role: String = "editor",
    val sourceId: String? = null,
    val songs: List<Song> = emptyList(),
    val members: List<CollaborativeMember> = emptyList(),
) : Item {
    override val subtitle get() = "$songCount songs · Collaborative"
    val isOwner get() = role == "owner"
}

/** Server-owned shared playlists never enter the personal Library/last-write-wins sync path. */
class CollaborativePlaylists(
    private val sync: Sync,
    private val scope: CoroutineScope,
    private val library: Library,
    private val request: SocialTransport = { path, method, body ->
        sync.accountRequest(path, method, body, maximumRequestBytes = 2_000_000)
    },
    private val accountId: () -> String? = { sync.userId },
) {
    val userId get() = accountId()
    private var owner: String? = userId
    private var ownerGeneration = sync.accountGeneration
    private var _summaries by mutableStateOf<List<CollaborativePlaylist>>(emptyList())
    private var _invites by mutableStateOf<List<CollaborativePlaylist>>(emptyList())
    private var _selectedId by mutableStateOf<String?>(null)
    private var _selected by mutableStateOf<CollaborativePlaylist?>(null)
    val summaries get() = if (currentOwner()) _summaries else emptyList()
    val playlists get() = summaries
    val invites get() = if (currentOwner()) _invites else emptyList()
    val selectedId get() = _selectedId.takeIf { currentOwner() }
    val selected get() = _selected.takeIf { currentOwner() }
    val detail get() = selected
    var loading by mutableStateOf(false)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var setupRequired by mutableStateOf(false)
        private set
    private var loaded = false
    private var lastListAt = 0L
    private var changes = 0L
    private var selectionVersion = 0L
    private var failures = 0
    private var pollingError = false
    private val reads = Mutex()
    private data class Ticket(val account: String, val generation: Long)
    private data class PendingCreate(val id: String, val title: String, val peers: List<String>, val source: SavedPlaylist?)
    private var pendingCreate: PendingCreate? = null
    val pollDelayMs: Long get() = when {
        setupRequired -> 60_000
        failures > 0 -> (5_000L shl failures.coerceAtMost(4)).coerceAtMost(60_000)
        selectedId != null -> 5_000
        else -> 15_000
    }

    private fun currentOwner() = owner == userId && ownerGeneration == sync.accountGeneration
    private fun current(ticket: Ticket) = currentOwner() && ticket.account == userId && ticket.generation == sync.accountGeneration
    private fun ticket(): Ticket? = userId?.let { Ticket(it, sync.accountGeneration) }

    /** Also clears an old account's pending create UUID, callbacks, and visible details. */
    fun resetForAccount() {
        if (currentOwner()) return
        owner = userId
        ownerGeneration = sync.accountGeneration
        _summaries = emptyList(); _invites = emptyList(); _selectedId = null; _selected = null
        loading = false; detailLoading = false; busy = false; error = null; setupRequired = false
        loaded = false; lastListAt = 0; changes++; selectionVersion++; failures = 0; pollingError = false
        pendingCreate = null
    }

    private suspend fun rpc(name: String, body: JSONObject = JSONObject()): String = request("rpc/troit_collab_$name", "POST", body.toString())

    /** A bounded list refresh plus one selected detail, with no parallel request backlog. */
    suspend fun refresh(forceList: Boolean = false) {
        resetForAccount()
        val account = ticket() ?: return
        if (!reads.tryLock()) return
        val beforeChanges = changes
        val initialSelection = selectionVersion
        var detailRequest: Pair<String, Long>? = null
        if (!loaded) loading = true
        try {
            if (forceList || !loaded || System.currentTimeMillis() - lastListAt >= 15_000) {
                val response = rpc("list")
                val snapshot = withContext(Dispatchers.Default) { decodeList(response) }
                if (!current(account) || beforeChanges != changes) return
                // A stale refresh cannot replace a newer mutation's summary revision.
                val previous = _summaries.associateBy { it.id }
                _summaries = snapshot.first.map { incoming -> previous[incoming.id]?.takeIf { it.revision > incoming.revision } ?: incoming }
                _invites = snapshot.second
                loaded = true; lastListAt = System.currentTimeMillis()
                val chosen = _selectedId
                if (chosen != null && _summaries.none { it.id == chosen }) {
                    _selectedId = null; _selected = null; selectionVersion++
                }
            }
            val chosen = _selectedId
            val version = selectionVersion
            if (chosen != null) {
                if (_selected == null) detailLoading = true
                detailRequest = chosen to version
                val response = rpc("get", JSONObject().put("p_playlist_id", chosen))
                val detail = withContext(Dispatchers.Default) { decodeDetail(response) }
                if (!current(account) || beforeChanges != changes || chosen != _selectedId || version != selectionVersion) return
                require(detail.id == chosen) { "Unexpected collaborative playlist." }
                adopt(detail)
            }
            if (current(account)) {
                failures = 0; setupRequired = false
                if (pollingError) { error = null; pollingError = false }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (current(account) && beforeChanges == changes) {
                val requested = detailRequest
                if (requested != null && (_selectedId != requested.first || selectionVersion != requested.second)) return
                // Access revoked/deleted: do not keep displaying a previously authorized song list.
                if (requested != null && failure is AccountApiException && failure.status in setOf(403, 404) && !failure.setupRequired) {
                    forget(requested.first); lastListAt = 0
                }
                fail(failure, poll = true)
            }
        } finally {
            if (current(account)) { loading = false; if (initialSelection == selectionVersion) detailLoading = false }
            reads.unlock()
        }
    }

    fun open(id: String) {
        resetForAccount()
        if (id == _selectedId && _selected != null) return
        _selectedId = id; _selected = null; detailLoading = true; selectionVersion++; error = null
        scope.launch { refresh() }
    }

    fun close() { _selectedId = null; _selected = null; detailLoading = false; selectionVersion++ }
    fun retry() { lastListAt = 0; scope.launch { refresh(forceList = true) } }

    /** Retrying the same form after a lost response reuses the original operation UUID. */
    fun create(title: String, peerIds: List<String>, source: SavedPlaylist? = null, onCreated: (CollaborativePlaylist) -> Unit = {}) {
        resetForAccount()
        val normalized = runCatching { normalizeTitle(title) to normalizePeers(peerIds) }
            .getOrElse { error = it.message; return }
        val songs = runCatching { normalizeSongs(source?.songs.orEmpty(), allowEmpty = true) }
            .getOrElse { error = it.message; return }
        val pending = pendingCreate?.takeIf { it.title == normalized.first && it.peers == normalized.second && it.source == source }
            ?: PendingCreate(UUID.randomUUID().toString(), normalized.first, normalized.second, source).also { pendingCreate = it }
        mutate({
            rpc("create", JSONObject().put("p_playlist_id", pending.id).put("p_title", pending.title)
                .put("p_songs", Library.songsToJson(songs)).put("p_peer_ids", JSONArray(pending.peers))
                .put("p_source_id", source?.id ?: JSONObject.NULL))
        }) { decoded ->
            val playlist = requireNotNull(decoded) { "The server did not return the new collaborative playlist." }
            require((playlist.id == pending.id || (source != null && playlist.sourceId == source.id)) && playlist.ownerId == userId) { "Unexpected created playlist." }
            adopt(playlist)
            _selectedId = playlist.id; _selected = playlist; detailLoading = false; selectionVersion++
            source?.let { promoted ->
                library.playlists.firstOrNull { it.id == promoted.id }?.let { local ->
                    // Edits made while conversion was in flight remain as a separate personal copy.
                    if (local.title != promoted.title || local.songs != promoted.songs) {
                        library.add(local.copy(id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis()))
                    }
                    library.remove(local)
                }
            }
            pendingCreate = null
            onCreated(playlist)
        }
    }

    /** onComplete receives a success message, or null on failure so a save dialog stays open. */
    fun addSongs(id: String, songs: List<Song>, onComplete: (String?) -> Unit = {}) {
        val normalized = runCatching { normalizeSongs(songs, allowEmpty = false) }.getOrElse {
            error = it.message; onComplete(null); return
        }
        mutate({ rpc("add_songs", playlistBody(id).put("p_songs", Library.songsToJson(normalized))) }, onFailure = { onComplete(null) }) { decoded ->
            adopt(requireNotNull(decoded))
            onComplete("Saved to collaborative playlist.")
        }
    }

    fun removeSong(id: String, song: Song) = mutate({ rpc("remove_song", playlistBody(id).put("p_song_url", song.url)) }) { adopt(requireNotNull(it)) }

    fun rename(id: String, title: String, expectedRevision: Long? = null) {
        val name = runCatching { normalizeTitle(title) }.getOrElse { error = it.message; return }
        val revision = expectedRevision ?: selected?.takeIf { it.id == id }?.revision ?: summaries.firstOrNull { it.id == id }?.revision
        if (revision == null) { error = "Refresh this playlist before renaming it."; return }
        mutate({ rpc("rename", playlistBody(id).put("p_title", name).put("p_expected_revision", revision)) }) { adopt(requireNotNull(it)) }
    }

    fun invite(id: String, peerIds: List<String>) {
        val peers = runCatching { normalizePeers(peerIds) }.getOrElse { error = it.message; return }
        if (peers.isEmpty()) { error = "Choose at least one accepted friend."; return }
        mutate({ rpc("invite", playlistBody(id).put("p_peer_ids", JSONArray(peers))) }) { adopt(requireNotNull(it)) }
    }

    fun respond(id: String, accept: Boolean, onAccepted: (CollaborativePlaylist) -> Unit = {}) = mutate({
        rpc("respond", playlistBody(id).put("p_accept", accept))
    }) { decoded ->
        _invites = _invites.filterNot { it.id == id }
        if (accept) { val playlist = requireNotNull(decoded); adopt(playlist); onAccepted(playlist) }
    }

    fun removeMember(id: String, peerId: String) = mutate({
        rpc("member_action", playlistBody(id).put("p_peer_id", peerId).put("p_action", "remove"))
    }) { adopt(requireNotNull(it)) }

    fun leave(id: String, onLeft: () -> Unit = {}) = mutate({
        rpc("member_action", playlistBody(id).put("p_peer_id", userId).put("p_action", "leave"))
    }) { forget(id); onLeft() }

    fun delete(id: String, onDeleted: () -> Unit = {}) = mutate({ rpc("delete", playlistBody(id)) }) { forget(id); onDeleted() }

    private fun mutate(action: suspend () -> String, onFailure: () -> Unit = {}, apply: (CollaborativePlaylist?) -> Unit) {
        resetForAccount()
        val account = ticket() ?: run { error = "Sign in to collaborate on playlists."; onFailure(); return }
        if (busy) { error = "Another playlist change is still saving. Try again."; onFailure(); return }
        busy = true; error = null; pollingError = false; changes++
        scope.launch {
            try {
                val response = action()
                val decoded = withContext(Dispatchers.Default) { if (response.trim() == "null") null else decodeDetail(response) }
                if (!current(account)) return@launch
                apply(decoded)
                setupRequired = false; failures = 0; lastListAt = 0
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (current(account)) { fail(failure); onFailure() }
            } finally {
                if (current(account)) busy = false
            }
        }
    }

    private fun adopt(playlist: CollaborativePlaylist) {
        val old = _summaries.firstOrNull { it.id == playlist.id }
        if (old != null && old.revision > playlist.revision) return
        val summary = playlist.copy(songs = emptyList(), members = emptyList())
        _summaries = (_summaries.filterNot { it.id == playlist.id } + summary).sortedBy { it.title.lowercase() }.take(50)
        if (_selectedId == playlist.id && (_selected?.revision ?: 0) <= playlist.revision) { _selected = playlist; detailLoading = false }
    }

    private fun forget(id: String) {
        _summaries = _summaries.filterNot { it.id == id }; _invites = _invites.filterNot { it.id == id }
        if (_selectedId == id) close()
    }

    private fun fail(failure: Exception, poll: Boolean = false) {
        failures = (failures + 1).coerceAtMost(4)
        setupRequired = failure is AccountApiException && failure.setupRequired
        pollingError = poll
        error = if (setupRequired) "Collaborative playlists need the latest server update. Your personal playlists still work."
            else failure.message?.take(300) ?: "Couldn't update the collaborative playlist. Please try again."
    }

    private fun playlistBody(id: String) = JSONObject().put("p_playlist_id", id)

    companion object {
        private fun normalizeTitle(title: String) = title.trim().also { require(it.length in 1..100) { "Use a playlist name between 1 and 100 characters." } }
        private fun normalizePeers(peers: List<String>) = peers.distinct().sorted().also {
            require(it.size <= 19) { "Invite at most 19 friends to a collaborative playlist." }
            it.forEach(UUID::fromString)
        }
        private fun normalizeSongs(songs: List<Song>, allowEmpty: Boolean): List<Song> {
            require(songs.size <= 1_000) { "A collaborative playlist can contain up to 1,000 songs." }
            require(allowEmpty || songs.isNotEmpty()) { "Choose a song to add." }
            songs.forEach { song ->
                require(song.title.isNotBlank() && song.title.length <= 500 && song.artist.length <= 500 &&
                    song.url.startsWith("https://") && song.url.length <= 2048 && (song.artwork?.length ?: 0) <= 4096) { "This song cannot be saved to a collaborative playlist." }
            }
            return songs.distinctBy { it.url }
        }

        private fun decodeSummary(row: JSONObject): CollaborativePlaylist = CollaborativePlaylist(
            id = row.getString("id"), title = row.getString("title"), ownerId = row.getString("owner_id"),
            revision = row.getLong("revision").also { require(it > 0) },
            songCount = row.getInt("song_count").also { require(it in 0..1000) },
            artwork = row.optString("artwork").takeUnless { row.isNull("artwork") || it.isBlank() },
            memberCount = row.getInt("member_count").also { require(it in 1..20) },
            role = row.getString("role").also { require(it in setOf("owner", "editor", "invited")) },
            sourceId = row.optString("source_id").takeUnless { row.isNull("source_id") || it.isBlank() },
        )

        private fun decodeList(response: String): Pair<List<CollaborativePlaylist>, List<CollaborativePlaylist>> {
            val row = JSONObject(response)
            fun list(name: String): List<CollaborativePlaylist> {
                val rows = row.getJSONArray(name)
                require(rows.length() <= 50) { "Unexpected collaborative playlist count." }
                return (0 until rows.length()).map { decodeSummary(rows.getJSONObject(it)) }.distinctBy { it.id }
            }
            return list("playlists") to list("invites")
        }

        private fun decodeDetail(response: String): CollaborativePlaylist {
            val row = JSONObject(response)
            val summary = decodeSummary(row)
            val songs = row.getJSONArray("songs")
            val members = row.getJSONArray("members")
            require(songs.length() <= 1000 && members.length() <= 20) { "Unexpected collaborative playlist size." }
            return summary.copy(songs = Library.songsFromJson(songs), members = (0 until members.length()).map {
                val member = members.getJSONObject(it)
                CollaborativeMember(member.getString("id"), member.getString("handle"), member.getString("display_name"),
                    member.getString("status"), member.getString("role"))
            })
        }
    }
}
