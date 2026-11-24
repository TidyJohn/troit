package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class TvNavigationTest {
    private class Player : MusicPlayer {
        override var now by mutableStateOf(NowPlaying())
        var plays = 0
        var toggles = 0
        var skips = 0
        private var mode by mutableStateOf(PlaybackMode.STOP)
        override val playbackMode get() = mode
        override fun setPlaybackMode(mode: PlaybackMode) { this.mode = mode }
        override fun positionMs() = 30_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) { plays++; now = NowPlaying(songs, index, true) }
        override fun toggle() { toggles++; now = now.copy(playing = !now.playing) }
        override fun next() { skips++; now = now.copy(index = (now.index + 1).coerceAtMost(now.queue.lastIndex)) }
        override fun previous() { now = now.copy(index = (now.index - 1).coerceAtLeast(0)) }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
    private fun ImageComposeScene.text(value: String) = nodes().first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun ImageComposeScene.select(label: String) {
        assertTrue("Can select $label", text(label).button().config[SemanticsActions.OnClick].action?.invoke() == true)
    }
    private fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }
    private fun ImageComposeScene.focused(label: String): Boolean = nodes().filter { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == label }
    }.any { node -> node.button().config.let { SemanticsProperties.Focused in it && it[SemanticsProperties.Focused] } }

    @Test
    fun remoteArrowsOpenLibraryAndEnterPlaysSongsAndControlsPlayback() = runBlocking(Dispatchers.Main) {
        val dir = Files.createTempDirectory("troit-tv-navigation").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val songs = List(16) { Song("Evening song ${it + 1}", "The Evening Orchestra", "https://example.invalid/song-$it", durationSeconds = 240) }
        val library = Library(File(dir, "library.json")).apply { add(SavedPlaylist("Living room favorites", songs)) }
        val sync = Sync(dir, "Android TV", library, scope)
        val player = Player()
        var back: (() -> Unit)? = null
        var devices = 0
        val scene = ImageComposeScene(960, 540, Density(1f), coroutineContext = coroutineContext) {
            TroitTheme { Surface(Modifier.fillMaxSize()) {
                TvAppContent(player, dir, "Android TV", library, sync, { devices++ },
                    backHandler = { back = it }, homeSource = { listOf(Section("Made for this evening", songs)) })
            } }
        }
        var time = 0L
        suspend fun settle() { repeat(5) { scene.render(time).close(); time += 50_000_000L; delay(15) } }
        fun save(name: String) {
            val output = File("build/ui-previews").apply { mkdirs() }
            scene.render(time).use { frame -> File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
        }
        try {
            settle()
            assertTrue("TV opens with a focused destination", scene.focused("Discover"))
            save("tv-discover")
            scene.select("Now playing"); settle()
            scene.text("Nothing playing")
            scene.select("Browse music"); settle()
            assertTrue("The empty player returns to a focused music destination", scene.focused("Discover"))
            scene.key(Key.DirectionDown); settle()
            save("tv-search-focused")
            assertTrue("D-pad Down reaches Search", scene.focused("Search"))
            scene.key(Key.Enter); settle()
            scene.text("Search music")
            scene.key(Key.DirectionDown); settle()
            assertTrue("D-pad Down reaches Library", scene.focused("Library"))
            scene.key(Key.Enter); settle()
            scene.text("Living room favorites")
            scene.key(Key.DirectionRight); settle()
            scene.key(Key.Enter); settle()
            scene.text("Play all")
            assertTrue("Opening a collection focuses its Back control", scene.focused("Back"))
            save("tv-playlist")
            scene.key(Key.DirectionDown); settle()
            assertTrue("Down reaches the collection's Play all control", scene.focused("Play all"))
            scene.key(Key.Enter); settle()
            assertEquals(1, player.plays)
            assertEquals(songs, player.now.queue)
            assertNotNull(back)
            back?.invoke(); settle()
            scene.text("Living room favorites")
            assertTrue("Back restores the Library destination focus", scene.focused("Library"))
            scene.key(Key.DirectionDown); settle()
            assertTrue("Down reaches Now playing", scene.focused("Now playing"))
            scene.key(Key.DirectionDown); settle()
            assertTrue("The last destination offers an arrow path into the footer", scene.focused("Devices"))
            scene.key(Key.DirectionLeft); settle()
            assertTrue("Left reaches Next in the footer", scene.focused("Next"))
            scene.key(Key.Enter); settle()
            assertEquals(1, player.skips)
            assertEquals(songs[1], player.now.song)
            scene.key(Key.DirectionLeft); settle()
            assertTrue("Left reaches Pause in the footer", scene.focused("Pause"))
            scene.key(Key.Enter); settle()
            assertEquals(1, player.toggles)
            assertFalse(player.now.playing)
            save("tv-paused")
            for (label in listOf("Previous", "Play", "Next")) {
                val bounds = scene.text(label).button().boundsInRoot
                assertTrue("TV $label stays visible: $bounds", bounds.left >= 0 && bounds.right <= 960 && bounds.top >= 0 && bounds.bottom <= 540)
                assertTrue("TV $label has a large focus target", bounds.height >= 54)
            }
            scene.select("Now playing"); settle()
            scene.select("Playback mode"); settle()
            scene.select("Repeat playlist"); settle()
            assertEquals(PlaybackMode.REPEAT_ALL, player.playbackMode)
            assertEquals("Changing a mode preserves the playing queue", songs, player.now.queue)
            Unit
        } finally { scene.close(); scope.cancel() }
    }
}
