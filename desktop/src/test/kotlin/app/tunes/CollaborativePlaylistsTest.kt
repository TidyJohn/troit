package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class CollaborativePlaylistsTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val friend = "00000000-0000-0000-0000-000000000002"
    private val id = "00000000-0000-0000-0000-000000000010"
    private val first = Song("First song", "Artist", "https://music.youtube.com/watch?v=abcdefghijk")
    private val second = Song("Second song", "Artist", "https://music.youtube.com/watch?v=bcdefghijkl")
    private fun row(playlistId: String = id, title: String = "Shared", revision: Long = 1, owner: String = me,
                    role: String = "owner", songs: List<Song> = listOf(first), sourceId: String? = null) = JSONObject()
        .put("id", playlistId).put("title", title).put("owner_id", owner).put("revision", revision)
        .put("song_count", songs.size).put("artwork", JSONObject.NULL).put("member_count", 1)
        .put("role", role).put("source_id", sourceId ?: JSONObject.NULL)
        .put("songs", Library.songsToJson(songs))
        .put("members", JSONArray().put(JSONObject().put("id", owner).put("handle", "owner_name")
            .put("display_name", "Owner").put("status", "accepted").put("role", "owner")))
    private fun snapshot(vararg playlists: JSONObject, invites: List<JSONObject> = emptyList()) =
        JSONObject().put("playlists", JSONArray(playlists.toList())).put("invites", JSONArray(invites)).toString()
    private suspend fun until(done: () -> Boolean) = withTimeout(5_000) { while (!done()) delay(5) }
    private inner class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("troit-collaboration-client").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val library = Library(File(directory, "library.json"))
        val sync = Sync(directory, "Test", library, scope)
        var owner: String? = me
        fun state(request: SocialTransport) = CollaborativePlaylists(sync, scope, library, request) { owner }
        override fun close() { scope.cancel(); directory.deleteRecursively() }
    }

    @Test
    fun promotionOnlyRemovesPrivateSourceAfterSuccessAndLostReplyRetryReusesOperationId() = runBlocking {
        Fixture().use { fixture ->
            val source = SavedPlaylist("Personal", listOf(first))
            fixture.library.add(source)
            val calls = mutableListOf<String>()
            var created: CollaborativePlaylist? = null
            val state = fixture.state { path, _, body ->
                assertEquals("rpc/troit_collab_create", path)
                val request = JSONObject(body!!)
                calls += request.getString("p_playlist_id")
                if (calls.size == 1) throw IOException("Lost response")
                // A different device may already have promoted this source under its canonical UUID.
                row(playlistId = id, title = "Together", sourceId = source.id).toString()
            }
            state.create("Together", listOf(friend), source) { created = it }
            until { !state.busy }
            assertNull(created)
            assertEquals(listOf(source), fixture.library.playlists)
            state.create("Together", listOf(friend), source) { created = it }
            until { !state.busy }
            assertEquals(calls[0], calls[1])
            assertEquals(id, created!!.id)
            assertTrue(fixture.library.playlists.isEmpty())
            assertTrue(fixture.library.all.single().deleted)
            assertEquals(id, state.selectedId)
            assertEquals(1, state.selected!!.songs.size)
            assertTrue(state.summaries.single().songs.isEmpty())
            assertTrue(state.summaries.single().members.isEmpty())
        }
    }

    @Test
    fun granularSongEditsAdoptOnlySelectedDetailAndNeverChangePersonalLibrary() = runBlocking {
        Fixture().use { fixture ->
            val personal = SavedPlaylist("Private", listOf(first))
            fixture.library.add(personal)
            val before = File(fixture.directory, "library.json").readText()
            var additions = 0
            val state = fixture.state { path, _, body -> when (path) {
                "rpc/troit_collab_list" -> snapshot(row())
                "rpc/troit_collab_get" -> row().toString()
                "rpc/troit_collab_add_songs" -> {
                    additions++
                    val songs = JSONObject(body!!).getJSONArray("p_songs")
                    assertEquals(1, songs.length())
                    assertEquals(second.url, songs.getJSONObject(0).getString("url"))
                    row(revision = 2, songs = listOf(first, second)).toString()
                }
                else -> error("Unexpected RPC $path")
            } }
            state.refresh()
            state.open(id)
            until { state.selected != null }
            var message: String? = null
            state.addSongs(id, listOf(second, second)) { message = it }
            until { !state.busy }
            assertEquals(1, additions)
            assertNotNull(message)
            assertEquals(listOf(first, second), state.selected!!.songs)
            assertEquals(2L, state.selected!!.revision)
            assertEquals(before, File(fixture.directory, "library.json").readText())
            assertEquals(listOf(personal), fixture.library.playlists)
        }
    }

    @Test
    fun accountChangeDropsOldMutationCallbacksAndCannotClearTheNewAccountsBusyState() = runBlocking {
        Fixture().use { fixture ->
            val source = SavedPlaylist("Private", listOf(first))
            fixture.library.add(source)
            val oldReply = CompletableDeferred<String>()
            val newReply = CompletableDeferred<String>()
            var oldId = ""
            var newId = ""
            val state = fixture.state { _, _, body ->
                val requested = JSONObject(body!!).getString("p_playlist_id")
                if (fixture.owner == me) { oldId = requested; oldReply.await() }
                else { newId = requested; newReply.await() }
            }
            var oldCallbacks = 0
            var newCallbacks = 0
            state.create("Old account", emptyList(), source) { oldCallbacks++ }
            until { oldId.isNotBlank() }
            fixture.owner = friend
            assertTrue(state.summaries.isEmpty())
            assertNull(state.selected)
            state.resetForAccount()
            state.create("New account", emptyList()) { newCallbacks++ }
            until { newId.isNotBlank() }
            oldReply.complete(row(playlistId = oldId, sourceId = source.id).toString())
            delay(50)
            assertTrue(state.busy)
            assertEquals(0, oldCallbacks)
            assertEquals(listOf(source), fixture.library.playlists)
            newReply.complete(row(playlistId = newId, owner = friend, title = "New account").toString())
            until { !state.busy }
            assertEquals(1, newCallbacks)
            assertEquals(friend, state.selected!!.ownerId)
            assertEquals(listOf(source), fixture.library.playlists)
        }
    }

    @Test
    fun anOlderListPollCannotOverwriteAConfirmedRename() = runBlocking {
        Fixture().use { fixture ->
            val slowList = CompletableDeferred<String>()
            var lists = 0
            val state = fixture.state { path, _, body -> when (path) {
                "rpc/troit_collab_list" -> if (++lists == 1) snapshot(row()) else slowList.await()
                "rpc/troit_collab_rename" -> {
                    assertEquals(1L, JSONObject(body!!).getLong("p_expected_revision"))
                    row(title = "New title", revision = 2).toString()
                }
                else -> error(path)
            } }
            state.refresh()
            val poll = async { state.refresh(forceList = true) }
            until { lists == 2 }
            state.rename(id, "New title")
            until { !state.busy }
            slowList.complete(snapshot(row(title = "Old title", revision = 1)))
            poll.await()
            assertEquals("New title", state.summaries.single().title)
            assertEquals(2L, state.summaries.single().revision)
        }
    }

    @Test
    fun invitationAcceptanceAndDeclineStaySeparateFromPersonalPlaylistsAndRevokedDetailsDisappear() = runBlocking {
        Fixture().use { fixture ->
            var accepted = false
            var revoked = false
            val invitation = row(owner = friend, role = "invited")
            val state = fixture.state { path, _, body -> when (path) {
                "rpc/troit_collab_list" -> if (accepted) snapshot(row(owner = friend, role = "editor")) else snapshot(invites = listOf(invitation))
                "rpc/troit_collab_respond" -> {
                    if (JSONObject(body!!).getBoolean("p_accept")) { accepted = true; row(owner = friend, role = "editor").toString() }
                    else "null"
                }
                "rpc/troit_collab_get" -> if (revoked) throw AccountApiException(403, "42501", "Access revoked") else row(owner = friend, role = "editor").toString()
                else -> error(path)
            } }
            state.refresh()
            assertEquals(1, state.invites.size)
            state.respond(id, false)
            until { !state.busy }
            assertTrue(state.invites.isEmpty())
            state.respond(id, true)
            until { !state.busy }
            assertEquals("editor", state.summaries.single().role)
            state.open(id)
            until { state.selected != null }
            revoked = true
            state.refresh()
            assertNull(state.selected)
            assertNull(state.selectedId)
            assertTrue(fixture.library.all.isEmpty())
        }
    }

    @Test
    fun newPrivateEditsDuringPromotionAreKeptAsAnIndependentPersonalCopy() = runBlocking {
        Fixture().use { fixture ->
            val source = SavedPlaylist("Private", listOf(first))
            fixture.library.add(source)
            val reply = CompletableDeferred<String>()
            var requested = ""
            val state = fixture.state { _, _, body -> requested = JSONObject(body!!).getString("p_playlist_id"); reply.await() }
            state.create("Shared", emptyList(), source)
            until { requested.isNotBlank() }
            fixture.library.addSongs(source.id, listOf(second))
            reply.complete(row(playlistId = requested, sourceId = source.id).toString())
            until { !state.busy }
            val preserved = fixture.library.playlists.single()
            assertNotEquals(source.id, preserved.id)
            assertEquals(listOf(first, second), preserved.songs)
            assertTrue(fixture.library.all.first { it.id == source.id }.deleted)
            assertEquals(listOf(first), state.selected!!.songs)
        }
    }

    @Test
    fun accessFailureForAnOldSelectionCannotCloseANewerPlaylist() = runBlocking {
        Fixture().use { fixture ->
            val otherId = "00000000-0000-0000-0000-000000000011"
            val oldReply = CompletableDeferred<String>()
            var requested = false
            var finished = false
            val state = fixture.state { path, _, body -> when (path) {
                "rpc/troit_collab_list" -> snapshot(row(), row(playlistId = otherId, title = "Other"))
                "rpc/troit_collab_get" -> if (JSONObject(body!!).getString("p_playlist_id") == id) {
                    requested = true
                    try { oldReply.await() } finally { finished = true }
                } else row(playlistId = otherId, title = "Other").toString()
                else -> error(path)
            } }
            state.refresh()
            state.open(id)
            until { requested }
            state.open(otherId)
            oldReply.completeExceptionally(AccountApiException(403, "42501", "Old access revoked"))
            until { finished }
            delay(30)
            assertEquals(otherId, state.selectedId)
            assertNull(state.error)
            state.refresh()
            assertEquals(otherId, state.selected!!.id)
            assertFalse(state.detailLoading)
        }
    }

    @Test
    fun missingServerAndOversizedResponsesFailWithoutPretendingToCreateOrLeakingCachedData() = runBlocking {
        Fixture().use { fixture ->
            var tooMany = false
            val state = fixture.state { _, _, _ ->
                if (!tooMany) throw AccountApiException(404, "PGRST202", "Missing RPC")
                JSONObject().put("playlists", JSONArray(List(51) { row(playlistId = "playlist-$it") })).put("invites", JSONArray()).toString()
            }
            state.refresh()
            assertTrue(state.setupRequired)
            assertTrue(state.summaries.isEmpty())
            tooMany = true
            state.refresh(forceList = true)
            assertTrue(state.summaries.isEmpty())
            assertTrue(state.error!!.contains("count"))
            assertTrue(fixture.library.all.isEmpty())
        }
    }
}
