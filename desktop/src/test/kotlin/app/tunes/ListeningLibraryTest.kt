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
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ListeningLibraryTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val other = "00000000-0000-0000-0000-000000000002"
    private val song = Song("After the last train", "Evening Orchestra", "https://music.example/a", durationSeconds = 240)
    private fun fixture() = Files.createTempDirectory("troit-listening-test").toFile()
    private fun sync(dir: File, scope: CoroutineScope) = Sync(dir, "PC", Library(File(dir, "library.json")), scope)
    private fun jsonSong(song: Song) = Library.songsToJson(listOf(song)).getJSONObject(0)

    private inner class Backend {
        var online = true
        var missing = false
        var loseNextLikeReply = false
        var revision = 0L
        val rows = LinkedHashMap<String, JSONObject>()
        val events = LinkedHashMap<String, JSONObject>()
        val receipts = LinkedHashMap<String, String>()
        val uploaded = mutableListOf<JSONObject>()
        fun change(song: Song, liked: Boolean) { rows[song.url] = JSONObject().put("song", jsonSong(song)).put("liked", liked).put("revision", ++revision) }
        val request: ConnectTransport = { path, body ->
            if (missing) throw AccountApiException(404, "PGRST202", "fixture missing")
            if (!online) throw IOException("fixture offline")
            val input = JSONObject(body)
            when (path.substringAfterLast("troit_listening_")) {
                "set_like" -> {
                    uploaded += input
                    val id = input.getString("p_operation_id")
                    val answer = receipts[id] ?: run {
                        val item = input.getJSONObject("p_song")
                        val current = rows[item.getString("url")]
                        val expected = input.getLong("p_expected_revision")
                        val conflict = (current?.getLong("revision") ?: 0) != expected
                        if (!conflict) {
                            val liked = input.getBoolean("p_liked")
                            if (current == null || current.getBoolean("liked") != liked) {
                                rows[item.getString("url")] = JSONObject().put("song", item).put("liked", liked).put("revision", ++revision)
                            }
                        }
                        JSONObject().put("id", id).put("conflict", conflict)
                            .put("row", rows[item.getString("url")] ?: JSONObject.NULL).toString().also { receipts[id] = it }
                    }
                    if (loseNextLikeReply) { loseNextLikeReply = false; throw IOException("accepted, reply lost") }
                    answer
                }
                "likes" -> {
                    val after = input.getLong("p_after_seq")
                    val found = rows.values.filter { it.getLong("revision") > after }.sortedBy { it.getLong("revision") }.take(100)
                    JSONObject().put("items", JSONArray(found)).put("cursor", found.lastOrNull()?.getLong("revision") ?: after)
                        .put("has_more", false).toString()
                }
                "record" -> {
                    val id = input.getString("p_event_id")
                    if (id !in events) events[id] = JSONObject().put("id", id).put("song", input.getJSONObject("p_song"))
                        .put("seq", ++revision).put("played_at", input.getLong("p_played_at"))
                        .put("position_ms", input.getLong("p_position_ms")).put("device_name", input.getString("p_device_name"))
                    JSONObject().put("id", id).put("duplicate", false).toString()
                }
                "history" -> {
                    val after = input.getLong("p_after_seq")
                    val found = events.values.filter { it.getLong("seq") > after }.sortedBy { it.getLong("seq") }.take(100)
                    JSONObject().put("items", JSONArray(found)).put("cursor", found.lastOrNull()?.getLong("seq") ?: after)
                        .put("has_more", false).toString()
                }
                else -> error("Unexpected fixture path $path")
            }
        }
    }

    @Test fun guestLikesSurviveRestartAndNeverLeakIntoAnotherAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = fixture(); val sync = sync(dir, scope); var owner: String? = null
        val backend = Backend()
        try {
            val state = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { owner })
            state.toggleLike(song)
            assertTrue(state.isLiked(song))
            val restarted = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { owner })
            assertEquals(listOf(song), restarted.likedSongs)
            owner = me; restarted.resetForAccount()
            assertTrue(restarted.likedSongs.isEmpty())
            owner = null; restarted.resetForAccount()
            assertTrue(restarted.isLiked(song))
            restarted.toggleLike(song)
            assertFalse(restarted.isLiked(song))
        } finally { scope.cancel() }
    }

    @Test fun offlineLikeAndUnlikeUseOrderedCasRevisionsAndSurviveRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = fixture(); val sync = sync(dir, scope); val backend = Backend().apply { online = false }
        try {
            val state = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me })
            state.toggleLike(song); state.toggleLike(song)
            assertFalse(state.isLiked(song)); assertTrue(state.pending)
            val restarted = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me })
            assertFalse(restarted.isLiked(song)); assertTrue(restarted.pending)
            backend.online = true; restarted.refresh()
            assertFalse(restarted.pending); assertFalse(restarted.isLiked(song))
            assertEquals(listOf(0L, 1L), backend.uploaded.map { it.getLong("p_expected_revision") })
            assertEquals(2, backend.uploaded.map { it.getString("p_operation_id") }.distinct().size)
        } finally { scope.cancel() }
    }

    @Test fun lostLikeReplyRetriesOriginalUuidWithoutOverwritingNewerRemoteUnlike() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = fixture(); val sync = sync(dir, scope); val backend = Backend().apply { loseNextLikeReply = true }
        try {
            val state = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me })
            state.toggleLike(song)
            assertTrue(state.isLiked(song)); assertTrue(state.pending)
            val original = backend.uploaded.single().getString("p_operation_id")
            backend.change(song, false)
            state.refresh()
            assertFalse(state.isLiked(song)); assertFalse(state.pending)
            assertEquals(listOf(original, original), backend.uploaded.map { it.getString("p_operation_id") })
            assertFalse(backend.rows[song.url]!!.getBoolean("liked"))
        } finally { scope.cancel() }
    }

    @Test fun newerRemoteChoiceWinsOverStaleOfflineIntentAndCanBeExplicitlyChangedAgain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val dir = fixture(); val sync = sync(dir, scope); val backend = Backend()
        backend.change(song, false)
        try {
            val state = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me })
            state.refresh(); backend.online = false; state.toggleLike(song)
            backend.change(song, true); backend.change(song, false)
            backend.online = true; state.refresh()
            assertFalse(state.isLiked(song)); assertFalse(state.pending)
            assertTrue(state.message!!.contains("another device"))
            state.toggleLike(song)
            assertTrue(state.isLiked(song)); assertFalse(state.pending)
        } finally { scope.cancel() }
    }

    @Test fun anotherDeviceUnlikeArrivesThroughIncrementalSync() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val backend = Backend()
        val one = fixture(); val two = fixture()
        try {
            val a = ListeningLibraryState(sync(one, scope), one, scope, request = backend.request, accountId = { me })
            val b = ListeningLibraryState(sync(two, scope), two, scope, request = backend.request, accountId = { me })
            a.toggleLike(song); b.refresh(); assertTrue(b.isLiked(song))
            b.toggleLike(song); a.refresh()
            assertFalse(a.isLiked(song)); assertFalse(b.isLiked(song))
        } finally { scope.cancel() }
    }

    @Test fun historyRequiresActualPlayAndKeepsDuplicateOccurrencesWithoutRestartDuplicates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val backend = Backend()
        val dir = fixture(); val sync = sync(dir, scope); var time = 1_800_000_000_000L
        try {
            val state = ListeningLibraryState(sync, dir, scope, "TV", backend.request, { me }, { time })
            val paused = NowPlaying(listOf(song, song), 0)
            state.recordPlayback(paused, 50_000); time += 60_000; state.recordPlayback(paused, 50_000)
            assertTrue(state.history.isEmpty())
            state.recordPlayback(paused.copy(playing = true, loading = true), 0)
            time += 60_000; state.recordPlayback(paused.copy(playing = true), 0)
            repeat(3) { time += 2_000; state.recordPlayback(paused.copy(playing = true), (it + 1) * 2_000L) }
            assertEquals(1, state.history.size); assertEquals(1, backend.events.size)
            val id = state.history.single().id
            val restarted = ListeningLibraryState(sync, dir, scope, "TV", backend.request, { me }, { time })
            time += 6_000; restarted.recordPlayback(paused.copy(playing = true), 12_000)
            assertEquals(id, restarted.history.single().id); assertEquals(1, backend.events.size)
            time += 2_000; restarted.recordPlayback(paused.copy(index = 1, playing = true), 0)
            time += 5_000; restarted.recordPlayback(paused.copy(index = 1, playing = true), 5_000)
            assertEquals(2, restarted.history.size); assertEquals(2, backend.events.size)
            assertEquals(2, restarted.history.map { it.id }.distinct().size)
        } finally { scope.cancel() }
    }

    @Test fun restartingTheCurrentSongCreatesANewQualifiedPlay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture()
        var time = 1_800_000_000_000L
        try {
            val state = ListeningLibraryState(sync(dir, scope), dir, scope, clock = { time })
            val now = NowPlaying(listOf(song), 0, true)
            state.recordPlayback(now, 0); time += 5_000; state.recordPlayback(now, 5_000)
            assertEquals(1, state.history.size)
            time += 5_000; state.recordPlayback(now, 10_000)
            time += 1_000; state.recordPlayback(now, 0)
            time += 5_000; state.recordPlayback(now, 5_000)
            assertEquals(2, state.history.size)
        } finally { scope.cancel() }
    }

    @Test fun accountChangesDiscardInFlightMetadataAndKeepCachesSeparated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture(); val sync = sync(dir, scope)
        var owner: String? = me; val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<String>()
        try {
            val state = ListeningLibraryState(sync, dir, scope, request = { _, _ -> started.complete(Unit); reply.await() }, accountId = { owner })
            val loading = async { state.refresh() }; started.await()
            owner = other; state.resetForAccount()
            reply.complete(JSONObject().put("items", JSONArray().put(JSONObject().put("song", jsonSong(song)).put("liked", true).put("revision", 1)))
                .put("cursor", 1).put("has_more", false).toString())
            loading.await()
            assertTrue(state.likedSongs.isEmpty()); assertTrue(state.history.isEmpty()); assertFalse(state.pending)
        } finally { scope.cancel() }
    }

    @Test fun activityLikesAndServiceHistoryShareJournalWithoutOverwritingEachOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture(); val sync = sync(dir, scope)
        val backend = Backend().apply { online = false }; var time = 1_800_000_000_000L
        try {
            val ui = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me }, clock = { time })
            val service = ListeningLibraryState(sync, dir, scope, request = backend.request, accountId = { me }, clock = { time })
            ui.toggleLike(song)
            val now = NowPlaying(listOf(song), 0, true)
            service.recordPlayback(now, 0); time += 5_000; service.recordPlayback(now, 5_000)
            ui.refresh()
            assertTrue(ui.isLiked(song)); assertEquals(1, ui.history.size); assertTrue(ui.pending)
            backend.online = true; service.refresh(); ui.refresh()
            assertFalse(ui.pending); assertTrue(ui.isLiked(song)); assertEquals(1, backend.events.size)
        } finally { scope.cancel() }
    }

    @Test fun missingServerRetainsLocalLikesUntilMigrationBecomesAvailable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture(); val backend = Backend().apply { missing = true }
        try {
            val state = ListeningLibraryState(sync(dir, scope), dir, scope, request = backend.request, accountId = { me })
            state.toggleLike(song)
            assertTrue(state.isLiked(song)); assertTrue(state.pending); assertTrue(state.setupRequired)
            backend.missing = false; state.refresh()
            assertTrue(state.isLiked(song)); assertFalse(state.pending); assertFalse(state.setupRequired)
            state.toggleLike(song.copy(url = "file:///private"))
            assertNotNull(state.error)
        } finally { scope.cancel() }
    }

    @Test fun expiredOfflineHistoryCannotBlockNewerSynchronization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture()
        val backend = Backend().apply { online = false }; var time = 1_800_000_000_000L
        try {
            val state = ListeningLibraryState(sync(dir, scope), dir, scope, request = backend.request, accountId = { me }, clock = { time })
            val now = NowPlaying(listOf(song), 0, true)
            state.recordPlayback(now, 0); time += 5_000; state.recordPlayback(now, 5_000)
            assertTrue(state.pending)
            time += 2_678_400_000L
            backend.online = true; state.refresh()
            assertFalse(state.pending); assertEquals(1, state.history.size)
            assertTrue(backend.events.isEmpty())
            assertNotNull(state.message)
        } finally { scope.cancel() }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun heartsAndLibraryFitPhoneAndTvAndPlayTheLikedCollection() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val dir = fixture()
        try {
            val state = ListeningLibraryState(sync(dir, scope), dir, scope)
            state.toggleLike(song)
            for (width in listOf(320, 960)) {
                var played: List<Song>? = null
                val scene = ImageComposeScene(width, 700, Density(1f, if (width == 320) 1.3f else 1f), coroutineContext = coroutineContext) {
                    TroitTheme { CompositionLocalProvider(LocalListeningLibrary provides state) { Surface(Modifier.fillMaxSize()) {
                        ListeningLibraryScreen(onPlay = { songs, _ -> played = songs }, television = width == 960)
                    } } }
                }
                fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                try {
                    repeat(5) { scene.render(it * 100_000_000L).close(); delay(15) }
                    val all = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
                    val playText = all.first { SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == "Play liked songs" } }
                    val playButton = generateSequence(playText) { it.parent }.first { SemanticsActions.OnClick in it.config }
                    assertTrue(playButton.boundsInRoot.left >= 0 && playButton.boundsInRoot.right <= width)
                    assertTrue(playButton.config[SemanticsActions.OnClick].action!!.invoke())
                    assertEquals(listOf(song), played)
                    assertTrue(all.any { SemanticsProperties.ContentDescription in it.config && "Unlike ${song.title}" in it.config[SemanticsProperties.ContentDescription] })
                    val output = File("build/ui-previews").apply { mkdirs() }
                    scene.render(1_000_000_000L).use { frame -> File(output, "listening-library-$width.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
                } finally { scene.close() }
            }
        } finally { scope.cancel() }
        Unit
    }
}
