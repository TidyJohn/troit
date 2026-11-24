package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Test-only strings rather than copyrighted lyrics. */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsRenderTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(text: String) = first { node -> SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == text } }

    @Test fun activeLineFitsPhoneAndTvAndChangesWithPlaybackPosition() = runBlocking(Dispatchers.Main) {
        val song = Song("Fixture recording with a long display title", "Fixture artist", "fixture", durationSeconds = 240)
        val lines = List(20) { LyricLine(it * 5_000L, "Test lyric line number ${it + 1}") }
        val result = LyricsResult(lines, sourceId = 42)
        val output = File("build/ui-previews").apply { mkdirs() }
        for (width in listOf(320, 960)) {
            val scene = ImageComposeScene(width, if (width == 320) 760 else 540, Density(1f, if (width == 320) 1.3f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { LyricsPanel(song, result, 5_500, width == 960) } }
            }
            try {
                repeat(10) { scene.render(it * 100_000_000L).close(); delay(25) }
                val all = scene.nodes()
                val current = all.first { SemanticsProperties.Selected in it.config && it.config[SemanticsProperties.Selected] }
                assertTrue(current.boundsInRoot.width > 0 && current.boundsInRoot.right <= width)
                assertEquals("Test lyric line number 2", current.config[SemanticsProperties.Text].single().text)
                all.text("Lyrics by LRCLIB")
                scene.render(2_000_000_000L).use { frame -> File(output, "lyrics-$width.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
            } finally { scene.close() }
        }
        assertEquals(3, result.activeIndex(15_000))
        Unit
    }

    @Test fun plainAndMissingLyricsNeverShowAFabricatedCurrentLine() = runBlocking(Dispatchers.Main) {
        val song = Song("Fixture", "Artist", "fixture")
        for (result in listOf(LyricsResult(plainText = "Test plain line"), LyricsResult(), LyricsResult(instrumental = true))) {
            val scene = ImageComposeScene(390, 760, Density(1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { LyricsPanel(song, result, 30_000) } }
            }
            try {
                repeat(4) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes()
                assertFalse(all.any { SemanticsProperties.Selected in it.config && it.config[SemanticsProperties.Selected] })
                all.text(when { result.instrumental -> "Instrumental"; result.plainText != null -> "Text lyrics · timing unavailable"; else -> "No lyrics for this recording" })
            } finally { scene.close() }
        }
        Unit
    }
}
