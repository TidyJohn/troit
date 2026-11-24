package app.tunes

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/**
 * Keyboard and headset media keys, also while the window is minimized or unfocused.
 * ponytail: RegisterHotKey takes the keys away from other players while a Troit song is loaded. System Media
 * Transport Controls (shared keys plus the Windows media overlay) is the upgrade path; it needs WinRT.
 */
internal class WindowsMediaKeys(private val player: MusicPlayer, private val keys: Map<Int, Int> = KEYS) : AutoCloseable {
    @Volatile private var running = true

    init {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Thread(::loop, "troit-media-keys").apply { isDaemon = true }.start()
    }

    override fun close() { running = false }

    /** Hot keys post to the thread that registered them, so registration and reading stay on this one thread. */
    private fun loop() {
        val user32 = runCatching { Native.load("user32", User32::class.java) }.getOrNull() ?: return
        val message = Memory(64) // MSG; wParam (the hot key id) follows the window handle and message id
        var registered = false
        try {
            while (running) {
                val wanted = player.now.song != null
                if (wanted != registered) {
                    keys.forEach { (id, key) -> if (wanted) user32.RegisterHotKey(null, id, 0, key) else user32.UnregisterHotKey(null, id) }
                    registered = wanted
                }
                while (user32.PeekMessageW(message, null, WM_HOTKEY, WM_HOTKEY, 1)) {
                    when (message.getInt(2L * Native.POINTER_SIZE)) {
                        PLAY_PAUSE -> player.toggle()
                        NEXT -> player.next()
                        PREVIOUS -> player.previous()
                    }
                }
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
        } finally { if (registered) keys.keys.forEach { user32.UnregisterHotKey(null, it) } }
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Boolean
        fun UnregisterHotKey(window: Pointer?, id: Int): Boolean
        fun PeekMessageW(message: Pointer, window: Pointer?, first: Int, last: Int, remove: Int): Boolean
    }

    internal companion object {
        private const val WM_HOTKEY = 0x0312
        const val PLAY_PAUSE = 1
        const val NEXT = 2
        const val PREVIOUS = 3
        /** Hot key id to VK_MEDIA_PLAY_PAUSE, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PREV_TRACK. */
        val KEYS = mapOf(PLAY_PAUSE to 0xB3, NEXT to 0xB0, PREVIOUS to 0xB1)
    }
}
