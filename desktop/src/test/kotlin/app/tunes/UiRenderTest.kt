package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Offscreen layout checks: render the real composables without opening or controlling a desktop window. */
@OptIn(ExperimentalComposeUiApi::class)
class UiRenderTest {
    private val sampleSongs = listOf(
        Song("A quieter kind of night", "Your evening soundtrack", "preview-1", durationSeconds = 246),
        Song("After the last train", "Night drive collection", "preview-2", durationSeconds = 218),
        Song("Somewhere, the sun is up", "Sunday listening", "preview-3", durationSeconds = 192),
    )
    private val player = object : MusicPlayer {
        override val now = NowPlaying(sampleSongs, 0, playing = false)
        override fun positionMs() = 68_000L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) = Unit
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
    }

    private fun render(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = coroutineContext, content = content)
        try {
            repeat(8) { scene.render(it * 100_000_000L).close(); delay(30) }
            scene.render(1_000_000_000L).use { frame ->
                File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
            }
        } finally { scene.close() }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.described(label: String) = semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        .filter { SemanticsProperties.ContentDescription in it.config && label in it.config[SemanticsProperties.ContentDescription] }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        val point = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point)
        sendPointerEvent(PointerEventType.Release, point)
    }

    @Test
    fun discoverSearchAndPlayerShortcutsOpenTheirNamedDestinations() = runBlocking(Dispatchers.Main) {
        val dataDir = Files.createTempDirectory("troit-navigation-preview").toFile()
        val scene = ImageComposeScene(360, 740, Density(1f), coroutineContext = coroutineContext) {
            App(player, dataDir, "Preview") {}
        }
        suspend fun settle() = repeat(6) { scene.render(it * 100_000_000L).close(); delay(20) }
        fun nodes() = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        fun named(label: String) = nodes().first { node ->
            SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == label }
        }
        try {
            settle()
            scene.click(named("Search songs, artists, or albums"))
            settle()
            assertTrue("Discover opens the search field ready for typing", nodes().any {
                SemanticsProperties.EditableText in it.config && SemanticsProperties.Focused in it.config && it.config[SemanticsProperties.Focused]
            })
            scene.click(scene.described("Open play queue").single())
            settle()
            scene.click(named("Player"))
            settle()
            assertTrue(scene.described("Previous track or restart").isNotEmpty())
            scene.click(named("Queue (3)"))
            settle()
            assertTrue(named("Track 1 of 3").boundsInRoot.height > 0)
            scene.click(scene.described("Close now playing").single())
            settle()
            scene.click(scene.described("Devices and playback").single())
            settle()
            assertTrue(named("Devices & playback").boundsInRoot.height > 0)
        } finally { scene.close() }
    }

    /** The music video is a panel beside the content (a strip above the player on a phone), never a dialog over it. */
    @Test
    fun videoPanelOpensBesideTheContentAndTheSameButtonHidesIt() {
        val dataDir = Files.createTempDirectory("troit-video-preview").toFile()
        for ((name, width, height) in listOf(Triple("desktop", 1100, 760), Triple("phone", 390, 844))) runBlocking(Dispatchers.Main) {
            val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = coroutineContext) {
                App(player, dataDir, "Preview", videoSurface = { song, modifier -> VideoPlaceholder(song, "Finding the video…", modifier) }) {}
            }
            suspend fun settle() = repeat(8) { scene.render(it * 100_000_000L).close(); delay(30) }
            try {
                settle()
                assertEquals("nothing is shown until it is asked for", 0, scene.described("Hide music video").size)
                scene.click(scene.described("Show music video").single())
                settle()
                val hide = scene.described("Hide music video")
                assertEquals("the panel and the player bar both offer to close the video", 2, hide.size)
                val panel = hide.minBy { it.boundsInRoot.top }
                if (name == "desktop") assertTrue("on a wide window the panel is at the right edge", panel.boundsInRoot.left > width - 320)
                assertEquals("a video opens inline", 0, scene.described("Close now playing").size)
                scene.render(1_000_000_000L).use { frame ->
                    File("build/ui-previews").apply { mkdirs() }.resolve("$name-video-panel.png")
                        .writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
                }
                scene.click(panel)
                settle()
                assertEquals("closing the panel hides it everywhere", 0, scene.described("Hide music video").size)
                assertEquals("and the button is back to its resting state", 1, scene.described("Show music video").size)
            } finally { scene.close() }
        }
    }

    @Test
    fun desktopAndPhoneLayoutsRenderWithoutOpeningWindows() {
        val dataDir = Files.createTempDirectory("troit-ui-preview").toFile()
        render("desktop-home", 1100, 760) { App(player, dataDir, "Preview") {} }
        render("phone-home", 390, 844) { App(player, dataDir, "Preview") {} }
        val preferences = RegionPreferences(File(dataDir, "preferences.json")).apply { setIndianMusicExcluded(true) }
        render("desktop-preferences", 870, 1100) {
            TroitTheme { CompositionLocalProvider(LocalListeningPreferences provides preferences.settings) {
                Surface(Modifier.fillMaxSize()) { TasteScreen(preferences) }
            } }
        }
        render("phone-preferences", 390, 844) {
            TroitTheme { CompositionLocalProvider(LocalListeningPreferences provides preferences.settings) {
                Surface(Modifier.fillMaxSize()) { TasteScreen(preferences) }
            } }
        }
        render("desktop-discovery", 870, 1000) {
            val scope = rememberCoroutineScope()
            val home = remember { Loader(scope) { listOf(Section("For your late nights", sampleSongs), Section("A different kind of discovery", sampleSongs.reversed())) } }
            TroitTheme { Surface(Modifier.fillMaxSize()) {
                HomeScreen(home, rememberLazyListState(), { _, _ -> }, {}) {}
            } }
        }
        val library = Library(File(dataDir, "collection-fixture.json")).apply {
            add(SavedPlaylist("Late night favorites", sampleSongs))
            add(SavedPlaylist("A very long playlist title to test wrapping", sampleSongs))
            add(SavedPlaylist("Fresh finds", emptyList()))
        }
        render("phone-library", 360, 740) {
            val scope = rememberCoroutineScope()
            val importing = remember { ImportState(scope, library) }
            val sync = remember { Sync(dataDir, "Preview", library, scope) }
            TroitTheme { Surface(Modifier.fillMaxSize()) { CollectionScreen(library, importing, sync) {} } }
        }
        render("phone-playlist", 360, 740) {
            TroitTheme { Surface(Modifier.fillMaxSize()) { PlaylistScreen(library.playlists.first { it.songs.isNotEmpty() }, library, { _, _ -> }, {}) } }
        }
        render("phone-search", 360, 740) {
            val scope = rememberCoroutineScope()
            val search = remember { SearchState(scope, { _, _, _ -> MusicSearch.Page(sampleSongs) }, { emptyList() }).apply { query = "night"; result = Result.success(sampleSongs) } }
            TroitTheme { Surface(Modifier.fillMaxSize()) { SearchScreen(search, { _, _ -> }, {}) } }
        }
    }
}
