package app.tunes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Real bounds and input exercise compactness without reducing interactive targets. */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class CompactPlayerUiTest {
    private val song = Song("MANGU — the complete acoustic evening session", "Fourtwnty with a special guest", "https://example.invalid/mangu", durationSeconds = 240)
    private fun snapshot(expired: Boolean = false) = Instant.now().let { time ->
        ConnectSnapshot("phone", "runtime", "Phone", song, true, 45_000, 1, time.toString(),
            (if (expired) time.minusSeconds(1) else time.plusSeconds(120)).toString(), true)
    }
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(value: String) = first { n -> SemanticsProperties.Text in n.config && n.config[SemanticsProperties.Text].any { it.text == value } }
    private fun List<SemanticsNode>.description(value: String) = first { n -> SemanticsProperties.ContentDescription in n.config && value in n.config[SemanticsProperties.ContentDescription] }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        val point = node.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, Offset(point.x, point.y))
        sendPointerEvent(PointerEventType.Release, Offset(point.x, point.y))
    }
    private suspend fun ImageComposeScene.settle() { repeat(5) { render(it * 100_000_000L).close(); delay(15) } }
    private fun ImageComposeScene.save(name: String) {
        val output = File("build/ui-previews").apply { mkdirs() }
        render(1_000_000_000L).use { frame -> File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
    }

    @Test fun compactPlayerFitsWithLongTitlesLargeTextAndReversibleHearts() = runBlocking(Dispatchers.Main) {
        data class Layout(val width: Int, val height: Int, val font: Float = 1f, val tv: Boolean = false)
        for (layout in listOf(Layout(320, 760, 1.4f), Layout(390, 760), Layout(1100, 312), Layout(1896, 312), Layout(704, 540, 1.2f, true))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val dir = Files.createTempDirectory("troit-compact-player").toFile()
            val sync = Sync(dir, "PC", Library(File(dir, "library.json")), scope)
            val listening = ListeningLibraryState(sync, dir, scope)
            val commands = mutableListOf<String>()
            var devices = 0
            var barHeight = 0
            val active = snapshot()
            val scene = ImageComposeScene(layout.width, layout.height, Density(1f, layout.font), coroutineContext = coroutineContext) {
                TroitTheme { CompositionLocalProvider(LocalListeningLibrary provides listening) {
                    Surface(Modifier.fillMaxSize()) { Column {
                        Surface(Modifier.fillMaxWidth().onSizeChanged { barHeight = it.height }.padding(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)) {
                            ConnectedPlaybackContent(active, false, null, { commands += it }, onDevices = { devices++ },
                                onListenHere = {}, television = layout.tv, compact = true)
                        }
                    } }
                } }
            }
            try {
                scene.settle()
                val all = scene.nodes()
                val controls = listOf("Previous", "Pause", "Next").map { all.description("$it on Phone").button() }
                val more = all.description("More playback options").button()
                val heart = all.description("Like ${song.title}").button()
                val important = controls + more + heart + listOf(all.text(song.title), all.text(song.artist))
                for (node in important) {
                    val bounds = node.boundsInRoot
                    assertTrue("${layout.width}: visible $bounds", bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= layout.width && bounds.top >= 0 && bounds.bottom <= layout.height)
                }
                for (button in controls + more + heart) assertTrue("${layout.width}: usable target ${button.boundsInRoot}", button.boundsInRoot.height >= if (layout.tv) 56f else 48f)
                for (a in controls.indices) for (b in a + 1 until controls.size) assertFalse(controls[a].boundsInRoot.overlaps(controls[b].boundsInRoot))
                val limit = if (layout.tv) 216 else if (layout.width < 640) 180 else 116
                assertTrue("${layout.width}: compact bar should fit $limit px, actual $barHeight", barHeight <= limit)
                assertFalse("Secondary actions stay in the menu", all.any { SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == "Lyrics" || text.text == "Sleep timer" } })
                scene.save("compact-connected-${if (layout.tv) "tv" else layout.width}")
                File("build/ui-previews/compact-connected-${if (layout.tv) "tv" else layout.width}-size.txt").writeText("width=${layout.width}, fontScale=${layout.font}, height=$barHeight")
                controls.forEach { scene.click(it) }
                assertEquals(listOf("previous", "pause", "next"), commands)
                scene.click(heart); scene.settle()
                assertTrue(listening.isLiked(song))
                scene.click(scene.nodes().description("Unlike ${song.title}").button()); scene.settle()
                assertFalse(listening.isLiked(song))
                val device = scene.nodes().first { n ->
                    (SemanticsProperties.Text in n.config && n.config[SemanticsProperties.Text].any { it.text == "Devices" }) ||
                        (SemanticsProperties.ContentDescription in n.config && n.config[SemanticsProperties.ContentDescription].any { it.startsWith("Devices & playback.") })
                }.button()
                scene.click(device)
                assertEquals(1, devices)
            } finally { scene.close(); scope.cancel() }
        }
    }

    @Test fun moreMenuRoutesSecondaryActionsAndCanBeDismissedWithoutChangingPlayback() = runBlocking(Dispatchers.Main) {
        val events = mutableListOf<String>()
        val active = snapshot()
        val scene = ImageComposeScene(390, 760, coroutineContext = coroutineContext) {
            TroitTheme { CompositionLocalProvider(LocalOpenLyrics provides { events += "lyrics" }, LocalOpenSleepTimer provides { events += "timer" }) {
                Surface(Modifier.fillMaxSize()) { Column {
                    ConnectedPlaybackContent(active, false, null, { events += it }, onDevices = { events += "devices" },
                        onListenHere = { events += "transfer" }, compact = true)
                } }
            } }
        }
        try {
            suspend fun open() { scene.click(scene.nodes().description("More playback options").button()); scene.settle() }
            scene.settle(); open()
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp)); scene.settle()
            assertTrue(events.isEmpty())
            assertFalse("Escape closes the menu", scene.nodes().any { n -> SemanticsProperties.Text in n.config && n.config[SemanticsProperties.Text].any { it.text == "Lyrics" } })
            for ((label, expected) in listOf("Lyrics" to "lyrics", "Sleep timer" to "timer", "Listen here" to "transfer", "Devices & playback" to "devices")) {
                open(); scene.click(scene.nodes().text(label).button()); scene.settle()
                assertEquals(expected, events.last())
                scene.nodes().text(song.title)
            }
            assertEquals(listOf("lyrics", "timer", "transfer", "devices"), events)
        } finally { scene.close() }
    }

    @Test fun offlineReferenceUsesOneDesktopRowAtWindowsDisplayScale() = runBlocking(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val dir = Files.createTempDirectory("troit-offline-player-reference").toFile()
        val sync = Sync(dir, "PC", Library(File(dir, "library.json")), scope)
        val listening = ListeningLibraryState(sync, dir, scope)
        val active = snapshot(true).copy(song = song.copy(title = "MANGU", artist = "Fourtwnty", durationSeconds = 0))
        var barHeight = 0
        val scene = ImageComposeScene(1896, 312, Density(1.25f), coroutineContext = coroutineContext) {
            TroitTheme { CompositionLocalProvider(LocalListeningLibrary provides listening) {
                Surface(Modifier.fillMaxSize()) { Column {
                    Surface(Modifier.fillMaxWidth().onSizeChanged { barHeight = it.height }.padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)) {
                        ConnectedPlaybackContent(active, false, null, { fail("Offline control sent") }, onDevices = {},
                            onListenHere = { fail("Offline transfer sent") }, compact = true)
                    }
                } }
            } }
        }
        try {
            scene.settle()
            assertTrue("The source screenshot's offline state should fit a compact row: $barHeight", barHeight <= 115)
            scene.nodes().text("Phone is offline")
            scene.save("compact-connected-reference-offline")
            File("build/ui-previews/compact-connected-reference-size.txt").writeText("viewport=1896x312, density=1.25, height=$barHeight")
        } finally { scene.close(); scope.cancel() }
    }

    @Test fun staleAndPendingCompactPlayersKeepRecoveryAvailableWithoutSendingControls() = runBlocking(Dispatchers.Main) {
        for (expired in listOf(false, true)) {
            var deviceActions = 0
            val active = snapshot(expired)
            val scene = ImageComposeScene(320, 760, Density(1f, 1.4f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { Column {
                    ConnectedPlaybackContent(active, !expired, if (expired) "Connection interrupted. Open Devices to reconnect." else null,
                        { fail("Disabled remote control sent $it") }, onDevices = { deviceActions++ },
                        onListenHere = { fail("Disabled transfer sent") }, compact = true, messageIsError = expired)
                } } }
            }
            try {
                scene.settle()
                if (!expired) scene.nodes().text("Waiting for Phone…") else scene.nodes().text("Offline")
                val controls = listOf("Previous", "Pause", "Next").map { scene.nodes().description("$it on Phone").button() }
                for (button in controls) { assertTrue(SemanticsProperties.Disabled in button.config); scene.click(button) }
                scene.click(scene.nodes().description("More playback options").button()); scene.settle()
                val transfer = scene.nodes().text("Listen here").button()
                assertTrue(SemanticsProperties.Disabled in transfer.config)
                scene.click(transfer)
                if (expired) { scene.nodes().text("Open Troit on Phone to reconnect."); scene.save("compact-connected-offline-menu") }
                scene.click(scene.nodes().text("Devices & playback").button()); scene.settle()
                assertEquals(1, deviceActions)
            } finally { scene.close() }
        }
    }
}
