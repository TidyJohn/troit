package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

class WindowsTitleBarTest {
    @Test
    fun nativeDecoratedWindowAcceptsThemeColorsAndEnablesDarkMode() {
        assumeTrue("Native DWM integration requires Windows", WindowsFrameTheme.isWindows)
        assumeFalse("A native AWT peer is required", GraphicsEnvironment.isHeadless())
        var result: Map<Int, Int> = emptyMap()
        var darkMode: Int? = null
        var paperMode: Int? = null
        SwingUtilities.invokeAndWait {
            val frame = Frame("Troit native theme integration test")
            try {
                // Create a real native peer without displaying a window or touching a running app.
                frame.addNotify()
                assertFalse(frame.isVisible)
                assertFalse(frame.isUndecorated)
                assertTrue(frame.isResizable)
                result = WindowsFrameTheme.apply(frame, 0xFF000000.toInt(), 0xFFFAFAFA.toInt())
                darkMode = WindowsFrameTheme.read(frame, WindowsFrameTheme.DARK_MODE)
                WindowsFrameTheme.apply(frame, 0xFFF7F7F4.toInt(), 0xFF111111.toInt())
                paperMode = WindowsFrameTheme.read(frame, WindowsFrameTheme.DARK_MODE)
            } finally { frame.dispose() }
        }
        assertEquals("Dark mode must be accepted by DWM", 0, result[WindowsFrameTheme.DARK_MODE])
        assertEquals("The caption text and icon must be hidden", 0, result[WindowsFrameTheme.HIDE_TITLE])
        assertEquals(1, darkMode)
        assertEquals("Light themes need dark native controls", 0, paperMode)
        assumeTrue("Exact caption colors require Windows 11", result[WindowsFrameTheme.CAPTION_COLOR] == 0)
        // Microsoft documents the color attributes for DwmSetWindowAttribute only. Windows can
        // accept them while rejecting DwmGetWindowAttribute, so check the real native HRESULTs.
        assertEquals(0, result[WindowsFrameTheme.CAPTION_COLOR])
        assertEquals(0, result[WindowsFrameTheme.TEXT_COLOR])
        assertEquals(0, result[WindowsFrameTheme.BORDER_COLOR])
    }
}
