package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SpotifyImportTest {
    private fun track(index: Int) = SpotifyTrack("Song $index", listOf("Artist"), 180_000)
    private fun match(track: SpotifyTrack) = Match(track, Song(track.title, "Artist", track.title), true)

    @Test
    fun importCapAppliesAcrossLinksBeforeMatchingAndResultsKeepSourceOrder() = runBlocking {
        var reads = 0
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val session = prepareSpotifyImport("https://open.spotify.com/playlist/0123456789ABCDEFGHIJKL " +
            "https://open.spotify.com/album/0123456789ABCDEFGHIJKM", readLink = { _, _ ->
            reads++
            SpotifyImportSource((0 until 1200).map(::track))
        }, matcher = { song ->
            val inFlight = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, inFlight) }
            delay(1)
            active.decrementAndGet()
            match(song)
        })
        assertEquals(1, reads)
        val progress = mutableListOf<Int>()
        val result = session.run { done, total -> assertEquals(1000, total); progress += done }
        assertEquals(1000, result.songs.size)
        assertEquals("Song 999", result.songs.last().title)
        assertEquals(2, maximum.get())
        assertEquals(1000, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(result.warnings.any { it.contains("1,000") })
    }

    @Test
    fun cancellationAndResumeKeepCompletedMatchesWithoutRepeatingThem() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        var paused = true
        val seen = mutableMapOf<String, Int>()
        val session = SpotifyImportSession((0 until 12).map(::track), match = { song ->
            if (song.title.substringAfter(' ').toInt() >= 4 && paused) { reached.complete(Unit); delay(60_000) }
            seen[song.title] = (seen[song.title] ?: 0) + 1
            match(song)
        })
        val first = launch { session.run { _, _ -> } }
        reached.await()
        first.cancelAndJoin()
        assertEquals(4, session.done)
        assertEquals(4, session.songs.size)
        paused = false
        assertEquals(12, session.run { _, _ -> }.songs.size)
        assertTrue(seen.values.all { it == 1 })
    }

    @Test
    fun matchingFailuresDoNotDiscardSuccessfulSongsAndEmbedSourcesWarnAboutPreviewLimits() = runBlocking {
        val session = SpotifyImportSession((0 until 5).map(::track), match = { song ->
            if (song.title == "Song 2") error("Network fixture failed")
            match(song)
        })
        val result = session.run { _, _ -> }
        assertEquals(4, result.songs.size)
        assertEquals(listOf("Artist - Song 2"), result.notFound)
        val entity = JSONObject().put("type", "playlist").put("trackList", JSONArray().put(
            JSONObject().put("title", "Song").put("subtitle", "Artist").put("duration", 180000)))
        val html = "<script id=\"__NEXT_DATA__\">" + JSONObject().put("props", JSONObject().put("pageProps", JSONObject()
            .put("state", JSONObject().put("data", JSONObject().put("entity", entity))))) + "</script>"
        val source = Spotify.parseEmbed(html)
        assertEquals(1, source.tracks.size)
        assertTrue(source.warnings.single().contains("Connect Spotify"))
    }
}
