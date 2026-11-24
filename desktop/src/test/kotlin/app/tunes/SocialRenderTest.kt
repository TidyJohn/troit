package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Local RPC fixtures: no account, chat message, or friendship is created on the real service. */
@OptIn(ExperimentalComposeUiApi::class)
class SocialRenderTest {
    private val me = "00000000-0000-0000-0000-000000000001"
    private val friend = "00000000-0000-0000-0000-000000000002"

    private fun profile(id: String, handle: String, name: String) = JSONObject().put("id", id).put("handle", handle).put("display_name", name)

    private fun snapshot() = JSONObject()
        .put("profile", profile(me, "nightowl", "Night Owl"))
        .put("connections", JSONArray().put(JSONObject().put("id", "friendship-1").put("peer", profile(friend, "sunday", "Sunday Listener")).put("status", "accepted").put("requested_by", me))
            .put(JSONObject().put("id", "request-2").put("peer", profile("00000000-0000-0000-0000-000000000003", "freshfinds", "Fresh Finds")).put("status", "pending").put("requested_by", "00000000-0000-0000-0000-000000000003")))
        .put("blocked", JSONArray()).toString()

    private fun messages() = JSONArray().apply {
        put(JSONObject().put("id", "message-1").put("seq", 1).put("sender_id", friend).put("recipient_id", me).put("text", "This song has been on repeat all afternoon.").put("sticker_id", JSONObject.NULL).put("created_at", "2026-11-11T08:10:00Z"))
        put(JSONObject().put("id", "message-2").put("seq", 2).put("sender_id", me).put("recipient_id", friend).put("text", "Same! Saving it to the weekend playlist.").put("sticker_id", JSONObject.NULL).put("created_at", "2026-11-11T08:10:20Z"))
        put(JSONObject().put("id", "message-3").put("seq", 3).put("sender_id", friend).put("recipient_id", me).put("text", JSONObject.NULL).put("sticker_id", "jomok-wkwk").put("created_at", "2026-11-11T08:10:40Z"))
        put(JSONObject().put("id", "message-4").put("seq", 4).put("sender_id", me).put("recipient_id", friend).put("text", JSONObject.NULL).put("sticker_id", "jawir-ngopi").put("created_at", "2026-11-11T08:11:00Z"))
    }.toString()

    private suspend fun render(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val output = File("build/ui-previews").apply { mkdirs() }
        val scene = ImageComposeScene(width, height, density = Density(1f), coroutineContext = kotlinx.coroutines.currentCoroutineContext(), content = content)
        try {
            repeat(8) { scene.render(it * 100_000_000L).close(); delay(25) }
            scene.render(1_000_000_000L).use { frame ->
                File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
            }
        } finally { scene.close() }
    }

    @Test
    fun friendsAndConversationRenderOnDesktopAndPhoneWithLocalStickers() = runBlocking(Dispatchers.Main) {
        val directory = Files.createTempDirectory("troit-social-preview").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val library = Library(File(directory, "library.json"))
        val sync = Sync(directory, "Preview", library, scope)
        var activeAccount: String? = me
        val social = SocialState(sync, scope, request = { path, _, body ->
            when (path) {
                "rpc/troit_social_snapshot" -> snapshot()
                "rpc/troit_chat_messages" -> if (JSONObject(body.orEmpty()).isNull("p_after")) messages() else "[]"
                else -> error("A render fixture must not write to a service: $path")
            }
        }, accountId = { activeAccount })
        try {
            val onboarding = SocialState(sync, scope, request = { _, _, _ -> """{"profile":null,"connections":[],"blocked":[]}""" }, accountId = { me })
            onboarding.refresh()
            render("phone-profile-compact", 390, 360) { TroitTheme { Surface(Modifier.fillMaxSize()) { FriendsScreen(onboarding, sync) {} } } }
            social.refresh()
            assertEquals(1, social.friends.size)
            render("phone-friends", 390, 760) { TroitTheme { Surface(Modifier.fillMaxSize()) { FriendsScreen(social, sync) {} } } }
            social.openChat(social.friends.single())
            withTimeout(3_000) { while (social.messages.size < 4) delay(5) }
            render("desktop-chat", 1020, 780) { TroitTheme { Surface(Modifier.fillMaxSize()) { FriendsScreen(social, sync) {} } } }
            render("phone-chat", 390, 760) { TroitTheme { Surface(Modifier.fillMaxSize()) { FriendsScreen(social, sync) {} } } }
            activeAccount = null
            social.resetForAccount()
            assertNull(social.profile)
            assertEquals(0, social.messages.size)
            render("phone-social-signed-out", 390, 760) { TroitTheme { Surface(Modifier.fillMaxSize()) { FriendsScreen(social, sync) {} } } }
            render("sticker-packs", 760, 420) {
                TroitTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        StickerCatalog.entries.chunked(4).forEach { pack ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pack.forEach { sticker -> Sticker(sticker.id, Modifier.weight(1f)) }
                            }
                        }
                        Sticker("unknown-sticker")
                    }
                } }
            }
        } finally { scope.cancel() }
    }
}
