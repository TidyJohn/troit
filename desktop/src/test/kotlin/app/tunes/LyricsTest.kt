package app.tunes

import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local fixtures only; lyric lines below are test strings, never a real song's lyrics. */
class LyricsTest {
    private val song = Song("Fixture recording", "Fixture artist", "https://example.invalid/fixture", durationSeconds = 180)
    private fun record(title: String = song.title, artist: String = song.artist, duration: Int = 180,
        timed: String? = "[00:01.00]Test first line\n[00:03.50]Test second line", plain: String? = "Test first line\nTest second line", instrumental: Boolean = false) =
        JSONObject().put("id", 42).put("trackName", title).put("artistName", artist).put("duration", duration)
            .put("syncedLyrics", timed ?: JSONObject.NULL).put("plainLyrics", plain ?: JSONObject.NULL).put("instrumental", instrumental).toString()
    private fun response(request: Request, text: String, code: Int = 200, retry: String? = null, type: String = "application/json") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Offline fixture")
            .body(text.toResponseBody(type.toMediaType())).apply { retry?.let { header("Retry-After", it) } }.build()

    @Test fun parserHandlesFractionsRepeatedTimestampsMetadataOffsetAndInstrumentalBreaks() {
        val lines = parseLrc("[ar:Metadata]\n[00:02.5][00:04.25]Test repeat\n[00:03.125]Test millisecond\n[00:05.00]\n[00:99.00]Bad seconds\n[bad]Ignore\n[offset:+500]")
        assertEquals(listOf(2_000L, 2_625L, 3_750L, 4_500L), lines.map { it.timeMs })
        assertEquals(listOf("Test repeat", "Test millisecond", "Test repeat", ""), lines.map { it.text })
        val lyrics = LyricsResult(lines)
        assertEquals(-1, lyrics.activeIndex(1_999))
        assertEquals(0, lyrics.activeIndex(2_000))
        assertEquals(1, lyrics.activeIndex(3_000))
        assertEquals(3, lyrics.activeIndex(9_000))
        assertEquals(emptyList<LyricLine>(), parseLrc("[00:01.00]\n[ti:Title]"))
        assertEquals(1_250L, parseLrc("[offset:-250]\n[00:01.00]Test").single().timeMs)
    }

    @Test fun parserIsBoundedAndDuplicateTimesChooseTheFinalLine() {
        val lines = parseLrc((0 until 3_000).joinToString("\n") { "[00:01.00]Test $it" })
        assertEquals(2_000, lines.size)
        assertEquals(1_999, LyricsResult(lines).activeIndex(1_000))
        assertTrue(runCatching { parseLrc("x".repeat(128_001)) }.isFailure)
    }

    @Test fun exactLookupUsesIdentifiedClientAndCacheWithoutUploadingAccountOrQueue() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("/api/get", chain.request().url.encodedPath)
            assertEquals(song.title, chain.request().url.queryParameter("track_name"))
            assertEquals("180", chain.request().url.queryParameter("duration"))
            assertTrue(chain.request().header("User-Agent")!!.startsWith("Troit"))
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().body)
            response(chain.request(), record())
        }.build()
        val client = LyricsClient(http, throttleMs = 0)
        try {
            val first = client.get(song)
            assertEquals(2, first.lines.size)
            assertSame(first, client.get(song))
            assertEquals(1, calls)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun titleArtistAndDurationMismatchesNeverBecomeLyricsForThisSong() = runBlocking {
        for (wrong in listOf(record(title = "Different recording"), record(artist = "Different artist"), record(duration = 183))) {
            val http = OkHttpClient.Builder().addInterceptor { response(it.request(), wrong) }.build()
            try { assertFalse(LyricsClient(http, throttleMs = 0).get(song).available) }
            finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
        Unit
    }

    @Test fun plainLyricsAndInstrumentalRemainExplicitlyUntimed() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(),
            if (chain.request().url.queryParameter("track_name") == "Instrumental") record(title = "Instrumental", instrumental = true, timed = null, plain = null)
            else record(timed = null)) }.build()
        try {
            val client = LyricsClient(http, throttleMs = 0)
            val plain = client.get(song)
            assertFalse(plain.timed); assertNotNull(plain.plainText); assertEquals(-1, plain.activeIndex(90_000))
            val instrumental = client.get(song.copy(title = "Instrumental"))
            assertTrue(instrumental.instrumental); assertFalse(instrumental.timed)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun providerRetryAfterIsHonoredAcrossSongsAndCacheIsBounded() = runBlocking {
        var now = 1_000L; var calls = 0; var rateLimited = true
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            if (rateLimited) response(chain.request(), "Not JSON", 429, "10", "text/plain")
            else response(chain.request(), "", 404)
        }.build()
        try {
            val client = LyricsClient(http, clock = { now }, throttleMs = 0, jitter = { 0.0 })
            val limited = runCatching { client.get(song) }.exceptionOrNull() as LyricsUnavailable
            assertEquals(11_000L, limited.retryAtMillis)
            assertTrue(runCatching { client.get(song.copy(title = "Another")) }.isFailure)
            assertEquals(1, calls)
            now = 11_000; rateLimited = false
            repeat(40) { assertFalse(client.get(song.copy(title = "Track $it")).available) }
            assertEquals(32, client.cacheSize)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test fun oversizedAndNonJsonResponsesFailWithoutCachingThem() = runBlocking {
        for ((text, type) in listOf("x".repeat(256_001) to "application/json", "<html>Offline</html>" to "text/html")) {
            val http = OkHttpClient.Builder().addInterceptor { response(it.request(), text, type = type) }.build()
            try {
                val client = LyricsClient(http, throttleMs = 0)
                assertTrue(runCatching { client.get(song) }.isFailure)
                assertEquals(0, client.cacheSize)
            } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
        Unit
    }

    @Test fun switchingTracksCancelsTheHttpCallAndLateStateCannotReplaceNewLyrics() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.queryParameter("track_name") == song.title) {
                started.countDown(); release.await(5, TimeUnit.SECONDS)
            }
            if (chain.call().isCanceled()) throw IOException("Fixture cancelled")
            response(chain.request(), record(title = chain.request().url.queryParameter("track_name")!!))
        }.build()
        try {
            val state = LyricsState(LyricsClient(http, throttleMs = 0))
            val old = launch { state.load(song) }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
            old.cancelAndJoin(); release.countDown()
            state.load(song.copy(title = "New recording"))
            assertEquals(42L, state.result?.sourceId)
            assertFalse(state.loading); assertNull(state.error)
        } finally { release.countDown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
