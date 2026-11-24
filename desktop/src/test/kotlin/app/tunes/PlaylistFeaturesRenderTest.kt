package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalComposeUiApi::class)
class PlaylistFeaturesRenderTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(value: String) = first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }

    @Test
    fun searchingAThousandSongsPlaysTheOriginalQueueAndSpotifySetupFitsPhones() = runBlocking(Dispatchers.Main) {
        val directory = Files.createTempDirectory("troit-playlist-features").toFile()
        val output = File("build/ui-previews").apply { mkdirs() }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val library = Library(File(directory, "library.json"))
        val playlist = SavedPlaylist("A thousand favorites", List(1_000) { Song("Track $it", "Artist ${it % 17}", "preview-$it") })
        library.add(playlist)
        var selection: Pair<List<Song>, Int>? = null
        var frame = 0L
        suspend fun ImageComposeScene.settle() { repeat(6) { render(frame).close(); frame += 100_000_000L; delay(20) } }
        fun ImageComposeScene.save(name: String) { render(frame).use { image ->
            File(output, "$name.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        } }
        try {
            for (width in listOf(320, 1100)) {
                val scene = ImageComposeScene(width, 900, density = Density(1f, if (width == 320) 1.2f else 1f), coroutineContext = coroutineContext) {
                    TroitTheme { Surface(Modifier.fillMaxSize()) { PlaylistScreen(playlist, library, { songs, index -> selection = songs to index }, {}) } }
                }
                try {
                    scene.settle()
                    val field = scene.nodes().first { SemanticsActions.SetText in it.config }
                    assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Track 999")))
                    scene.settle()
                    scene.save("playlist-search-$width")
                    scene.nodes().text("1 of 1000 songs")
                    scene.nodes().text("Track 999").button().config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(999, selection!!.second)
                    assertEquals(1_000, selection!!.first.size)
                    assertEquals(1_000, library.playlists.single().songs.size)
                } finally { scene.close() }

                val opened = mutableListOf<String>()
                val connection = SpotifyConnection(SpotifyAccount(directory), scope)
                val setup = ImageComposeScene(width, 900, density = Density(1f, if (width == 320) 1.2f else 1f), coroutineContext = coroutineContext) {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                        TroitTheme { Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { SpotifySetupCard(connection) }
                        } }
                    }
                }
                try {
                    setup.settle()
                    setup.nodes().text("Set up Spotify").button().config[SemanticsActions.OnClick].action!!.invoke()
                    setup.settle()
                    setup.save("spotify-setup-$width")
                    setup.nodes().text(SpotifyOAuthLoopback.REDIRECT_URI)
                    setup.nodes().text("Open developer dashboard").button().config[SemanticsActions.OnClick].action!!.invoke()
                    assertEquals(listOf("https://developer.spotify.com/dashboard"), opened)
                } finally { setup.close() }
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
