package app.tunes

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real native EOFs with local silent PCM files: no upstream catalogue or streaming dependency. */
class MpvPlaybackModesTest {
    private val songs = listOf(Song("First", "Playlist artist", "first"), Song("Second", "Playlist artist", "second"))
    private val suggested = Song("New discovery", "Related artist", "suggested")

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("Timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(40)
        }
    }

    private class Fixture(
        durationSeconds: Double = 1.2,
        sourceFile: (String, File) -> String = { _, wave -> wave.absolutePath },
        radio: suspend (List<Song>, ListeningPreferences, List<Song>) -> List<Song> = { _, _, _ -> emptyList() },
    ) : AutoCloseable {
        val dir = Files.createTempDirectory("troit-mpv-modes").toFile()
        val loaded = CopyOnWriteArrayList<String>()
        private val wave = File(dir, "silence.wav").apply {
            val samples = (durationSeconds * 16_000).toInt() // Long enough for a native time-pos sample.
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
            writeBytes(header + ByteArray(samples * 2))
        }
        val player = MpvPlayer(listOf("--ao=null"), dir, MpvDependencies(
            audioSource = { url -> loaded += url; AudioSource(sourceFile(url, wave), AudioQuality("PCM", 256)) },
            radio = { seeds, preferences, queue, _ -> radio(seeds, preferences, queue) },
        ))
        init { player.setTransitionSettings(TransitionSettings(gapless = false)) }
        override fun close() { player.close(); dir.deleteRecursively() }
    }

    private fun requireMpv() = assumeTrue("Bundled mpv required for native EOF tests", File("resources/windows/mpv.exe").isFile)

    @Test
    fun nativeEndStopsOrRepeatsAndManualNextBypassesRepeatOne() {
        requireMpv()
        Fixture().use { f ->
            f.player.play(songs, 0)
            waitFor("stop at end") { f.loaded.size == 2 && !f.player.now.playing && !f.player.now.loading }
            assertNull(f.player.now.error)
            assertEquals(1, f.player.now.index)

            f.player.setPlaybackMode(PlaybackMode.REPEAT_ALL)
            f.player.play(songs, 1)
            waitFor("repeat queue wraps to first") { f.loaded.size >= 4 }
            assertEquals(listOf("second", "first"), f.loaded.toList().drop(2).take(2))

            f.player.setPlaybackMode(PlaybackMode.REPEAT_ONE)
            val before = f.loaded.size
            f.player.play(songs, 0)
            waitFor("repeat current song") { f.loaded.size >= before + 2 }
            assertEquals(listOf("first", "first"), f.loaded.toList().drop(before).take(2))
            f.player.next()
            waitFor("manual next escapes repeat one") { f.loaded.last() == "second" }
            assertEquals(PlaybackMode.REPEAT_ONE, f.player.playbackMode)
            assertEquals("REPEAT_ONE", File(f.dir, "playback-mode.txt").readText())
        }
    }

    @Test
    fun radioUsesOriginalSeedsAndFiltersDuplicatesAndCurrentMusicPreferences() {
        requireMpv()
        val requested = CompletableFuture<Pair<List<Song>, List<Song>>>()
        val result = CompletableFuture<List<Song>>()
        Fixture { seeds, _, played -> requested.complete(seeds to played); result.get(10, TimeUnit.SECONDS) }.use { f ->
            f.player.setPlaybackMode(PlaybackMode.RADIO)
            f.player.play(songs, 1)
            val request = requested.get(15, TimeUnit.SECONDS)
            assertTrue(request.first.isNotEmpty() && request.first.size <= 3)
            assertTrue(request.first.all { seed -> songs.any { it.url == seed.url } })
            assertEquals(songs, request.second)
            RegionPreferences(File(f.dir, "listening-preferences.json")).setIndianMusicExcluded(true)
            result.complete(listOf(songs.first(), suggested, suggested,
                Song("Bollywood hits", "Singer", "excluded")))
            waitFor("new music begins") { f.loaded.contains("suggested") }
            assertEquals(listOf("first", "second", "suggested"), f.player.now.queue.map { it.url })
            assertEquals(2, songs.size)
        }
    }

    @Test
    fun thousandSongQueueRetainsUpcomingSongsThenCompactsOnlyAfterCompletion() {
        requireMpv()
        val original = (0 until 1000).map { Song("Playlist song $it", "Original artist", "original-$it") }
        Fixture(durationSeconds = 8.0, radio = { _, _, _ -> listOf(suggested) }).use { f ->
            f.player.setPlaybackMode(PlaybackMode.RADIO)
            f.player.play(original, 500)
            waitFor("large original queue starts") { !f.player.now.loading && f.player.now.index == 500 }
            assertEquals(original, f.player.now.queue)
            f.player.next()
            waitFor("manual Next keeps all unplayed original songs") { f.player.now.index == 501 && !f.player.now.loading }
            assertEquals(original, f.player.now.queue)
            f.player.play(original, original.lastIndex)
            waitFor("final original song starts") { f.player.now.index == original.lastIndex && !f.player.now.loading }
            f.player.next()
            waitFor("rolling radio tail replaces only completed history") { f.player.now.song?.url == suggested.url && !f.player.now.loading }
            assertEquals(41, f.player.now.queue.size)
            assertEquals(original.takeLast(40), f.player.now.queue.dropLast(1))
            assertEquals(40, f.player.now.index)
            assertEquals(1000, original.size)
        }
    }

    @Test
    fun pausingPendingRadioRejectsLateReplyAndNewQueueStaysSelected() {
        requireMpv()
        val requested = CompletableFuture<Unit>()
        val result = CompletableFuture<List<Song>>()
        Fixture { _, _, _ -> requested.complete(Unit); result.get(10, TimeUnit.SECONDS) }.use { f ->
            f.player.setPlaybackMode(PlaybackMode.RADIO)
            f.player.play(songs, 1)
            requested.get(15, TimeUnit.SECONDS)
            assertTrue(f.player.now.loading)
            f.player.toggle()
            waitFor("radio pause cancels loading intent") { !f.player.now.playing && !f.player.now.loading }
            result.complete(listOf(suggested)) // Simulates a transport that finishes despite coroutine cancellation.
            Thread.sleep(750)
            assertFalse(f.loaded.contains("suggested"))
            assertEquals(songs, f.player.now.queue)

            f.player.setPlaybackMode(PlaybackMode.STOP)
            f.player.play(listOf(songs.first()), 0)
            waitFor("replacement queue") { f.player.now.queue.size == 1 }
            assertEquals("first", f.player.now.song?.url)
            assertEquals(PlaybackMode.STOP, f.player.playbackMode)
        }
    }

    @Test
    fun decoderErrorsNeverRepeatOrRequestRadio() {
        requireMpv()
        for (mode in listOf(PlaybackMode.REPEAT_ONE, PlaybackMode.REPEAT_ALL, PlaybackMode.RADIO)) {
            val requests = AtomicInteger()
            Fixture(sourceFile = { _, wave -> File(wave.parentFile, "missing-audio.wav").absolutePath },
                radio = { _, _, _ -> requests.incrementAndGet(); listOf(suggested) }).use { f ->
                f.player.setPlaybackMode(mode)
                f.player.play(songs, 0)
                waitFor("decoder failure stops in $mode") { f.player.now.error != null }
                assertFalse(f.player.now.playing)
                assertFalse(f.player.now.loading)
                Thread.sleep(1_000)
                assertEquals("Failed audio must not loop in $mode", listOf("first"), f.loaded.toList())
                assertEquals("Failed audio must not request radio in $mode", 0, requests.get())
            }
        }
    }

    @Test
    fun replacingQueueDuringRadioRejectsUncancelableLateResponse() {
        requireMpv()
        val requested = CompletableFuture<Unit>()
        val result = CompletableFuture<List<Song>>()
        Fixture(durationSeconds = 8.0, radio = { _, _, _ ->
            requested.complete(Unit); result.get(10, TimeUnit.SECONDS)
        }).use { f ->
            f.player.setPlaybackMode(PlaybackMode.RADIO)
            f.player.play(songs, 1)
            waitFor("original track starts") { !f.player.now.loading && f.player.now.playing }
            f.player.next() // Final manual Next can also request similar music.
            requested.get(15, TimeUnit.SECONDS)
            val replacement = Song("Replacement queue", "Another artist", "replacement")
            f.player.play(listOf(replacement), 0)
            waitFor("replacement starts while old request is still pending") {
                f.player.now.song?.url == replacement.url && !f.player.now.loading
            }
            result.complete(listOf(suggested))
            Thread.sleep(750)
            assertEquals(listOf(replacement), f.player.now.queue)
            assertTrue(f.player.now.playing)
            assertFalse(f.loaded.contains("suggested"))
            assertEquals(PlaybackMode.RADIO, f.player.playbackMode)
        }
    }

    @Test
    fun changingModeDuringRadioRejectsUncancelableLateResponse() {
        requireMpv()
        val requested = CompletableFuture<Unit>()
        val result = CompletableFuture<List<Song>>()
        Fixture(durationSeconds = 8.0, radio = { _, _, _ ->
            requested.complete(Unit); result.get(10, TimeUnit.SECONDS)
        }).use { f ->
            f.player.setPlaybackMode(PlaybackMode.RADIO)
            f.player.play(songs, 1)
            waitFor("original track starts") { !f.player.now.loading && f.player.now.playing }
            f.player.next()
            requested.get(15, TimeUnit.SECONDS)
            f.player.setPlaybackMode(PlaybackMode.STOP)
            waitFor("changing mode cancels radio intent") {
                f.player.playbackMode == PlaybackMode.STOP && !f.player.now.loading
            }
            result.complete(listOf(suggested))
            Thread.sleep(750)
            assertEquals(songs, f.player.now.queue)
            assertFalse(f.player.now.playing)
            assertFalse(f.loaded.contains("suggested"))
        }
    }
}
