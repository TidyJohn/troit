package app.tunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Uses the public catalogue, like PlaylistRadioLiveTest: a real music video keeps up with a running clock. */
class MusicVideoLiveTest {
    @Test
    fun aRealVideoIsFoundAndItsFramesKeepUpWithTheSound() = runBlocking {
        assumeTrue("Bundled mpv required", File("resources/windows/mpv.exe").isFile)
        val song = Song("Never Gonna Give You Up", "Rick Astley", "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        val source = Yt.videoSource(song, maxHeight = 480, targetSeconds = 213)
        assertTrue("picked a video of the song's own length, not a clip: ${source.durationSeconds}s", source.durationSeconds in 205..220)

        val started = System.nanoTime()
        fun clock() = 30_000 + (System.nanoTime() - started) / 1_000_000 // the song is half a minute in
        val shown = CopyOnWriteArrayList<Pair<Long, Long>>()
        val job = launch(Dispatchers.Default) {
            playVideoFrames(source.videoUrl, source.durationSeconds * 1000, ::clock, { true }) { _, pts -> shown += clock() to pts }
        }
        try {
            val deadline = System.currentTimeMillis() + 60_000
            while (shown.size < 100) {
                assertTrue("only ${shown.size} frames arrived in a minute", System.currentTimeMillis() < deadline)
                Thread.sleep(100)
            }
            val steady = shown.takeLast(50)
            val late = steady.count { (sound, pts) -> sound - pts > 100 }
            assertTrue("frames arrive on time once it is running: $late of 50 were late", late <= 5)
            assertTrue("and at the video's own pace", steady.last().second - steady.first().second in 1_500..2_600)
        } finally { job.cancelAndJoin() }
    }
}
