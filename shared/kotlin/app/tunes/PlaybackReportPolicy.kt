package app.tunes

/**
 * A restored paused queue is history, not a claim to the account's current playback.
 * Once this app instance observes local playback, report changes and active-playback heartbeats.
 */
internal class PlaybackReportPolicy {
    private var observedPlaying = false
    private var lastReported: Pair<String, Boolean>? = null
    private var lastSentAt = 0L

    fun shouldReport(now: NowPlaying, timeMs: Long): Boolean {
        val song = now.song ?: return false
        if (now.playing) observedPlaying = true
        if (!observedPlaying) return false

        val state = song.url to now.playing
        if (state == lastReported && (!now.playing || timeMs - lastSentAt < 30_000)) return false
        lastReported = state
        lastSentAt = timeMs
        return true
    }
}
