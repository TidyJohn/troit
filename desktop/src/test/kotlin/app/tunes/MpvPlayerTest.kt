package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Live, and silent (--ao=null). Skipped until mpv is available. */
class MpvPlayerTest {
    private fun mpvAvailable() = File("resources/windows/mpv.exe").isFile ||
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "mpv.exe").isFile }

    private fun waitFor(what: String, seconds: Int = 45, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            assertTrue("timed out waiting for: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(200)
        }
    }

    @Test
    fun playsPausesSkipsAndResumesAtAPosition() {
        assumeTrue("mpv not installed", mpvAvailable())
        val songs = Yt.search("lana del rey summertime sadness").filterIsInstance<Song>().take(2)
        assertEquals(2, songs.size)
        MpvPlayer(listOf("--ao=null")).use { player ->
            player.play(songs, 0)
            waitFor("audio to advance") { player.positionMs() > 2_000 }
            assertNotNull(player.now.song)
            assertNotNull("source quality is available after the stream resolves", player.now.audioQuality)
            assertTrue(player.now.playing)
            assertTrue("audio duration is available for seeking", player.durationMs() > 2_000)

            player.toggle()
            waitFor("pause") { !player.now.playing }
            val paused = player.positionMs()
            Thread.sleep(1_500)
            assertTrue("kept moving while paused", player.positionMs() - paused < 600)

            player.seekTo(20_000)
            waitFor("seek while paused") { player.positionMs() in 19_000..21_000 }
            assertTrue("seeking should preserve pause", !player.now.playing)

            player.toggle()
            waitFor("resume") { player.now.playing }

            player.next()
            waitFor("next song") { player.now.index == 1 && player.now.playing && !player.now.loading }

            player.play(songs, 0, positionMs = 60_000)
            waitFor("start position") { player.now.index == 0 && player.positionMs() in 60_000..90_000 && !player.now.loading }
        }
    }
}
