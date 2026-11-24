package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
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
import java.time.Instant

@OptIn(ExperimentalComposeUiApi::class)
class EcosystemUiTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.description(value: String) = first { node ->
        SemanticsProperties.ContentDescription in node.config && value in node.config[SemanticsProperties.ContentDescription]
    }
    private fun List<SemanticsNode>.text(value: String) = first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        val center = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, Offset(center.x, center.y))
        sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y))
    }
    private fun ImageComposeScene.save(name: String) {
        val output = File("build/ui-previews").apply { mkdirs() }
        render(1_000_000_000L).use { frame -> File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
    }

    @Test
    fun duplicateQueueSongsKeepTheirOriginalIndicesAndCurrentSongHasNoRemove() = runBlocking(Dispatchers.Main) {
        val current = Song("Playing now", "Artist", "https://example.invalid/current")
        val duplicate = Song("The same recording", "Artist", "https://example.invalid/duplicate")
        for (width in listOf(320, 960)) {
            val removed = mutableListOf<Int>(); val moved = mutableListOf<Pair<Int, Int>>()
            val scene = ImageComposeScene(width, 760, Density(1f, if (width == 320) 1.2f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) {
                    SharedQueueList(listOf(current, duplicate, duplicate), 0, false, width == 960,
                        { removed += it }, { from, to -> moved += from to to })
                } }
            }
            try {
                repeat(5) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes()
                assertFalse(all.any { SemanticsProperties.ContentDescription in it.config && "Remove queue item 1" in it.config[SemanticsProperties.ContentDescription] })
                val remove = all.description("Remove queue item 3").button()
                val move = all.description("Move queue item 3 up").button()
                for (button in listOf(remove, move)) {
                    assertTrue(button.boundsInRoot.left >= 0 && button.boundsInRoot.right <= width && button.boundsInRoot.bottom <= 760)
                }
                scene.click(remove); scene.click(move)
                assertEquals(listOf(2), removed)
                assertEquals(listOf(2 to 1), moved)
                assertTrue(SemanticsProperties.Disabled in all.description("Move queue item 2 up").button().config)
                scene.save("shared-queue-$width")
            } finally { scene.close() }
        }
        Unit
    }

    @Test
    fun namedDeviceActionsFitSmallPhoneAndTvAndOfflineTransferIsDisabled() = runBlocking(Dispatchers.Main) {
        for (width in listOf(320, 960)) {
            var transfers = 0
            val device = EcosystemDevice("tv", "runtime", "Living room television with a long custom name", "tv", false, Instant.now().toString())
            val scene = ImageComposeScene(width, 540, Density(1f, if (width == 320) 1.3f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { Column {
                    DeviceRow(device, false, false, false, false, width == 960, { transfers++ }, {})
                } } }
            }
            try {
                repeat(5) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes()
                val transfer = all.text("Play on this device").button()
                val rename = all.text("Rename").button()
                assertTrue(SemanticsProperties.Disabled in transfer.config)
                assertFalse(transfer.boundsInRoot.overlaps(rename.boundsInRoot))
                for (button in listOf(transfer, rename)) assertTrue(button.boundsInRoot.right <= width && button.boundsInRoot.bottom <= 540)
                scene.click(transfer)
                assertEquals(0, transfers)
                scene.save("device-hub-$width")
            } finally { scene.close() }
        }
        Unit
    }
}
