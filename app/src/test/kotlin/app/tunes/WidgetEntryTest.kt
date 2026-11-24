package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetEntryTest {
    @Test fun compactSnapshotPreservesItsOriginalAccountAndCommandNonce() {
        val original = WidgetEntry("account-a", false, "Track", "Artist", "https://example.com/track", true,
            queueIndex = 3, remote = "{\"device_id\":\"pc\"}", nonce = "original-tap")
        assertEquals(original, WidgetEntry.parse(original.json()))
        assertEquals("account-a", WidgetEntry.parse(original.json())?.owner)
        assertEquals("original-tap", WidgetEntry.parse(original.json())?.nonce)
        assertEquals(3, WidgetEntry.parse(original.json())?.queueIndex)
    }

    @Test fun guestPlaybackDoesNotBecomeASignedInAccountSnapshot() {
        val guest = WidgetEntry(null, true, "Track", "Artist", "https://example.com/track", false)
        assertNull(WidgetEntry.parse(guest.json())?.owner)
        assertNull(WidgetEntry.parse("corrupt snapshot"))
        assertNull(WidgetEntry.parse("x".repeat(20_001)))
    }
}
