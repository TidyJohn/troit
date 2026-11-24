package app.tunes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/** Keep the native Windows frame and its controls, blended into the app: no border, title or icon. */
@Composable
fun ThemedWindowsTitleBar(window: ComposeWindow) {
    val colors = MaterialTheme.colorScheme
    val background = colors.background.toArgb()
    val foreground = colors.onBackground.toArgb()
    DisposableEffect(window, background, foreground) {
        window.background = java.awt.Color(background, true)
        fun apply() { WindowsFrameTheme.apply(window, background, foreground) }
        // DISPLAYABILITY_CHANGED is early enough to theme the native peer before its first show.
        val hierarchy = HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && window.isDisplayable) apply()
        }
        val lifecycle = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = apply()
            override fun windowActivated(event: WindowEvent) = apply()
        }
        window.addHierarchyListener(hierarchy)
        window.addWindowListener(lifecycle)
        if (window.isDisplayable) apply()
        onDispose {
            window.removeHierarchyListener(hierarchy)
            window.removeWindowListener(lifecycle)
        }
    }
}

/** Native attribute ids and COLORREF layout follow Microsoft's DWM API. */
internal object WindowsFrameTheme {
    const val DARK_MODE = 20
    const val BORDER_COLOR = 34
    const val CAPTION_COLOR = 35
    const val TEXT_COLOR = 36
    /** Result key for hiding the caption text and icon; not a DWM attribute. */
    const val HIDE_TITLE = -1
    private const val COLOR_NONE = 0xFFFFFFFE.toInt() // DWMWA_COLOR_NONE: draw no window border

    interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmGetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    interface UxTheme : StdCallLibrary {
        /** [options] is WTA_OPTIONS: flags, then mask. */
        fun SetWindowThemeAttribute(window: Pointer, attribute: Int, options: IntArray, size: Int): Int
    }

    private val dwm by lazy { Native.load("dwmapi", DwmApi::class.java) }
    private val uxTheme by lazy { Native.load("uxtheme", UxTheme::class.java) }
    val isWindows get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun colorRef(argb: Int) = ((argb ushr 16) and 0xFF) or (argb and 0xFF00) or ((argb and 0xFF) shl 16)

    /** Unsupported attributes on older Windows versions leave the normal native fallback intact. */
    fun apply(window: Window, background: Int, foreground: Int): Map<Int, Int> {
        if (!isWindows || !window.isDisplayable) return emptyMap()
        return runCatching {
            val handle = Native.getWindowPointer(window)
            linkedMapOf(
                DARK_MODE to if ((((background ushr 16) and 255) * .2126 + ((background ushr 8) and 255) * .7152 + (background and 255) * .0722) < 127.5) 1 else 0,
                CAPTION_COLOR to colorRef(background),
                TEXT_COLOR to colorRef(foreground),
                BORDER_COLOR to COLOR_NONE,
            ).mapValues { (attribute, value) ->
                dwm.DwmSetWindowAttribute(handle, attribute, IntByReference(value), Int.SIZE_BYTES)
            } +
                // WTA_NONCLIENT = 1; WTNCA_NODRAWCAPTION | WTNCA_NODRAWICON = 3. The taskbar keeps both.
                (HIDE_TITLE to uxTheme.SetWindowThemeAttribute(handle, 1, intArrayOf(3, 3), 2 * Int.SIZE_BYTES))
        }.getOrDefault(emptyMap())
    }

    internal fun read(window: Window, attribute: Int): Int? {
        if (!isWindows || !window.isDisplayable) return null
        return runCatching {
            val value = IntByReference()
            val result = dwm.DwmGetWindowAttribute(Native.getWindowPointer(window), attribute, value, Int.SIZE_BYTES)
            value.value.takeIf { result == 0 }
        }.getOrNull()
    }
}
