package app.tunes

/** The home-screen controller watches quickly while useful, without waking a sleeping phone. */
internal object LiveWidgetPolicy {
    fun nextDelayMs(
        interactive: Boolean,
        localPlaying: Boolean,
        networkAvailable: Boolean,
        remoteFresh: Boolean,
        remotePlaying: Boolean,
        unsuccessfulReads: Int,
    ): Long = when {
        !interactive || localPlaying -> 30_000L
        !networkAvailable -> 60_000L
        unsuccessfulReads > 0 -> when (unsuccessfulReads) {
            1 -> 5_000L
            2 -> 15_000L
            3 -> 30_000L
            else -> 60_000L
        }
        remoteFresh && remotePlaying -> 2_000L
        remoteFresh -> 5_000L
        else -> 5_000L
    }
}
