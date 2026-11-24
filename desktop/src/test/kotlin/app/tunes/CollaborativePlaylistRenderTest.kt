package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Offline account/RPC fixtures render the actual collaboration actions without inviting real users. */
@OptIn(ExperimentalComposeUiApi::class)
class CollaborativePlaylistRenderTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val friend = "00000000-0000-0000-0000-000000000002"
    private val pending = "00000000-0000-0000-0000-000000000003"
    private val playlistId = "00000000-0000-0000-0000-000000000011"
    private val invitationId = "00000000-0000-0000-0000-000000000012"
    private val songs = listOf(Song("First song", "Evening Artist", "https://music.youtube.com/watch?v=first000001"),
        Song("Second song", "Evening Artist", "https://music.youtube.com/watch?v=second00001"))
    private fun profile(id: String, handle: String, name: String) = JSONObject().put("id", id).put("handle", handle).put("display_name", name)
    private fun member(id: String, handle: String, name: String, role: String) = profile(id, handle, name).put("status", "accepted").put("role", role)
    private fun detail(role: String = "owner", id: String = playlistId, title: String = "Late night together") = JSONObject()
        .put("id", id).put("title", title).put("owner_id", me).put("revision", 1).put("song_count", songs.size)
        .put("artwork", JSONObject.NULL).put("member_count", 2).put("role", role).put("source_id", JSONObject.NULL)
        .put("songs", Library.songsToJson(songs)).put("members", JSONArray().put(member(me, "nightowl", "Night Owl", "owner"))
            .put(member(friend, "sunday", "Sunday Listener", "editor")))
    private fun snapshot() = JSONObject().put("profile", profile(me, "nightowl", "Night Owl"))
        .put("connections", JSONArray().put(JSONObject().put("id", "friendship-1").put("peer", profile(friend, "sunday", "Sunday Listener"))
            .put("status", "accepted").put("requested_by", me))
            .put(JSONObject().put("id", "friendship-2").put("peer", profile(pending, "notyet", "Pending Request"))
                .put("status", "pending").put("requested_by", me)))
        .put("blocked", JSONArray()).toString()
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.hasText(value: String) = any { node -> SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value } }
    private fun List<SemanticsNode>.text(value: String) = first { node -> SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value } }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private suspend fun until(done: () -> Boolean) = withTimeout(3_000) { while (!done()) delay(10) }

    @Test
    fun ownerAndEditorActionsSearchAndInvitationsRenderOnPhoneAndDesktop() = runBlocking(Dispatchers.Main) {
        val directory = Files.createTempDirectory("troit-collab-preview").toFile()
        val output = File("build/ui-previews").apply { mkdirs() }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val library = Library(File(directory, "library.json"))
        val sync = Sync(directory, "Preview", library, scope)
        library.onChange = null
        var frame = 0L
        suspend fun ImageComposeScene.settle() { repeat(8) { render(frame).close(); frame += 80_000_000L; delay(15) } }
        fun ImageComposeScene.save(name: String) { render(frame).use { image -> File(output, "$name.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes) } }
        try {
            for (role in listOf("owner", "editor")) for (width in listOf(320, 1100)) {
                val state = CollaborativePlaylists(sync, scope, library, request = { path, _, _ ->
                    when (path) {
                        "rpc/troit_collab_list" -> JSONObject().put("playlists", JSONArray().put(detail(role))).put("invites", JSONArray().put(detail("invited", invitationId, "After-hours invitation")
                            .put("owner_id", if (role == "owner") friend else me))).toString()
                        "rpc/troit_collab_get" -> detail(role).toString()
                        else -> error("Read-only render fixture must not mutate a live service: $path")
                    }
                }, accountId = { if (role == "owner") me else friend })
                state.refresh(forceList = true)
                state.open(playlistId)
                until { state.selected != null }
                var played: Pair<List<Song>, Int>? = null
                val scene = ImageComposeScene(width, 980, density = Density(1f, if (width == 320) 1.2f else 1f), coroutineContext = coroutineContext) {
                    TroitTheme { Surface(Modifier.fillMaxSize()) { CollaborativePlaylistScreen(state, playlistId, { tracks, index -> played = tracks to index }, {}) } }
                }
                try {
                    scene.settle()
                    assertEquals(role == "owner", scene.nodes().hasText("Rename"))
                    assertEquals(role == "owner", scene.nodes().hasText("Delete playlist"))
                    assertEquals(role == "editor", scene.nodes().hasText("Leave collaboration"))
                    scene.nodes().first { SemanticsActions.SetText in it.config }.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Second"))
                    scene.settle()
                    scene.save("collaboration-$role-$width")
                    scene.nodes().text("1 of 2 songs")
                    scene.nodes().text("Second song").button().config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(songs, played!!.first)
                    assertEquals(1, played!!.second)
                } finally { scene.close() }
                val section = ImageComposeScene(width, 760, density = Density(1f), coroutineContext = coroutineContext) {
                    TroitTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(18.dp)) { CollaborationLibrarySection(state, "", {}) } } }
                }
                try {
                    section.settle()
                    section.nodes().text("Accept invitation")
                    section.nodes().text("Decline")
                    section.save("collaboration-library-$role-$width")
                } finally { section.close() }
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test
    fun promotionInvitesOnlySelectedAcceptedFriendAndSharedSaveUsesServer() = runBlocking(Dispatchers.Main) {
        val directory = Files.createTempDirectory("troit-collab-actions").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val library = Library(File(directory, "library.json"))
        val source = SavedPlaylist("My collection", songs)
        library.add(source)
        val sync = Sync(directory, "Preview", library, scope)
        library.onChange = null
        var creation: JSONObject? = null
        var additions = 0
        val state = CollaborativePlaylists(sync, scope, library, request = { path, _, body -> when (path) {
            "rpc/troit_collab_list" -> JSONObject().put("playlists", JSONArray().put(detail())).put("invites", JSONArray()).toString()
            "rpc/troit_collab_get" -> detail().toString()
            "rpc/troit_collab_create" -> {
                creation = JSONObject(body.orEmpty())
                detail(id = creation!!.getString("p_playlist_id"), title = source.title).put("source_id", source.id).toString()
            }
            "rpc/troit_collab_add_songs" -> { additions++; detail().toString() }
            else -> error("Unexpected fixture operation: $path")
        } }, accountId = { me })
        val social = SocialState(sync, scope, request = { _, _, _ -> snapshot() }, accountId = { me })
        var frame = 0L
        suspend fun ImageComposeScene.settle() { repeat(8) { render(frame).close(); frame += 80_000_000L; delay(15) } }
        try {
            social.refresh()
            state.refresh(forceList = true)
            var created: CollaborativePlaylist? = null
            val dialog = ImageComposeScene(390, 900, density = Density(1f), coroutineContext = coroutineContext) {
                TroitTheme { MakeCollaborativeDialog(state, social, source, { created = it }, {}) }
            }
            try {
                dialog.settle()
                assertFalse(dialog.nodes().hasText("Pending Request"))
                dialog.nodes().text("Sunday Listener").button().config[SemanticsActions.OnClick].action!!.invoke()
                dialog.settle()
                dialog.nodes().filter { SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == "Make collaborative" } }
                    .mapNotNull { runCatching { it.button() }.getOrNull() }.last().config[SemanticsActions.OnClick].action!!.invoke()
                until { created != null }
                assertEquals(listOf(friend), creation!!.getJSONArray("p_peer_ids").let { rows -> (0 until rows.length()).map(rows::getString) })
                assertEquals(source.id, creation!!.getString("p_source_id"))
                assertTrue(library.playlists.none { it.id == source.id })
            } finally { dialog.close() }
            var saved: String? = null
            val save = ImageComposeScene(390, 760, density = Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(LocalCollaborativePlaylists provides state) { TroitTheme { SaveDialog(SaveRequest(listOf(songs.first()), ""), library) { saved = it } } }
            }
            try {
                save.settle()
                save.nodes().text("Late night together").button().config[SemanticsActions.OnClick].action!!.invoke()
                until { saved != null }
                assertEquals(1, additions)
                assertTrue(library.playlists.isEmpty())
            } finally { save.close() }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
