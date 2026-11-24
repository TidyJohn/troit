package app.tunes

/** End-of-queue behavior is chosen explicitly and remembered on each device. */
enum class PlaybackMode(val label: String, val description: String, val icon: String) {
    STOP("Stop at end", "Stop when the last song finishes.", "stop"),
    REPEAT_ALL("Repeat playlist", "Play the queue again from the beginning.", "repeat"),
    REPEAT_ONE("Repeat song", "Keep playing the current song.", "repeat_one"),
    RADIO("Similar music", "Continue with new music inspired by this playlist.", "radio"),
}

/** Manual Next bypasses repeat-one, while automatic completion repeats the current song. */
internal fun nextPlaybackIndex(mode: PlaybackMode, index: Int, size: Int, automatic: Boolean = true): Int? {
    if (size <= 0 || index !in 0 until size) return null
    if (automatic && mode == PlaybackMode.REPEAT_ONE) return index
    if (index < size - 1) return index + 1
    return if (mode == PlaybackMode.REPEAT_ALL) 0 else null
}

internal fun canPlayNext(mode: PlaybackMode, now: NowPlaying): Boolean = now.song != null &&
    (nextPlaybackIndex(mode, now.index, now.queue.size, automatic = false) != null || mode == PlaybackMode.RADIO)
