package app.tunes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs

/** The silent picture beside the music, with a generated test video: no catalogue or streaming dependency. */
class InlineVideoTest {
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition()) {
            assertTrue("Timed out: $what", System.currentTimeMillis() < deadline)
            Thread.sleep(40)
        }
    }

    private fun requireMpv() = assumeTrue("Bundled mpv required", File("resources/windows/mpv.exe").isFile)

    /** A 25 fps video whose every frame is a flat grey equal to its own frame number, so a frame says when it is from. */
    private val numberedFrames = "av://lavfi:nullsrc=size=64x36:rate=25:duration=40,format=gray,geq=lum='mod(N,256)'"

    @Test
    fun framesAreReleasedWhenTheSoundReachesThemAndFollowPauseAndSeek() = runBlocking {
        requireMpv()
        val started = System.nanoTime()
        val offset = AtomicLong(2_000) // the "song" is already two seconds in when the picture is switched on
        val playing = AtomicBoolean(true)
        val paused = AtomicLong(0)
        fun clock() = if (playing.get()) offset.get() + (System.nanoTime() - started) / 1_000_000 else paused.get()
        val shown = CopyOnWriteArrayList<Triple<Long, Long, Int>>() // sound position, frame time, frame number read from its pixels
        val job = launch(Dispatchers.Default) {
            playVideoFrames(numberedFrames, 40_000, ::clock, playing::get) { frame, pts ->
                shown += Triple(clock(), pts, frame[frame.size / 2 + 1].toInt() and 255)
            }
        }
        try {
            waitFor("frames arrive") { shown.size >= 40 }
            val steady = shown.drop(10).take(30)
            assertTrue("each frame is shown within a tenth of a second of its moment: $steady", steady.all { (sound, pts, _) -> sound - pts in 0..100 })
            // Full-range grey G decodes from luma Y as about (Y - 16) * 1.164; frame n is expected at n / 25 s.
            steady.forEach { (_, pts, grey) ->
                val expected = ((pts * 25 / 1000 % 256 - 16) * 1.164).coerceIn(0.0, 255.0)
                assertTrue("the frame shown at $pts ms is the one from that moment (grey $grey, expected $expected)", abs(grey - expected) <= 5)
            }

            paused.set(clock()); playing.set(false)
            Thread.sleep(300)
            val whilePaused = shown.size
            Thread.sleep(500)
            assertEquals("the picture holds while the music is paused", whilePaused, shown.size)

            offset.addAndGet(20_000 - (System.nanoTime() - started) / 1_000_000) // resume from a seek to 0:22
            playing.set(true)
            waitFor("the picture follows a seek") { shown.last().second in 21_500..26_000 }
            val after = shown.last()
            assertTrue("and is in step there too: $after", after.first - after.second in 0..150)
        } finally { job.cancelAndJoin() }
        assertTrue("stopping leaves no player behind", ProcessHandle.current().children().noneMatch { it.isAlive && it.info().command().orElse("").endsWith("mpv.exe") })
    }

    @Test
    fun pictureSyncHoldsWhenAheadSpeedsUpWhenBehindAndJumpsWhenFarOff() {
        assertEquals(1f, pictureSyncSpeed(0))
        assertEquals(1f, pictureSyncSpeed(-40))
        assertEquals("a little behind: catch up gently", 1.05f, pictureSyncSpeed(-200))
        assertEquals("ahead: wait for the sound", 0f, pictureSyncSpeed(PICTURE_JUMP_LEAD_MS))
        assertNull("far behind: jump", pictureSyncSpeed(-501))
        assertNull("far ahead, after the listener seeks back: jump", pictureSyncSpeed(2_001))
    }

    /** Opens a real window for a few seconds with the real side panel beside real audio playback. */
    @Test
    fun pictureAppearsBesideTheMusicWithoutInterruptingIt() {
        requireMpv()
        assumeTrue(!GraphicsEnvironment.isHeadless() && System.getProperty("os.name").startsWith("Windows"))
        val dir = Files.createTempDirectory("troit-video").toFile()
        val wave = File(dir, "silence.wav").apply {
            val samples = 12 * 16_000
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2).array()
            writeBytes(header + ByteArray(samples * 2))
        }
        val song = Song("First", "Artist", "first", durationSeconds = 12)
        val audioLoads = AtomicLong()
        val player = MpvPlayer(listOf("--ao=null"), dir, MpvDependencies(audioSource = {
            audioLoads.incrementAndGet(); AudioSource(wave.absolutePath, AudioQuality("PCM", 256))
        }))
        val open = mutableStateOf(false)
        var picture: Rect? = null
        lateinit var window: ComposeWindow
        /** Colour bars are vivid; the placeholder and an empty panel are greys. */
        fun pictureIsShowing(): Boolean {
            val bounds = picture ?: return false
            val scale = window.graphicsConfiguration.defaultTransform.scaleX
            val origin = window.contentPane.locationOnScreen
            val pixel = Robot().getPixelColor(origin.x + ((bounds.left + bounds.width * .62f) / scale).toInt(),
                origin.y + ((bounds.top + bounds.height * .3f) / scale).toInt())
            return maxOf(pixel.red, pixel.green, pixel.blue) - minOf(pixel.red, pixel.green, pixel.blue) > 120
        }
        try {
            player.play(listOf(song), 0)
            waitFor("music plays") { player.now.playing && !player.now.loading && player.positionMs() > 300 }
            SwingUtilities.invokeAndWait {
                window = ComposeWindow().apply {
                    setSize(900, 520)
                    setLocationRelativeTo(null)
                    isAlwaysOnTop = true
                    setContent {
                        TroitTheme {
                            CompositionLocalProvider(LocalVideoOpen provides open, LocalVideoSurface provides { playing, modifier ->
                                MpvSyncedVideo(player, playing, modifier.onGloballyPositioned { picture = it.boundsInWindow() }) { _, _ ->
                                    VideoSource("Test bars", "av://lavfi:testsrc=size=640x360:rate=30:duration=12", null, 12)
                                }
                            }) {
                                Surface(Modifier.fillMaxSize()) {
                                    Box(Modifier.fillMaxSize()) { if (open.value) VideoPanel(song, compact = false) }
                                }
                            }
                        }
                    }
                    isVisible = true
                }
            }
            val before = player.positionMs()
            assertFalse(pictureIsShowing())
            open.value = true
            waitFor("the picture is drawn in the side panel") { pictureIsShowing() }
            assertTrue("the music kept playing while the picture started", player.now.playing && !player.now.loading && player.positionMs() > before)
            assertEquals("and its audio was never loaded again", 1, audioLoads.get())
            System.getProperty("troit.screenshot")?.let { path ->
                Thread.sleep(400)
                ImageIO.write(Robot().createScreenCapture(window.bounds), "png", File(path))
            }
            val hidden = player.positionMs()
            open.value = false
            waitFor("music continues after the picture is hidden") { player.positionMs() > hidden + 400 }
            assertNull(player.now.error)
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
            player.close()
            dir.deleteRecursively()
        }
    }
}
