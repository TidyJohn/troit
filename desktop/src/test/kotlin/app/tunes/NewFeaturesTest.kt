package app.tunes

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Downloads, song sharing, stats, and the Windows volume and levelling controls. */
class NewFeaturesTest {
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) {
            assertTrue("Timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(30)
        }
    }

    private fun silence(file: File, seconds: Double) = file.apply {
        val samples = (seconds * 16_000).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
        writeBytes(header + ByteArray(samples * 2))
    }

    @Test
    fun downloadIsFetchedInRangesPlaysFromDiskSurvivesRestartAndCanBeRemoved() {
        val audio = ByteArray(2_500) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/audio") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range").also { ranges += it }.removePrefix("bytes=").split('-')
                val from = range[0].toInt()
                val to = minOf(range[1].toInt(), audio.lastIndex)
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${audio.size}")
                exchange.sendResponseHeaders(206, (to - from + 1).toLong())
                exchange.responseBody.use { it.write(audio, from, to - from + 1) }
            }
            start()
        }
        val dir = Files.createTempDirectory("troit-downloads").toFile()
        val song = Song("Offline song", "Artist", "https://music.youtube.com/watch?v=offline0001")
        val other = Song("Missing", "Artist", "https://music.youtube.com/watch?v=missing0001")
        try {
            Downloads.chunkBytes = 1_000
            Downloads.resolve = { url ->
                if (url == song.url) AudioSource("http://127.0.0.1:${server.address.port}/audio", AudioQuality("Opus", 160))
                else AudioSource("http://127.0.0.1:${server.address.port}/gone", AudioQuality("Opus", 160))
            }
            Downloads.init(dir)
            Downloads.download(listOf(song, other))
            waitFor("downloads settle") { Downloads.progress.isEmpty() }

            assertTrue(Downloads.isDownloaded(song.url))
            assertEquals(listOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2999"), ranges.toList())
            val local = Yt.audioSource(song.url)
            assertArrayEquals(audio, File(local.url).readBytes())
            assertEquals(AudioQuality("Opus", 160), Yt.cachedAudioQuality(song.url))
            assertFalse("a failed download is not listed", Downloads.isDownloaded(other.url))
            assertNotNull(Downloads.error)
            assertTrue("no partial file is left behind", File(dir, "downloads").list().orEmpty().none { it.endsWith(".part") })

            Downloads.init(Files.createTempDirectory("troit-downloads-other").toFile())
            assertFalse(Downloads.isDownloaded(song.url))
            Downloads.init(dir) // a restart reads the saved index
            assertEquals(listOf(song), Downloads.entries.map { it.song })
            assertEquals(2_500L, Downloads.totalBytes)

            Downloads.remove(listOf(song.url))
            assertNull(Downloads.localSource(song.url))
            assertFalse(File(local.url).exists())
        } finally {
            server.stop(0)
            Downloads.resolve = { Yt.remoteAudioSource(it) }
            Downloads.chunkBytes = 4L shl 20
            dir.deleteRecursively()
        }
    }

    @Test
    fun sharedSongsRoundTripAndOnlyExactSongLinksBecomeCards() {
        val song = Song("Title — with dash", "Some Artist", "https://www.youtube.com/watch?v=abc_DEF-123&list=RD1")
        val parsed = SongShare.parse(SongShare.text(song)!!) as Song
        assertEquals("Title — with dash", parsed.title)
        assertEquals("Some Artist", parsed.artist)
        assertEquals("https://music.youtube.com/watch?v=abc_DEF-123", parsed.url)
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", parsed.artwork)

        val album = SongShare.parse(SongShare.text(Playlist("An album", "Artist", "MPREb_abc123"))!!) as Playlist
        assertEquals("An album" to "MPREb_abc123", album.title to album.browseId)

        assertNull(SongShare.parse("hello"))
        assertNull(SongShare.parse("🎵 Song — Artist\nhttps://evil.example/watch?v=abc_DEF-123"))
        assertNull(SongShare.parse("🎵 Song — Artist\nhttps://music.youtube.com/watch?v=abc_DEF-123\nextra line"))
        assertNull(SongShare.parse("check this https://music.youtube.com/watch?v=abc_DEF-123"))
        assertNull(SongShare.songUrl("https://music.youtube.com.evil.example/watch?v=abc_DEF-123"))
        assertNull(SongShare.text(Song("Local", "Artist", "file:///C:/secret.mp3")))
    }

    @Test
    fun statsCountPlaysPerSongAndArtistInsideThePeriod() {
        fun entry(id: Int, title: String, artist: String, at: Long) =
            ListeningHistoryEntry("e$id", Song(title, artist, "https://music.youtube.com/watch?v=$title"), at, 0, "PC")
        val history = listOf(entry(1, "aaaaaa", "One", 900), entry(2, "bbbbbb", "Two", 800), entry(3, "aaaaaa", "One", 700),
            entry(4, "cccccc", "One", 600), entry(5, "bbbbbb", "Two", 100))
        val all = listeningStats(history)
        assertEquals(5, all.plays)
        assertEquals(listOf("aaaaaa" to 2, "bbbbbb" to 2, "cccccc" to 1), all.songs.map { it.first.title to it.second })
        assertEquals(listOf("One" to 3, "Two" to 2), all.artists)
        val recent = listeningStats(history, since = 600, top = 1)
        assertEquals(4, recent.plays)
        assertEquals(listOf("One" to 3), recent.artists)
        assertEquals(0, listeningStats(emptyList()).plays)
    }

    @Suppress("FunctionName")
    private interface Keyboard : com.sun.jna.win32.StdCallLibrary {
        fun keybd_event(key: Byte, scan: Byte, flags: Int, extra: com.sun.jna.Pointer?)
    }

    /** Real hot-key registration and message reading, driven by F24 so no actual media key is pressed. */
    @Test
    fun globalHotKeyReachesThePlayerOnlyWhileASongIsLoaded() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val f24 = 0x87
        val toggles = java.util.concurrent.atomic.AtomicInteger()
        val loaded = java.util.concurrent.atomic.AtomicReference(NowPlaying())
        val player = object : MusicPlayer {
            override val now get() = loaded.get()
            override fun positionMs() = 0L
            override fun play(songs: List<Song>, index: Int, positionMs: Long) {}
            override fun toggle() { toggles.incrementAndGet() }
            override fun next() {}
            override fun previous() {}
        }
        val keyboard = com.sun.jna.Native.load("user32", Keyboard::class.java)
        fun press() { keyboard.keybd_event(f24.toByte(), 0, 0, null); keyboard.keybd_event(f24.toByte(), 0, 2, null) }
        WindowsMediaKeys(player, mapOf(WindowsMediaKeys.PLAY_PAUSE to f24)).use {
            Thread.sleep(300)
            press()
            Thread.sleep(300)
            assertEquals("nothing is claimed while no song is loaded", 0, toggles.get())
            loaded.set(NowPlaying(listOf(Song("Song", "Artist", "url")), 0))
            Thread.sleep(300)
            press()
            waitFor("hot key toggles playback") { toggles.get() == 1 }
        }
    }

    @Test
    fun volumeIsRememberedAndLevellingKeepsPlaybackWorking() {
        assumeTrue("Bundled mpv required", File("resources/windows/mpv.exe").isFile)
        val dir = Files.createTempDirectory("troit-volume").toFile()
        val wave = silence(File(dir, "silence.wav"), 1.0)
        val dependencies = MpvDependencies(audioSource = { AudioSource(wave.absolutePath, AudioQuality("PCM", 256)) })
        val songs = listOf(Song("First", "Artist", "first"), Song("Second", "Artist", "second"))
        try {
            // The bundled build must accept the exact filter the app sets, or levelling would silently do nothing.
            val check = ProcessBuilder(locateMpv(), "--no-config", "--no-video", "--ao=null", "--af=$NORMALIZE_FILTER", wave.absolutePath)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(check.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, check.exitValue())

            MpvPlayer(listOf("--ao=null"), dir, dependencies).use { player ->
                assertEquals(1f, player.volume)
                player.setVolume(0.4f)
                player.setTransitionSettings(TransitionSettings(gapless = false, normalize = true))
                player.play(songs, 0)
                waitFor("both songs finish with levelling on") { player.now.index == 1 && !player.now.playing && !player.now.loading }
                assertNull(player.now.error)
                assertEquals(0.4f, player.volume)
            }
            MpvPlayer(listOf("--ao=null"), dir, dependencies).use { restarted ->
                assertEquals(0.4f, restarted.volume)
                assertTrue(restarted.transitionSettings.normalize)
            }
        } finally { dir.deleteRecursively() }
    }
}
