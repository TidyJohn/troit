package app.tunes

import org.junit.Assert.*
import org.junit.Test

class PlaybackModeTest {
    @Test
    fun completionStopsRepeatsOrRequestsRadioOnlyAtTheBoundary() {
        assertEquals(1, nextPlaybackIndex(PlaybackMode.STOP, 0, 3))
        assertNull(nextPlaybackIndex(PlaybackMode.STOP, 2, 3))
        assertEquals(0, nextPlaybackIndex(PlaybackMode.REPEAT_ALL, 2, 3))
        assertEquals(1, nextPlaybackIndex(PlaybackMode.REPEAT_ONE, 1, 3))
        assertEquals(1, nextPlaybackIndex(PlaybackMode.RADIO, 0, 3))
        assertNull(nextPlaybackIndex(PlaybackMode.RADIO, 2, 3))
        assertEquals(0, nextPlaybackIndex(PlaybackMode.REPEAT_ALL, 0, 1))
        assertEquals(0, nextPlaybackIndex(PlaybackMode.REPEAT_ONE, 0, 1))
        assertNull(nextPlaybackIndex(PlaybackMode.STOP, 0, 1))
    }

    @Test
    fun manualNextBypassesRepeatOneAndInvalidQueuesNeverAdvance() {
        assertEquals(2, nextPlaybackIndex(PlaybackMode.REPEAT_ONE, 1, 3, automatic = false))
        assertNull(nextPlaybackIndex(PlaybackMode.REPEAT_ONE, 2, 3, automatic = false))
        for (mode in PlaybackMode.entries) {
            assertNull(nextPlaybackIndex(mode, 0, 0))
            assertNull(nextPlaybackIndex(mode, -1, 3))
            assertNull(nextPlaybackIndex(mode, 3, 3))
        }
        val end = NowPlaying(listOf(Song("Song", "Artist", "song")), index = 0)
        assertTrue(canPlayNext(PlaybackMode.RADIO, end))
        assertTrue(canPlayNext(PlaybackMode.REPEAT_ALL, end))
        assertFalse(canPlayNext(PlaybackMode.REPEAT_ONE, end))
        assertFalse(canPlayNext(PlaybackMode.STOP, end))
        assertFalse(canPlayNext(PlaybackMode.RADIO, NowPlaying()))
    }
}
