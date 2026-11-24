package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveWidgetPolicyTest {
    @Test fun screenOffAndLocalPlaybackDoNotUseTheFastRemoteLoop() {
        assertEquals(30_000L, delay(interactive = false))
        assertEquals(30_000L, delay(localPlaying = true))
    }

    @Test fun liveRemoteTracksRefreshQuicklyAndPausedSessionsUseLessWork() {
        assertEquals(2_000L, delay())
        assertEquals(5_000L, delay(remotePlaying = false))
    }

    @Test fun missingOrUnreachableDevicesBackOffToABoundedMinute() {
        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L), (1..5).map { delay(unsuccessfulReads = it) })
        assertEquals(60_000L, delay(networkAvailable = false))
        assertEquals(2_000L, delay(unsuccessfulReads = 0))
    }

    private fun delay(interactive: Boolean = true, localPlaying: Boolean = false, networkAvailable: Boolean = true,
        remoteFresh: Boolean = true, remotePlaying: Boolean = true, unsuccessfulReads: Int = 0) =
        LiveWidgetPolicy.nextDelayMs(interactive, localPlaying, networkAvailable, remoteFresh, remotePlaying, unsuccessfulReads)
}
