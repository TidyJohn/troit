package app.tunes

import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/** Uses Windows' native notification area; the in-app banner remains available on every system. */
internal class DesktopChatNotifications(private val openChat: (String) -> Unit, private val openApp: () -> Unit) : AutoCloseable {
    private var tray: TrayIcon? = null
    private var owner: String? = null
    private var lastAlert: ChatAlert? = null

    fun accountChanged(account: String?) {
        if (owner == account) return
        owner = account
        lastAlert = null
        close()
    }

    fun show(alert: ChatAlert) {
        if (owner != alert.message.recipientId) return
        lastAlert = alert
        val icon = tray ?: createTray()?.also { tray = it }
        if (icon != null) icon.displayMessage("${alert.peer.displayName} · Troit", alert.preview, TrayIcon.MessageType.NONE)
        else runCatching { Toolkit.getDefaultToolkit().beep() }
    }

    private fun createTray(): TrayIcon? = runCatching {
        if (!SystemTray.isSupported()) return null
        val size = SystemTray.getSystemTray().trayIconSize
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = Color.WHITE
            fillRoundRect(0, 0, size.width, size.height, 8, 8)
            color = Color.BLACK
            font = Font(Font.SANS_SERIF, Font.BOLD, (size.height * .82).toInt())
            drawString("♪", size.width / 5, (size.height * .82).toInt())
            dispose()
        }
        val menu = PopupMenu().apply {
            add(MenuItem("Open Troit").apply { addActionListener { openApp() } })
            add(MenuItem("Open latest message").apply { addActionListener { openLatest() } })
        }
        TrayIcon(image, "Troit messages", menu).apply {
            isImageAutoSize = true
            // AWT does not identify which queued balloon was clicked. Open the inbox;
            // the explicit menu action can safely target the latest sender instead.
            addActionListener { openChat("") }
            SystemTray.getSystemTray().add(this)
        }
    }.getOrNull()

    private fun openLatest() {
        val alert = lastAlert
        if (alert != null && alert.message.recipientId == owner) openChat(alert.peer.id) else openApp()
    }

    override fun close() {
        tray?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        tray = null
    }
}
