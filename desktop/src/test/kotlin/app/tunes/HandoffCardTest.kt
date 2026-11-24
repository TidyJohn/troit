package app.tunes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Measures the real title and action bounds; no service or account is involved. */
@OptIn(ExperimentalComposeUiApi::class)
class HandoffCardTest {
    private val song = Song(
        "After the Last Train — a very long live recording title with an extended outro and the complete acoustic session",
        "The Evening Orchestra with a Special Guest Vocalist",
        "https://example.invalid/fixture-track",
    )
    private val handoff = RemotePlayback(listOf(song), 0, 38_000, "fixture-pc", "PC", 1000)

    private class Player(override var now: NowPlaying = NowPlaying()) : MusicPlayer {
        var played: Triple<List<Song>, Int, Long>? = null
        override fun positionMs() = 0L
        override fun play(songs: List<Song>, index: Int, positionMs: Long) { played = Triple(songs, index, positionMs) }
        override fun toggle() = Unit
        override fun next() = Unit
        override fun previous() = Unit
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(value: String): SemanticsNode = first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }

    @Test
    fun fullRemoteTitleWrapsAboveActionsOnPhoneDesktopAndLargerFonts() = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        listOf(Triple("phone", 390, 1f), Triple("narrow-large-font", 320, 1.4f), Triple("desktop", 1000, 1f)).forEach { (name, width, fontScale) ->
            val scene = ImageComposeScene(width, 900, density = Density(1f, fontScale), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { PlayerBar(Player(), handoff) }
                } }
            }
            try {
                repeat(6) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes()
                val title = all.text(song.title)
                val artist = all.text(song.artist)
                val continueButton = all.text("Continue here").button()
                val dismissButton = all.text("Dismiss").button()
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                scene.render(1_000_000_000L).use { frame ->
                    File(output, "handoff-$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
                }
                val metrics = layouts.joinToString { layout ->
                    "size=${layout.size}, constraints=${layout.layoutInput.constraints}, widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, lines=${layout.lineCount}, lastEnd=${layout.getLineEnd(layout.lineCount - 1)}, textLength=${song.title.length}"
                }
                File(output, "handoff-$name-layout.txt").writeText(metrics)
                assertTrue("The full title must fit its text layout at $name: $metrics", layouts.none { it.hasVisualOverflow })
                assertEquals(song.title.length, layouts.single().getLineEnd(layouts.single().lineCount - 1))
                if (width < 500) assertTrue("Phone titles must wrap instead of disappearing behind actions", layouts.single().lineCount > 1)
                assertTrue("Continue must be below all song details at $name", continueButton.boundsInRoot.top >= artist.boundsInRoot.bottom)
                assertTrue("Dismiss must be below all song details at $name", dismissButton.boundsInRoot.top >= artist.boundsInRoot.bottom)
                assertFalse("The two actions must not overlap at $name", continueButton.boundsInRoot.overlaps(dismissButton.boundsInRoot))
                listOf(title, artist, continueButton, dismissButton).forEach { node ->
                    assertTrue("Content must remain inside the screen at $name", node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width && node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= 900)
                }
                all.text("Last played on PC")
            } finally { scene.close() }
        }
    }

    @Test
    fun continueResumesTheRemoteQueueAndFiltersKeepTheActionDisabled() = runBlocking(Dispatchers.Main) {
        val player = Player(NowPlaying(listOf(Song("A paused local song", "Local artist", "local-fixture")), 0))
        var dismissals = 0
        val scene = ImageComposeScene(390, 800, density = Density(1f), coroutineContext = coroutineContext) {
            TroitTheme { PlayerBar(player, handoff) { dismissals++ } }
        }
        try {
            repeat(4) { scene.render(it * 100_000_000L).close(); delay(10) }
            val all = scene.nodes()
            all.text(song.title) // The remote card replaces a paused local surface rather than adding another bar.
            assertFalse(all.any { SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == "A paused local song" } })
            assertTrue(all.text("Continue here").button().config[SemanticsActions.OnClick].action!!.invoke())
            assertEquals(Triple(handoff.queue, handoff.index, handoff.positionMs), player.played)
            assertEquals(1, dismissals)
        } finally { scene.close() }

        val blockedSong = song.copy(title = "Hindi songs — a filtered recording")
        player.played = null
        val filtered = ImageComposeScene(320, 800, density = Density(1f), coroutineContext = coroutineContext) {
            TroitTheme { CompositionLocalProvider(LocalListeningPreferences provides ListeningPreferences(excludedLanguages = setOf("hi"))) {
                PlayerBar(player, handoff.copy(queue = listOf(blockedSong))) { dismissals++ }
            } }
        }
        try {
            repeat(4) { filtered.render(it * 100_000_000L).close(); delay(10) }
            val all = filtered.nodes()
            all.text("This track is hidden by your music filters.")
            assertTrue(SemanticsProperties.Disabled in all.text("Continue here").button().config)
            assertNull(player.played)
            all.text("Dismiss").button().config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(2, dismissals)
        } finally { filtered.close() }
    }
}
