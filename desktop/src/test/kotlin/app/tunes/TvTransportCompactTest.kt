package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Real TV footer bounds, D-pad actions and expiry, without a live backend or native audio. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class TvTransportCompactTest {
    private val song = Song("After the last train — an evening acoustic session", "The Evening Orchestra", "https://example.invalid/tv-footer", durationSeconds = 240)
    private class Player(song: Song) : MusicPlayer {
        override var now by mutableStateOf(NowPlaying(listOf(song), 0, false))
        var toggles = 0
        var nexts = 0
        var previouses = 0
        override fun positionMs() = 30_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) { now = NowPlaying(songs, index, true) }
        override fun toggle() { toggles++; now = now.copy(playing = !now.playing) }
        override fun next() { nexts++ }
        override fun previous() { previouses++ }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
    private fun ImageComposeScene.text(value: String) = nodes().first { node -> SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value } }
    private fun ImageComposeScene.description(value: String) = nodes().first { node -> SemanticsProperties.ContentDescription in node.config && value in node.config[SemanticsProperties.ContentDescription] }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun ImageComposeScene.key(key: Key) { sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)) }
    private fun SemanticsNode.focused() = SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]
    private fun save(scene: ImageComposeScene, name: String) {
        val output = File("build/ui-previews").apply { mkdirs() }
        scene.render(2_000_000_000L).use { frame -> File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
    }

    @Test fun compactFooterKeepsLargeTargetsAndKeyboardControlsAtTvSizes() = runBlocking(Dispatchers.Main) {
        for ((width, fontScale) in listOf(960 to 1f, 960 to 1.3f, 1280 to 1f)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dir = Files.createTempDirectory("troit-compact-tv").toFile()
            val sync = Sync(dir, "TV", Library(File(dir, "library.json")), scope)
            val library = ListeningLibraryState(sync, dir, scope)
            val player = Player(song)
            val devicesFocus = FocusRequester()
            var devices = 0
            val scene = ImageComposeScene(width, 120, Density(1f, fontScale), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalListeningLibrary provides library) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            TvTransport(player, { devices++ }, devicesFocus = devicesFocus)
                        }
                        LaunchedEffect(Unit) { devicesFocus.requestFocus() }
                    }
                } }
            }
            var frameTime = 0L
            suspend fun settle() { repeat(5) { scene.render(frameTime).close(); frameTime += 50_000_000L; delay(15) } }
            try {
                settle()
                val controls = listOf("Previous", "Play", "Next", "Devices").map { scene.text(it).button() } + scene.description("Like ${song.title}").button()
                val title = scene.text(song.title)
                assertTrue("The compact footer retains readable song space", title.boundsInRoot.width >= 150)
                val top = controls.minOf { it.boundsInRoot.top }
                assertTrue("The footer including progress takes less than 84dp", 120 - top <= 84)
                for (control in controls) {
                    val bounds = control.boundsInRoot
                    assertTrue("Large TV focus target: $bounds", bounds.height >= 56 && bounds.width >= 48)
                    assertTrue("Footer control is entirely visible: $bounds", bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 120)
                }
                assertTrue(scene.text("Devices").button().focused())
                scene.key(Key.DirectionLeft); settle(); assertTrue(scene.text("Next").button().focused())
                scene.key(Key.Enter); settle(); assertEquals(1, player.nexts)
                scene.key(Key.DirectionLeft); settle(); assertTrue(scene.text("Play").button().focused())
                scene.key(Key.Enter); settle(); assertEquals(1, player.toggles)
                scene.key(Key.DirectionLeft); settle(); assertTrue(scene.text("Previous").button().focused())
                scene.key(Key.Enter); settle(); assertEquals(1, player.previouses)
                scene.key(Key.DirectionLeft); settle(); assertTrue(scene.description("Like ${song.title}").button().focused())
                scene.key(Key.Enter); settle(); assertTrue(library.isLiked(song))
                save(scene, "compact-tv-footer-$width-${(fontScale * 100).toInt()}")
                repeat(4) { scene.key(Key.DirectionRight); settle() }
                assertTrue("Right returns to the always-available Devices recovery action", scene.text("Devices").button().focused())
                scene.key(Key.Enter); settle(); assertEquals(1, devices)
            } finally { scene.close(); scope.cancel() }
        }
        Unit
    }

    @Test fun pausedRemoteControlsExpireEvenWhenLocalPlaybackAndSnapshotStayUnchanged() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val dir = Files.createTempDirectory("troit-tv-expiry").toFile()
        val sync = Sync(dir, "TV", Library(File(dir, "library.json")), scope)
        val owner = "00000000-0000-0000-0000-000000000001"
        val start = Instant.now()
        val snapshot = ConnectSnapshot("00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003",
            "Bedroom phone", song, false, 30_000, 1, start.toString(), start.plusSeconds(2).toString(), true)
        var requests = 0
        val client = ConnectClient(sync, { _, _ -> requests++; snapshot.toJson() }, { owner })
        val remote = PlaybackRemoteState(sync, client, { owner })
        remote.refresh()
        val scene = ImageComposeScene(960, 120, Density(1f), coroutineContext = coroutineContext) {
            TroitTheme { Surface(Modifier.fillMaxSize()) { TvTransport(null, {}, remote, sync.deviceId) } }
        }
        var frameTime = 0L
        suspend fun settle() { repeat(5) { scene.render(frameTime).close(); frameTime += 50_000_000L; delay(15) } }
        try {
            settle()
            assertFalse(SemanticsProperties.Disabled in scene.text("Play").button().config)
            delay(2_250)
            settle()
            for (label in listOf("Previous", "Play", "Next")) assertTrue("Expired $label is disabled without another poll", SemanticsProperties.Disabled in scene.text(label).button().config)
            assertFalse(SemanticsProperties.Disabled in scene.text("Devices").button().config)
            scene.text("Bedroom phone · Offline · ${song.artist}")
            assertEquals("No polling is needed to expire an unchanged paused snapshot", 1, requests)
            save(scene, "compact-tv-footer-offline")
        } finally { scene.close(); scope.cancel() }
        Unit
    }
}
