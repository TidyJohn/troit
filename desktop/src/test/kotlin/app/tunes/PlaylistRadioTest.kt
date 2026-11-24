package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class PlaylistRadioTest {
    private fun song(id: String, title: String = id, artist: String = "Artist") = Song(title, artist, "https://music.youtube.com/watch?v=$id")
    private fun row(id: String, title: String = "New favorite", artist: String = "Similar Artist", unavailable: Boolean = false): JSONObject {
        val item = JSONObject().put("videoId", id).put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title))))
            .put("longBylineText", JSONObject().put("runs", JSONArray()
                .put(JSONObject().put("text", artist).put("navigationEndpoint", JSONObject().put("browseEndpoint", JSONObject().put("browseId", "UC-artist"))))
                .put(JSONObject().put("text", " • ")).put(JSONObject().put("text", "Album"))))
            .put("lengthText", JSONObject().put("simpleText", "3:21"))
            .put("thumbnail", JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://image.example/cover=w60-h60").put("width", 60))))
        if (unavailable) item.put("unplayableText", JSONObject().put("simpleText", "Unavailable"))
        return JSONObject().put("playlistPanelVideoRenderer", item)
    }
    private fun page(rows: List<JSONObject>): String {
        val panel = JSONObject().put("playlistPanelRenderer", JSONObject().put("contents", JSONArray(rows)))
        val queue = JSONObject().put("musicQueueRenderer", JSONObject().put("content", panel))
        val tab = JSONObject().put("tabRenderer", JSONObject().put("content", queue))
        val tabbed = JSONObject().put("watchNextTabbedResultsRenderer", JSONObject().put("tabs", JSONArray().put(tab)))
        val renderer = JSONObject().put("singleColumnMusicWatchNextResultsRenderer", JSONObject().put("tabbedRenderer", tabbed))
        return JSONObject().put("contents", renderer).toString()
    }
    private fun response(request: Request, text: String, code: Int = 200, body: ResponseBody? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(body ?: text.toResponseBody("application/json".toMediaType())).build()
    private fun http(block: (Interceptor.Chain) -> Response) = OkHttpClient.Builder().addInterceptor(block).build()

    @Test
    fun radioUsesSeveralPlaylistArtistsAndExcludesHistoryAndHiddenMusic() = runBlocking {
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queue = listOf(song("seed0000001", "First", "Main Artist"), song("seed0000002", "Second", "Other Artist"),
            song("seed0000003", "Third", "Main Artist"), song("seed0000004", "Fourth", "Third Artist"))
        val history = queue + song("heard000001", "Already heard", "Similar Artist")
        val http = http { chain ->
            val body = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            val id = body.getString("videoId")
            requested += id
            assertEquals("RDAMVM$id", body.getString("playlistId"))
            assertEquals("ID", body.getJSONObject("context").getJSONObject("client").getString("gl"))
            assertTrue(body.getBoolean("isAudioOnly"))
            response(chain.request(), page(listOf(row("seed0000001", "First", "Main Artist"),
                row("heard000001", "Already heard"), row("fresh000001", "New discovery"),
                row("hidden00001", "Bollywood hits"), row("unavail0001", unavailable = true))))
        }
        try {
            val result = PlaylistRadioClient(http, fallbackSearch = { _, _ -> error("Radio succeeded") })
                .songs(queue, ListeningPreferences(market = "ID", excludedRegions = setOf("IN")), history)
            assertEquals(setOf("seed0000003", "seed0000002", "seed0000004"), requested.toSet())
            assertEquals(listOf("New discovery"), result.map { it.title })
            assertEquals("Similar Artist", result.single().artist)
            assertEquals(201L, result.single().durationSeconds)
            assertTrue(result.single().artwork!!.contains("w544-h544"))
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun parserHandlesWrappedPrimarySongsAndIgnoresUnplayableRowsAndOtherRenderers() {
        val wrapped = JSONObject().put("playlistPanelVideoWrapperRenderer", JSONObject().put("primaryRenderer", row("fresh000001")))
        val result = PlaylistRadioClient.parse(JSONObject(page(listOf(wrapped, row("unavail0001", unavailable = true),
            JSONObject().put("playlistPanelVideoRenderer", JSONObject().put("videoId", "invalid")), JSONObject().put("automixPreviewVideoRenderer", JSONObject())))))
        assertEquals(listOf("https://music.youtube.com/watch?v=fresh000001"), result.map { it.url })
    }

    @Test
    fun recordsAndVideoIdsAreDeduplicatedAcrossDifferentUrlShapesAndSeedResults() {
        val queue = listOf(song("seed0000001", "Old favorite", "Artist"))
        val sameId = queue.single().copy(url = "https://youtu.be/seed0000001")
        val sameRecording = song("oldcopy0001", "Old favorite (Official Audio)", "ARTIST")
        val duplicate = song("fresh000001", "Discovery", "New Artist")
        val result = PlaylistRadioClient.novel(listOf(listOf(sameId, sameRecording, duplicate),
            listOf(duplicate.copy(url = "https://youtube.com/watch?v=fresh000001&list=radio"), song("fresh000002", "Another", "Next Artist"))), queue, ListeningPreferences())
        assertEquals(listOf("Discovery", "Another"), result.map { it.title })
        assertNull(PlaylistRadioClient.videoId("https://notyoutube.com/watch?v=seed0000001"))
        assertEquals("seed0000001", PlaylistRadioClient.videoId("https://youtu.be/seed0000001"))
    }

    @Test
    fun serviceFailureFallsBackOnlyToActualPlaylistArtistsAndNeverReplaysSeeds() = runBlocking {
        val http = http { chain -> response(chain.request(), "{}", code = 503) }
        val queries = mutableListOf<String>()
        val original = song("seed0000001", "Original", "Playlist Artist")
        try {
            val client = PlaylistRadioClient(http, fallbackSearch = { artist, _ ->
                queries += artist
                listOf(original, song("fresh000001", "Another song", artist), song("wrong000001", "Unrelated", "Tribute Band"))
            })
            assertEquals(listOf("Another song"), client.songs(listOf(original), ListeningPreferences()).map { it.title })
            assertEquals(listOf("Playlist Artist"), queries)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun cancellationStopsResponseReadsWithoutStartingFallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val searches = AtomicInteger()
        val http = http { chain ->
            val slow = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        started.complete(Unit)
                        while (!chain.call().isCanceled()) Thread.sleep(5)
                        throw IOException("Canceled")
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() { closed.complete(Unit) }
                }.buffer()
            }
            response(chain.request(), "", body = slow)
        }
        try {
            val client = PlaylistRadioClient(http, fallbackSearch = { _, _ -> searches.incrementAndGet(); emptyList() })
            val request = launch { client.songs(listOf(song("seed0000001")), ListeningPreferences()) }
            withTimeout(2_000) { started.await() }
            request.cancelAndJoin()
            withTimeout(2_000) { closed.await() }
            assertEquals(0, searches.get())
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun radioOutputIsBoundedAndRepresentativeSeedsPreferDominantArtists() {
        val tracks = (1..60).map { song("track%06d".format(it), "Track $it", "Artist $it") }
        assertEquals(20, PlaylistRadioClient.novel(listOf(tracks), emptyList(), ListeningPreferences()).size)
        val queue = tracks.take(10) + (1..12).map { song("main%07d".format(it), "Main $it", "Main Artist") }
        assertEquals("Main Artist", PlaylistRadioClient.seeds(queue).first().artist)
        assertEquals(3, PlaylistRadioClient.seeds(queue).size)
    }
}
