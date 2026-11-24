package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
class PlaybackModeRenderTest {
    @Test
    fun playerAndModeMenuFitSmallPhonesAndDesktop() = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        for (width in listOf(320, 1100)) {
            var selectedMode by mutableStateOf(PlaybackMode.STOP)
            val player = object : MusicPlayer {
                override val playbackMode get() = selectedMode
                override fun setPlaybackMode(mode: PlaybackMode) { selectedMode = mode }
                override val now = NowPlaying(listOf(Song("A quieter kind of night", "Evening soundtrack", "preview", durationSeconds = 240)), 0, playing = true)
                override fun positionMs() = 60_000L
                override fun play(songs: List<Song>, index: Int, positionMs: Long) = Unit
                override fun toggle() = Unit
                override fun next() = Unit
                override fun previous() = Unit
            }
            var clock = 0L
            val scene = ImageComposeScene(width, 760, density = Density(1f, if (width == 320) 1.25f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) {
                    Column {
                        PlaybackModeSelector(player, expanded = true)
                        Spacer(Modifier.weight(1f))
                        PlayerBar(player)
                    }
                } }
            }
            suspend fun settle() { repeat(8) { scene.render(clock).close(); clock += 50_000_000L; delay(20) } }
            fun save(name: String) { scene.render(clock).use { frame ->
                File(output, "$name-$width.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
            } }
            try {
                for (mode in PlaybackMode.entries) {
                    selectedMode = mode
                    settle()
                    save("playback-${mode.name.lowercase()}")
                }
                scene.sendPointerEvent(PointerEventType.Press, Offset(90f, 24f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(90f, 24f))
                settle()
                save("playback-mode-menu")
                assertEquals(PlaybackMode.RADIO, player.playbackMode)
                // Choose the first visible menu option with real pointer input.
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 82f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 82f))
                settle()
                assertEquals("Selecting Stop at end updates the player", PlaybackMode.STOP, player.playbackMode)
            } finally { scene.close() }
        }
    }
}
