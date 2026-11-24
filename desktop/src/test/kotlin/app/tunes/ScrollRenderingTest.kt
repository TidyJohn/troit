package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Exercises the real nested music shelves with wheel input, without opening a desktop window. */
@OptIn(ExperimentalComposeUiApi::class)
class ScrollRenderingTest {
    @Test
    fun largeCatalogueScrollsWithDuplicateTitlesAndTrackUrls() = runBlocking(Dispatchers.Main) {
        // Both duplicate shelf titles and duplicate tracks are valid catalogue data.
        val catalogue = List(100) { shelf ->
            Section("Collection ${shelf / 2}", List(24) { track ->
                Song("Track $track", "Artist $shelf", "preview-track-${track / 2}", durationSeconds = 180 + track.toLong())
            })
        }
        val home = Loader(CoroutineScope(coroutineContext)) { catalogue }
        val scroll = LazyListState()
        val frameTimes = ArrayList<Double>()
        var frameNanos = 0L
        val scene = ImageComposeScene(width = 900, height = 760, density = Density(1f), coroutineContext = coroutineContext) {
            TroitTheme { Surface(Modifier.fillMaxSize()) { HomeScreen(home, scroll, { _, _ -> }, {}) {} } }
        }
        fun renderFrame(): Double {
            val start = System.nanoTime()
            scene.render(frameNanos).close()
            frameNanos += 16_666_667L
            return (System.nanoTime() - start) / 1_000_000.0
        }
        suspend fun settle() {
            repeat(12) { renderFrame(); delay(5) }
        }
        try {
            val coldFrameMs = renderFrame()
            withTimeout(5_000) {
                while (home.result == null) { delay(5); renderFrame() }
            }
            assertTrue("The local catalogue fixture must load", home.result?.isSuccess == true)
            settle()
            assertEquals("All shelves and the three discovery header items remain in the list", 103, scroll.layoutInfo.totalItemsCount)
            val initialIndex = scroll.firstVisibleItemIndex
            val initialOffset = scroll.firstVisibleItemScrollOffset

            repeat(32) {
                scene.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = Offset(855f, 530f),
                    scrollDelta = Offset(0f, 6f),
                    timeMillis = frameNanos / 1_000_000L,
                )
                frameTimes += renderFrame()
                delay(5)
            }
            settle()
            val forwardIndex = scroll.firstVisibleItemIndex
            val forwardOffset = scroll.firstVisibleItemScrollOffset
            assertTrue("Wheel input must advance the real catalogue scroll state", forwardIndex > initialIndex || forwardOffset > initialOffset)

            repeat(8) {
                scene.sendPointerEvent(
                    eventType = PointerEventType.Scroll,
                    position = Offset(855f, 530f),
                    scrollDelta = Offset(0f, -6f),
                    timeMillis = frameNanos / 1_000_000L,
                )
                frameTimes += renderFrame()
                delay(5)
            }
            settle()
            assertTrue(
                "Reverse wheel input must also work after rendering duplicate catalogue entries",
                scroll.firstVisibleItemIndex < forwardIndex ||
                    (scroll.firstVisibleItemIndex == forwardIndex && scroll.firstVisibleItemScrollOffset < forwardOffset),
            )
            val visibleCount = scroll.layoutInfo.visibleItemsInfo.size
            assertTrue("The large feed must keep only a viewport-sized set of visible top-level items", visibleCount in 1..12)
            assertEquals(103, scroll.layoutInfo.totalItemsCount)

            val output = File("build/ui-previews").apply { mkdirs() }
            scene.render(frameNanos).use { frame ->
                File(output, "large-catalogue-scrolled.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
            }
            val sorted = frameTimes.sorted()
            fun number(value: Double) = String.format(Locale.ROOT, "%.3f", value)
            val report = """
                Offscreen catalogue scroll diagnostic
                This is a desktop render/correctness check, not a real-device FPS benchmark.
                Dataset: 100 shelves x 24 songs (2,400 songs), with repeated shelf titles and repeated song URLs.
                Wheel input: 32 forward events, 8 reverse events; 40 measured render calls.
                Initial position: item $initialIndex, offset $initialOffset px.
                Forward position: item $forwardIndex, offset $forwardOffset px.
                Final position: item ${scroll.firstVisibleItemIndex}, offset ${scroll.firstVisibleItemScrollOffset} px.
                Visible top-level items: $visibleCount / ${scroll.layoutInfo.totalItemsCount}.
                Cold render: ${number(coldFrameMs)} ms.
                Wheel render median: ${number(sorted[sorted.size / 2])} ms.
                Wheel render p95: ${number(sorted[((sorted.size - 1) * .95).toInt()])} ms.
                Wheel render maximum: ${number(sorted.last())} ms.
                No hardware-dependent timing threshold is used.
            """.trimIndent() + "\n"
            File(output, "scroll-report.txt").writeText(report)
        } finally { scene.close() }
    }
}
