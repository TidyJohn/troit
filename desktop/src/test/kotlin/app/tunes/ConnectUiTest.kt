package app.tunes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Real rendered bounds and pointer actions for the shared phone, desktop and television controller. */
@OptIn(ExperimentalComposeUiApi::class)
class ConnectUiTest {
    private val device = "Living room TV"
    private val song = Song(
        "After the last train — the complete evening acoustic session with a special extended live outro",
        "The Evening Orchestra with a Special Guest Vocalist",
        "https://example.invalid/connected-song",
        durationSeconds = 240,
    )

    private fun snapshot(playing: Boolean = true): ConnectSnapshot {
        val time = Instant.now()
        return ConnectSnapshot("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002",
            device, song, playing, 45_000, 7, time.toString(), time.plusSeconds(120).toString(), true, queueIndex = 4)
    }

    private data class Layout(val name: String, val width: Int, val height: Int, val fontScale: Float = 1f, val tv: Boolean = false)
    private val layouts = listOf(Layout("phone-large-font", 320, 760, 1.4f), Layout("desktop", 1100, 760), Layout("tv", 960, 540, tv = true))

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(value: String) = first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun List<SemanticsNode>.description(value: String) = first { node ->
        SemanticsProperties.ContentDescription in node.config && value in node.config[SemanticsProperties.ContentDescription]
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        val bounds = node.boundsInRoot
        val position = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        sendPointerEvent(PointerEventType.Press, position)
        sendPointerEvent(PointerEventType.Release, position)
    }

    @Test
    fun songAndControlsFitPhoneDesktopAndTvAndRouteEveryAction() = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        for (layout in layouts) {
            var active by mutableStateOf(snapshot())
            val actions = mutableListOf<String>()
            var devices = 0
            var transfers = 0
            val scene = ImageComposeScene(layout.width, layout.height, density = Density(1f, layout.fontScale), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { Column {
                    ConnectedPlaybackContent(active, busy = false, message = null, onControl = { actions += it },
                        onDevices = { devices++ }, onListenHere = { transfers++ }, deviceName = "Phone", television = layout.tv)
                } } }
            }
            var time = 0L
            suspend fun settle() { repeat(5) { scene.render(time).close(); time += 50_000_000L; delay(15) } }
            try {
                settle()
                val all = scene.nodes()
                val title = all.text(song.title)
                val artist = all.text(song.artist)
                val previous = all.description("Previous on $device").button()
                val pause = all.description("Pause on $device").button()
                val next = all.description("Next on $device").button()
                val listen = all.text("Listen here").button()
                val openDevices = all.text("Devices").button()
                val controls = listOf(previous, pause, next, listen)
                all.text("Playing on $device")
                all.description("Playback progress on $device")
                val critical = listOf(title, artist, openDevices) + controls
                for (node in critical) {
                    val bounds = node.boundsInRoot
                    assertTrue("${layout.name}: critical content has a visible area: $bounds", bounds.width > 0 && bounds.height > 0)
                    assertTrue("${layout.name}: critical content stays inside the display: $bounds",
                        bounds.left >= 0 && bounds.top >= 0 && bounds.right <= layout.width && bounds.bottom <= layout.height)
                }
                for (control in controls) {
                    assertTrue("${layout.name}: controls stay below song details", control.boundsInRoot.top >= artist.boundsInRoot.bottom)
                    assertFalse("${layout.name}: controls do not overlap the song title", control.boundsInRoot.overlaps(title.boundsInRoot))
                    assertFalse("${layout.name}: connected controls are enabled", SemanticsProperties.Disabled in control.config)
                    val minimum = if (layout.tv) 56f else 48f
                    assertTrue("${layout.name}: controls have usable height", control.boundsInRoot.height >= minimum)
                }
                for (left in controls.indices) for (right in left + 1 until controls.size) {
                    assertFalse("${layout.name}: controls must not overlap", controls[left].boundsInRoot.overlaps(controls[right].boundsInRoot))
                }
                scene.render(time).use { frame ->
                    File(output, "connect-${layout.name}.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
                }
                File(output, "connect-${layout.name}-bounds.txt").writeText(critical.joinToString("\n") { it.boundsInRoot.toString() })
                scene.click(previous)
                scene.click(pause)
                scene.click(next)
                scene.click(listen)
                scene.click(openDevices)
                settle()
                assertEquals("${layout.name}: real controls route to their exact remote action", listOf("previous", "pause", "next"), actions)
                assertEquals(1, transfers)
                assertEquals(1, devices)

                active = active.copy(playing = false)
                settle()
                scene.nodes().text("Paused on $device")
                scene.click(scene.nodes().description("Play on $device").button())
                settle()
                assertEquals(listOf("previous", "pause", "next", "play"), actions)
            } finally { scene.close() }
        }
    }

    @Test
    fun expiredAndBusyPlayersDisableTransportAndTransferWithoutSendingActions() = runBlocking(Dispatchers.Main) {
        for (layout in layouts) {
            var active by mutableStateOf(snapshot())
            var busy by mutableStateOf(true)
            val actions = mutableListOf<String>()
            var transfers = 0
            val scene = ImageComposeScene(layout.width, layout.height, density = Density(1f, layout.fontScale), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { Column {
                    ConnectedPlaybackContent(active, busy, null, { actions += it }, onListenHere = { transfers++ }, television = layout.tv)
                } } }
            }
            var time = 0L
            suspend fun settle() { repeat(5) { scene.render(time).close(); time += 50_000_000L; delay(15) } }
            try {
                for (expired in listOf(false, true)) {
                    if (expired) {
                        active = active.copy(expiresAt = Instant.now().minusSeconds(1).toString())
                        busy = false
                    }
                    settle()
                    val all = scene.nodes()
                    if (expired) all.text("$device is offline")
                    val buttons = listOf("Previous", "Pause", "Next").map { all.description("$it on $device").button() } +
                        all.text("Listen here").button()
                    for (button in buttons) {
                        assertTrue("${layout.name}: ${if (expired) "expired" else "pending"} playback must disable the action",
                            SemanticsProperties.Disabled in button.config)
                        scene.click(button)
                    }
                    settle()
                    assertTrue("${layout.name}: a disabled control must not send a remote action", actions.isEmpty())
                    assertEquals("${layout.name}: a disabled transfer must not start audio", 0, transfers)
                }
            } finally { scene.close() }
        }
    }
}
