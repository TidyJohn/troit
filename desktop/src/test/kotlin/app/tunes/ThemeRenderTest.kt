package app.tunes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalComposeUiApi::class)
class ThemeRenderTest {
    @Test
    fun appearanceRendersAtPhoneAndDesktopSizes() = runBlocking(Dispatchers.Main) {
        val output = File("build/ui-previews").apply { mkdirs() }
        for ((name, width, settings) in listOf(
            Triple("appearance-phone-paper", 360, ThemeSettings("paper", "blue")),
            Triple("appearance-desktop-ocean", 870, ThemeSettings("ocean", "mint")),
        )) {
            val dir = Files.createTempDirectory("troit-theme-render").toFile()
            val scene = ImageComposeScene(width, 1000, Density(1f), coroutineContext = coroutineContext) {
                val scope = rememberCoroutineScope()
                val state = remember { UserThemeState(dir, scope, { null }, { _, _, _, _ -> error("Offline fixture must not call the network") }).apply {
                    selectTheme(settings.themeId); selectAccent(settings.accentId)
                } }
                TroitTheme(state.settings) { Surface(Modifier.fillMaxSize()) { ThemeScreen(state) } }
            }
            try {
                repeat(8) { scene.render(it * 100_000_000L).close(); delay(25) }
                scene.render(1_000_000_000L).use { frame ->
                    File(output, "$name.png").writeBytes(requireNotNull(frame.encodeToData(EncodedImageFormat.PNG)).bytes)
                }
            } finally { scene.close() }
        }
    }
}
