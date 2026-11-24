package app.tunes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportPolicyTest {
    private fun now(title: String = "Song", playing: Boolean = false) =
        NowPlaying(listOf(Song(title, "Artist", title)), 0, playing = playing)

    @Test
    fun pausedHistoryNeverClaimsPlaybackEvenWhenRestoredAsynchronously() {
        val reports = PlaybackReportPolicy()
        assertFalse(reports.shouldReport(NowPlaying(), 0))
        assertFalse(reports.shouldReport(now("Old phone song"), 2_000))
        assertFalse(reports.shouldReport(now("Another restored song"), 4_000))
        assertFalse(reports.shouldReport(now("Another restored song"), 60_000))
    }

    @Test
    fun activePlaybackOnReopenReportsImmediatelyThenEveryThirtySeconds() {
        val reports = PlaybackReportPolicy()
        assertTrue(reports.shouldReport(now(playing = true), 0))
        assertFalse(reports.shouldReport(now(playing = true), 29_999))
        assertTrue(reports.shouldReport(now(playing = true), 30_000))
        assertFalse(reports.shouldReport(now(playing = true), 32_000))
    }

    @Test
    fun realPlaybackAfterRestorationReportsTrackAndPauseChanges() {
        val reports = PlaybackReportPolicy()
        assertFalse(reports.shouldReport(now("Old phone song"), 0))
        assertTrue(reports.shouldReport(now("New song", playing = true), 2_000))
        assertTrue(reports.shouldReport(now("New song"), 4_000))
        assertFalse(reports.shouldReport(now("New song"), 60_000))
        assertTrue(reports.shouldReport(now("New song", playing = true), 62_000))
        assertTrue(reports.shouldReport(now("Next song", playing = true), 64_000))
        assertFalse(reports.shouldReport(NowPlaying(), 66_000))
    }
}
