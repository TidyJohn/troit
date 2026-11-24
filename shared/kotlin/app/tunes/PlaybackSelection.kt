package app.tunes

/** A filtered queue and the selected song's index after filtering. */
data class PlaybackSelection(val songs: List<Song>, val index: Int)

/**
 * Apply listening preferences only when starting a queue. Keep the original selection by its index,
 * because a playlist can intentionally contain the same URL more than once.
 */
fun ListeningPreferences.selectPlayback(songs: List<Song>, index: Int): PlaybackSelection? {
    val selected = songs.getOrNull(index) ?: return null
    if (!allows(selected)) return null
    var filteredIndex = 0
    val allowed = songs.filterIndexed { originalIndex, song ->
        val keep = allows(song)
        if (keep && originalIndex < index) filteredIndex++
        keep
    }
    return PlaybackSelection(allowed, filteredIndex)
}
