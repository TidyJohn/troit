package app.tunes

import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

fun main() = application {
    val dataDir = remember {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Tunes").apply { mkdirs() }
    }
    val player = remember(dataDir) { MpvPlayer(dataDir = dataDir) }
    remember(player) { WindowsMediaKeys(player) }
    val windowState = rememberWindowState(width = 1100.dp, height = 760.dp)
    var notificationPeerId by remember { mutableStateOf<String?>(null) }
    Window(
        title = "Troit",
        icon = TroitWindowIcon,
        state = windowState,
        onCloseRequest = {
            player.close()
            exitApplication()
        },
    ) {
        var focused by remember { mutableStateOf(window.isFocused) }
        val notifications = remember(window) {
            fun openApp() {
                windowState.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
            DesktopChatNotifications({ peer -> notificationPeerId = peer; openApp() }, ::openApp)
        }
        DisposableEffect(window, notifications) {
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent) { focused = true }
                override fun windowLostFocus(event: WindowEvent) { focused = false }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener); notifications.close() }
        }
        App(player, dataDir, "PC", themeChrome = { ThemedWindowsTitleBar(window) },
            foreground = focused && !windowState.isMinimized, pollChatInBackground = true,
            onAccountChanged = { notifications.accountChanged(it); notificationPeerId = null },
            onChatNotification = notifications::show, notificationPeerId = notificationPeerId,
            onNotificationOpened = { notificationPeerId = null },
            videoSurface = { song, modifier -> MpvSyncedVideo(player, song, modifier) }) {}
    }
}
