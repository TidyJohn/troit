package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Public catalogue HTTP is intercepted locally; no account or live requests are used. */
class MusicSearchTest {
    private fun response(request: Request, json: String, code: Int = 200, body: ResponseBody? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
        .body(body ?: json.toResponseBody("application/json".toMediaType())).build()

    private fun http(block: (Interceptor.Chain) -> Response) = OkHttpClient.Builder().addInterceptor(block).build()

    private fun row(title: String = "Get Lucky", type: String = "song", unavailable: Boolean = false): JSONObject {
        val pageType = when (type) { "artist" -> "MUSIC_PAGE_TYPE_ARTIST"; "album" -> "MUSIC_PAGE_TYPE_ALBUM"; else -> "MUSIC_PAGE_TYPE_PLAYLIST" }
        fun nav(id: String, kind: String) = JSONObject().put("browseEndpoint", JSONObject().put("browseId", id)
            .put("browseEndpointContextSupportedConfigs", JSONObject().put("browseEndpointContextMusicConfig", JSONObject().put("pageType", kind))))
        val titleRun = JSONObject().put("text", title)
        if (type == "song") titleRun.put("navigationEndpoint", JSONObject().put("watchEndpoint", JSONObject().put("videoId", title)
            .put("watchEndpointMusicSupportedConfigs", JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", "MUSIC_VIDEO_TYPE_OMV")))))
        val secondaryRuns = JSONArray().put(JSONObject().put("text", "Daft Punk").put("navigationEndpoint", nav("artist-id", "MUSIC_PAGE_TYPE_ARTIST")))
            .put(JSONObject().put("text", " • ")).put(JSONObject().put("text", "3:12"))
        fun column(runs: JSONArray) = JSONObject().put("musicResponsiveListItemFlexColumnRenderer", JSONObject().put("text", JSONObject().put("runs", runs)))
        val renderer = JSONObject().put("flexColumns", JSONArray().put(column(JSONArray().put(titleRun))).put(column(secondaryRuns)))
        if (type != "song") renderer.put("navigationEndpoint", nav("$type-id", pageType))
        if (unavailable) renderer.put("musicItemRendererDisplayPolicy", "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT")
        return JSONObject().put("musicResponsiveListItemRenderer", renderer)
    }

    private fun page(rows: List<JSONObject> = listOf(row()), token: String? = null, continuation: Boolean = false): String {
        val shelf = JSONObject().put("contents", JSONArray(rows))
        token?.let { shelf.put("continuations", JSONArray().put(JSONObject().put("nextContinuationData", JSONObject().put("continuation", it)))) }
        val response = JSONObject().put("responseContext", JSONObject().put("visitorData", "visitor-context"))
        if (continuation) {
            response.put("continuationContents", JSONObject().put("musicShelfContinuation", shelf))
        } else {
            val sections = JSONArray().put(JSONObject().put("musicShelfRenderer", shelf))
            val content = JSONObject().put("sectionListRenderer", JSONObject().put("contents", sections))
            val tab = JSONObject().put("tabRenderer", JSONObject().put("content", content))
            response.put("contents", JSONObject().put("tabbedSearchResultsRenderer", JSONObject().put("tabs", JSONArray().put(tab))))
        }
        return response.toString()
    }

    @Test
    fun firstPageUsesOneRequestAndCacheReappliesCurrentPreferences() = runBlocking {
        val calls = AtomicInteger()
        val http = http { chain ->
            calls.incrementAndGet()
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertEquals("ID", JSONObject(body).getJSONObject("context").getJSONObject("client").getString("gl"))
            assertEquals("Daft Punk", JSONObject(body).getString("query"))
            response(chain.request(), page(listOf(row("Get Lucky"), row("Bollywood hits"))))
        }
        try {
            val client = MusicSearchClient(http)
            val hidden = client.searchPage(" Daft   Punk ", Yt.SONGS, ListeningPreferences("ID", excludedRegions = setOf("IN")))
            assertEquals(listOf("Get Lucky"), hidden.items.map { it.title })
            val song = hidden.items.single() as Song
            assertEquals("Daft Punk", song.artist)
            assertEquals(192L, song.durationSeconds)
            val restored = client.searchPage("daft punk", Yt.SONGS, ListeningPreferences(market = "ID"))
            assertEquals(2, restored.items.size)
            assertEquals(1, calls.get())
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun cacheIsSeparatedByMarketAndFilterExpiresAndEvicts() = runBlocking {
        var now = 0L
        val calls = AtomicInteger()
        val http = http { chain -> calls.incrementAndGet(); response(chain.request(), page()) }
        try {
            val client = MusicSearchClient(http, clockMs = { now }, cacheSize = 2, cacheTtlMs = 100)
            client.searchPage("query", Yt.SONGS, ListeningPreferences(market = "ID"))
            client.searchPage("query", Yt.ARTISTS, ListeningPreferences(market = "ID"))
            client.searchPage("query", Yt.SONGS, ListeningPreferences(market = "US"))
            assertEquals(3, calls.get())
            assertEquals(2, client.cachedPages)
            client.searchPage("query", Yt.SONGS, ListeningPreferences(market = "ID"))
            assertEquals(4, calls.get())
            now = 101
            client.searchPage("query", Yt.SONGS, ListeningPreferences(market = "ID"))
            assertEquals(5, calls.get())
            assertTrue(client.cachedPages <= 2)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun continuationKeepsQueryFilterMarketVisitorAndSupportsEmptyPages() = runBlocking {
        val calls = AtomicInteger()
        val http = http { chain ->
            val count = calls.incrementAndGet()
            val body = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            assertEquals("query", body.getString("query"))
            assertEquals(MusicSearchClient.filters.getValue(Yt.SONGS), body.getString("params"))
            val client = body.getJSONObject("context").getJSONObject("client")
            assertEquals("ID", client.getString("gl"))
            if (count == 1) response(chain.request(), page(emptyList(), "next-one"))
            else {
                assertEquals("next-one", chain.request().url.queryParameter("continuation"))
                assertEquals("next-one", chain.request().url.queryParameter("ctoken"))
                assertEquals("visitor-context", client.getString("visitorData"))
                response(chain.request(), page(listOf(row("Second page")), continuation = true))
            }
        }
        try {
            val client = MusicSearchClient(http)
            val first = client.searchPage("query", Yt.SONGS, ListeningPreferences(market = "ID"))
            assertTrue(first.items.isEmpty())
            val next = first.more!!.invoke()
            assertEquals("Second page", next.items.single().title)
            assertNull(next.more)
            assertEquals(2, calls.get())
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun allSearchFiltersPreserveTheirResultTypeAndUnavailableSongsAreSkipped() = runBlocking {
        val http = http { chain ->
            val params = JSONObject(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).getString("params")
            val filter = MusicSearchClient.filters.entries.single { it.value == params }.key
            val type = when (filter) { Yt.ARTISTS -> "artist"; Yt.ALBUMS -> "album"; Yt.PLAYLISTS -> "playlist"; else -> "song" }
            response(chain.request(), page(listOf(row(type = type), row("Unavailable", unavailable = true))))
        }
        try {
            val client = MusicSearchClient(http)
            assertTrue(client.searchPage("query", Yt.SONGS).items.single() is Song)
            val videos = client.searchPage("query", Yt.VIDEOS)
            val video = videos.items.single() as Song
            assertEquals("MUSIC_VIDEO_TYPE_OMV", videos.videoTypes[video.url])
            assertTrue(client.searchPage("query", Yt.ARTISTS).items.single() is Artist)
            assertTrue(client.searchPage("query", Yt.ALBUMS).items.single() is Playlist)
            assertTrue(client.searchPage("query", Yt.PLAYLISTS).items.single() is Playlist)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun failedAndMalformedResponsesAreNotCached() = runBlocking {
        val count = AtomicInteger()
        val http = http { chain -> when (count.incrementAndGet()) {
            1 -> response(chain.request(), "{}", 429)
            2 -> response(chain.request(), "{\"unexpected\":true}")
            else -> response(chain.request(), page())
        } }
        try {
            val client = MusicSearchClient(http)
            repeat(2) {
                try { client.searchPage("query", Yt.SONGS); fail("Invalid searches must fail") } catch (_: IOException) { }
                assertEquals(0, client.cachedPages)
            }
            assertEquals(1, client.searchPage("query", Yt.SONGS).items.size)
            assertEquals(3, count.get())
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun cancellationAbortsTheActualHttpCall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val http = http { chain ->
            entered.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(1)
            cancelled.complete(Unit)
            throw IOException("Cancelled offline request")
        }
        try {
            val client = MusicSearchClient(http)
            val job = launch { client.searchPage("query", Yt.SONGS) }
            withTimeout(2_000) { entered.await() }
            job.cancelAndJoin()
            withTimeout(2_000) { cancelled.await() }
            assertEquals(0, client.cachedPages)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun cancellationAlsoAbortsBodyDownloadAndClosesResponse() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val http = http { chain ->
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.complete(Unit)
                    while (!chain.call().isCanceled()) Thread.sleep(1)
                    throw IOException("Cancelled body read")
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.complete(Unit) }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }
            response(chain.request(), "", body = body)
        }
        try {
            val client = MusicSearchClient(http)
            val job = launch { client.searchPage("query", Yt.SONGS) }
            withTimeout(2_000) { entered.await() }
            job.cancelAndJoin()
            withTimeout(2_000) { closed.await() }
            assertEquals(0, client.cachedPages)
        } finally { http.dispatcher.executorService.shutdown() }
    }

    @Test
    fun oversizedResponseAndInvalidQueriesFailWithoutCacheGrowth() = runBlocking {
        val count = AtomicInteger()
        val closed = AtomicBoolean()
        val http = http { chain ->
            count.incrementAndGet()
            val source = object : Source {
                var sent = 0L
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (sent >= 2_000_001L) return -1
                    val length = minOf(byteCount, 2_000_001L - sent).toInt()
                    sink.write(ByteArray(length) { 'x'.code.toByte() }); sent += length
                    return length.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() { closed.set(true) }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }
            response(chain.request(), "", body = body)
        }
        try {
            val client = MusicSearchClient(http)
            for (query in listOf("", " ", "x".repeat(257))) {
                try { client.searchPage(query, Yt.SONGS); fail("Invalid query") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, count.get())
            try { client.searchPage("query", Yt.SONGS); fail("Oversized response") } catch (_: IOException) { }
            assertTrue(closed.get())
            assertEquals(0, client.cachedPages)
        } finally { http.dispatcher.executorService.shutdown() }
    }
}
