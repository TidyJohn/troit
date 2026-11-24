package app.tunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Live public-catalogue diagnostic. Runs separately from offline regression tests. */
class SearchLatencyTest {
    @Test
    fun reportDirectSearchCacheFiltersAndContinuation() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val requests = AtomicInteger()
        val started = ConcurrentHashMap<Call, Long>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { requests.incrementAndGet(); started[call] = System.nanoTime() }
            override fun callEnd(call: Call) {
                calls += "${call.request().url.host}${call.request().url.encodedPath} completed time=${ms(started.remove(call) ?: System.nanoTime())}ms"
            }
            override fun callFailed(call: Call, ioe: IOException) {
                calls += "${call.request().url.host}${call.request().url.encodedPath} cancelled=${call.isCanceled()} error=${ioe.javaClass.simpleName} time=${ms(started.remove(call) ?: System.nanoTime())}ms"
            }
        }).build()
        val search = MusicSearchClient(client)
        val report = mutableListOf("Direct search public-catalogue diagnostic; live timings are not a latency guarantee.")
        val preferences = ListeningPreferences(market = "ID")
        try {
            var before = requests.get()
            var start = System.nanoTime()
            val first = search.searchPage("daft punk get lucky", Yt.SONGS, preferences)
            report += "direct-first results=${first.items.size} requests=${requests.get() - before} time=${ms(start)}ms"
            assertTrue(first.items.isNotEmpty() && first.items.all { it is Song })
            assertTrue("Direct search must use one request", requests.get() - before == 1)

            before = requests.get(); start = System.nanoTime()
            val cached = search.searchPage("daft punk get lucky", Yt.SONGS, preferences)
            report += "cached-repeat results=${cached.items.size} requests=${requests.get() - before} time=${ms(start)}ms"
            assertTrue("A repeated search must use the bounded cache", requests.get() == before)

            val more = requireNotNull(first.more) { "The live songs query should have a continuation" }
            before = requests.get(); start = System.nanoTime()
            val next = more()
            report += "continuation results=${next.items.size} requests=${requests.get() - before} time=${ms(start)}ms"
            assertTrue(next.items.isNotEmpty() && next.items.all { it is Song })
            assertTrue(requests.get() - before == 1)

            for ((name, filter) in listOf("artists" to Yt.ARTISTS, "albums" to Yt.ALBUMS, "playlists" to Yt.PLAYLISTS, "uploads" to Yt.VIDEOS)) {
                before = requests.get(); start = System.nanoTime()
                val page = search.searchPage("daft punk get lucky", filter, preferences)
                report += "$name results=${page.items.size} requests=${requests.get() - before} time=${ms(start)}ms"
                assertTrue("$name should return live results", page.items.isNotEmpty())
                assertTrue(requests.get() - before == 1)
                assertTrue(page.items.all {
                    when (filter) {
                        Yt.ARTISTS -> it is Artist
                        Yt.ALBUMS, Yt.PLAYLISTS -> it is Playlist
                        else -> it is Song
                    }
                })
            }
            before = requests.get(); start = System.nanoTime()
            val changedMarket = search.searchPage("daft punk get lucky", Yt.SONGS, ListeningPreferences(market = "US"))
            report += "different-market results=${changedMarket.items.size} requests=${requests.get() - before} time=${ms(start)}ms"
            assertTrue(requests.get() - before == 1 && changedMarket.items.isNotEmpty())

            val pending = async { search.searchPage("radiohead paranoid android", Yt.SONGS, preferences) }
            delay(60)
            start = System.nanoTime()
            pending.cancelAndJoin()
            report += "cancel-superseded job-completed-in=${ms(start)}ms"
            delay(50) // Let the HTTP cancellation callback record its diagnostic.
            report += "cached-first-pages=${search.cachedPages}"
        } finally {
            val text = (report + calls.toList()).joinToString("\n", postfix = "\n")
            File("build/search-diagnostics").apply { mkdirs() }.resolve("direct-search-report.txt").writeText(text)
            println(text)
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun reportLegacyColdWarmAndSupersededSearchRequests() = runBlocking {
        Yt.http // Initialize the app's extractor before replacing its downloader in this isolated test JVM.
        val originalDownloader = NewPipe.getDownloader()
        val phase = ThreadLocal<String>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val report = mutableListOf<String>()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val started = System.nanoTime()
                val method = request.httpMethod()
                val body = request.dataToSend()?.toRequestBody()
                    ?: if (method == "POST") ByteArray(0).toRequestBody() else null
                val builder = okhttp3.Request.Builder().url(request.url()).method(method, body)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
                for ((name, values) in request.headers()) {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                val built = builder.build()
                return try {
                    client.newCall(built).execute().use { response ->
                        val text = response.body?.string()
                        calls += "${phase.get()} $method ${built.url.host}${built.url.encodedPath} HTTP=${response.code} time=${ms(started)}ms bytes=${text?.length ?: 0}"
                        Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
                    }
                } catch (error: Exception) {
                    calls += "${phase.get()} $method ${built.url.host}${built.url.encodedPath} error=${error.javaClass.simpleName} time=${ms(started)}ms"
                    throw error
                }
            }
        })
        try {
            fun measure(name: String, query: String) {
                phase.set(name)
                val firstCall = calls.size
                val started = System.nanoTime()
                val results = Yt.searchPage(query, Yt.SONGS)
                report += "$name query=$query results=${results.items.size} requests=${calls.size - firstCall} time=${ms(started)}ms"
                assertTrue("Live search returned no catalogue results", results.items.isNotEmpty())
            }
            measure("cold", "daft punk get lucky")
            measure("warm-repeat", "daft punk get lucky")
            val old = async(Dispatchers.IO + phase.asContextElement("superseded")) {
                Yt.searchPage("the weeknd blinding lights", Yt.SONGS)
            }
            delay(100)
            val cancelledAt = System.nanoTime()
            old.cancel()
            val replacement = async(Dispatchers.IO + phase.asContextElement("replacement")) {
                Yt.searchPage("the weeknd starboy", Yt.SONGS)
            }
            val fresh = replacement.await()
            old.join()
            report += "replacement results=${fresh.items.size} elapsed_after_superseding=${ms(cancelledAt)}ms"
            assertTrue(fresh.items.isNotEmpty())
        } finally {
            val text = (listOf("Legacy NewPipe public-search diagnostic; these are live network timings, not a latency guarantee.") + report + calls.toList()).joinToString("\n", postfix = "\n")
            File("build/search-diagnostics").apply { mkdirs() }.resolve("legacy-search-report.txt").writeText(text)
            println(text)
            NewPipe.init(originalDownloader)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun ms(start: Long) = String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1_000_000.0)
}
