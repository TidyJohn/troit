package app.tunes

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** Real silent native playback proves queue edits keep the current decoder and seek position. */
class NativeQueueEditTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition()) { assertTrue("Native player did not settle", System.currentTimeMillis() < deadline); Thread.sleep(25) }
    }

    @Test
    fun upcomingEditsKeepPausedAudioPositionAndRejectStaleQueues() = runBlocking {
        assumeTrue(File("resources/windows/mpv.exe").isFile)
        val dir = Files.createTempDirectory("troit-native-queue").toFile()
        val loaded = CopyOnWriteArrayList<String>()
        val samples = 20 * 16_000
        val wave = File(dir, "silence.wav").apply {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
            writeBytes(header + ByteArray(samples * 2))
        }
        val songs = List(3) { Song("Song $it", "Artist", "native-$it", durationSeconds = 20) }
        val player = MpvPlayer(listOf("--ao=null"), dir, MpvDependencies(
            audioSource = { url -> loaded += url; AudioSource(wave.absolutePath, AudioQuality("PCM", 256)) }))
        player.setTransitionSettings(TransitionSettings(gapless = false))
        try {
            player.play(songs, 0)
            waitFor { player.now.playing && !player.now.loading }
            player.seekTo(4_000)
            waitFor { player.positionMs() >= 3_900 }
            player.toggle()
            waitFor { !player.now.playing }
            val position = player.positionMs()
            val edited = listOf(songs[0], songs[2], songs[1], songs[2])
            assertTrue(player.replaceQueue(songs, edited, 0, position, false, PlaybackMode.REPEAT_ALL))
            assertEquals(edited, player.now.queue)
            assertFalse(player.now.playing)
            assertEquals(position, player.positionMs())
            assertEquals(listOf("native-0"), loaded.toList())
            assertEquals(PlaybackMode.REPEAT_ALL, player.playbackMode)
            assertFalse(player.replaceQueue(songs, songs, 0, 0, true, PlaybackMode.STOP))
            assertFalse(player.replaceQueue(edited, songs, 0, 0, true, PlaybackMode.STOP) { false })
            assertFalse(player.replaceQueue(edited, edited.reversed(), 0, 0, true, PlaybackMode.STOP))
            assertEquals(edited, player.now.queue)
            assertEquals(listOf("native-0"), loaded.toList())
            val intent = player.interactionRevision
            val transferred = listOf(songs[2], songs[1])
            assertTrue(player.replaceQueue(null, transferred, 0, 5_000, false, PlaybackMode.REPEAT_ONE) {
                player.interactionRevision == intent
            })
            waitFor { !player.now.loading }
            assertFalse("Prepared transfers must remain silent before activation", player.now.playing)
            // mpv's paused audio clock includes output-buffer preroll (also with --ao=null).
            assertTrue("Prepared position=${player.positionMs()}, state=${player.now}", kotlin.math.abs(player.positionMs() - 5_000) <= 250)
            assertEquals(intent + 1, player.interactionRevision)
            assertEquals(listOf("native-0", "native-2"), loaded.toList())
            assertTrue(player.control(songs[2].url, 0, "play") { player.interactionRevision == intent + 1 })
            assertTrue("Native play state must be ready before the receipt", player.now.playing)
        } finally { player.close() }
        Unit
    }
}
