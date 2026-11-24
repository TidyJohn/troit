package app.tunes

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalComposeUiApi::class)
class NotificationFeaturesRenderTest {
    @Test
    fun notificationsAndRenameRenderAtNarrowWidthsAndLargeText() = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        val directory = Files.createTempDirectory("troit-notification-render").toFile()
        val library = Library(File(directory, "library.json"))
        val playlist = SavedPlaylist("Late night favorites", emptyList()).also(library::add)
        val alert = ChatAlert(SocialProfile("friend", "sunday", "Sunday Listener with a very long display name"),
            ChatMessage("message", 10, "friend", "me", text = "Found a new favorite! Want to listen together tonight?"))
        try {
            for (width in listOf(320, 1100)) for (dialog in listOf(false, true)) {
                val scene = ImageComposeScene(width, 760, density = Density(1f, if (width == 320) 1.3f else 1f),
                    coroutineContext = coroutineContext) {
                    TroitTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().padding(12.dp)) {
                                ChatNotificationBanner(alert, {}, {})
                            }
                            if (dialog) RenamePlaylistDialog(playlist, library) {}
                        }
                    }
                }
                try {
                    repeat(5) { scene.render(it * 100_000_000L).close(); delay(30) }
                    scene.render(1_000_000_000L).use { frame ->
                        File(output, "${if (dialog) "rename-playlist" else "chat-alert"}-$width.png")
                            .writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
                    }
                } finally { scene.close() }
            }
        } finally { directory.deleteRecursively() }
    }
}
