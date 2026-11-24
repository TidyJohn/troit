package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Actual dialogs use fixture RPCs; no production account or room is touched. */
@OptIn(ExperimentalComposeUiApi::class)
class PartyTimerRenderTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun List<SemanticsNode>.text(value: String) = first { node ->
        SemanticsProperties.Text in node.config && node.config[SemanticsProperties.Text].any { it.text == value }
    }
    private fun SemanticsNode.button() = generateSequence(this) { it.parent }.first { SemanticsActions.OnClick in it.config }
    private fun SemanticsNode.visible(width: Int, height: Int) = boundsInRoot.let { it.width > 0 && it.height > 0 && it.left >= 0 && it.right <= width && it.top >= 0 && it.bottom <= height }
    private fun sync(scope: CoroutineScope, signedIn: Boolean): Sync {
        val dir = Files.createTempDirectory("troit-session-render").toFile()
        if (signedIn) File(dir, "session.json").writeText(JSONObject().put("userId", me).put("email", "fixture@example.invalid")
            .put("access", "fixture-access").put("refresh", "fixture-refresh").toString())
        val http = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
            .body("{}".toResponseBody("application/json".toMediaType())).build() }.build()
        return Sync(dir, "Fixture", Library(File(dir, "library.json")), scope, http)
    }
    private fun room(host: Boolean): String {
        val song = Song("An evening song with a longer name", "Evening Artist", "https://example.invalid/song", durationSeconds = 240)
        return JSONObject().put("id", me).put("code", "ABCD1234").put("name", "Evening together").put("host_id", me).put("host_name", "Evening Host")
            .put("is_host", host).put("members", JSONArray().put(JSONObject().put("id", me).put("name", "Evening Host").put("host", true)))
            .put("queue", JSONObject().put("device_id", me).put("instance_id", me).put("revision", 1).put("queue", Library.songsToJson(listOf(song, song)))
                .put("index", 0).put("position_ms", 20_000).put("playing", true).put("mode", "STOP").put("updated_at", Instant.now().toString())
                .put("playback_revision", 1).put("device_name", "Living room TV").put("fresh", true)).toString()
    }
    private fun save(scene: ImageComposeScene, name: String) {
        val output = File("build/ui-previews").apply { mkdirs() }
        scene.render(1_000_000_000L).use { frame -> File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes) }
    }
    @Test fun sleepTimerDialogFitsPhoneAndTvAndExposesAllChoices() = runBlocking(Dispatchers.Main) {
        for ((width, height) in listOf(320 to 760, 960 to 540)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val sync = sync(scope, true)
            val reply = JSONObject().put("revision", 0).put("server_time", Instant.now().toString()).put("timer", JSONObject.NULL).toString()
            val state = SleepTimerState(sync, client = SleepTimerClient(sync, { _, _ -> reply }))
            val scene = ImageComposeScene(width, height, Density(1f, if (width == 320) 1.3f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { SleepTimerDialog(state) {} } }
            }
            try {
                repeat(6) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes(); val done = all.text("Done").button()
                assertTrue("Timer Done is always inside the display", done.visible(width, height))
                val first = all.text("15 minutes").button(); assertTrue(first.visible(width, height))
                val second = all.text("30 minutes").button(); assertFalse(first.boundsInRoot.overlaps(second.boundsInRoot))
                all.text("60 minutes"); all.text("End of this song")
                save(scene, "sleep-dialog-$width")
            } finally { scene.close(); scope.cancel() }
        }
        Unit
    }
    @Test fun partyHostGuestAndSignedOutDialogsKeepPermissionActionsSeparate() = runBlocking(Dispatchers.Main) {
        for ((width, height) in listOf(320 to 760, 960 to 540)) for (mode in listOf("host", "guest", "signed-out")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val sync = sync(scope, mode != "signed-out")
            val state = PartyState(sync, PartyClient(sync, { _, _ -> room(mode == "host") }))
            state.refresh()
            val scene = ImageComposeScene(width, height, Density(1f, if (width == 320) 1.3f else 1f), coroutineContext = coroutineContext) {
                TroitTheme { Surface(Modifier.fillMaxSize()) { PartyDialog(state) {} } }
            }
            try {
                repeat(6) { scene.render(it * 100_000_000L).close(); delay(15) }
                val all = scene.nodes(); assertTrue("Party Done stays inside the display", all.text("Done").button().visible(width, height))
                fun has(value: String) = all.any { SemanticsProperties.Text in it.config && it.config[SemanticsProperties.Text].any { text -> text.text == value } }
                if (mode == "host") {
                    val playback = all.text("Playback controls").button(); val edit = all.text("Edit queue").button()
                    assertTrue(playback.visible(width, height)); assertFalse(playback.boundsInRoot.overlaps(edit.boundsInRoot))
                } else {
                    assertFalse(has("Playback controls")); assertFalse(has("Edit queue"))
                    if (mode == "signed-out") assertTrue(SemanticsProperties.Disabled in all.text("Host a room").button().config)
                    else all.text("Add a song")
                }
                save(scene, "party-dialog-$mode-$width")
            } finally { scene.close(); scope.cancel() }
        }
        Unit
    }
}
