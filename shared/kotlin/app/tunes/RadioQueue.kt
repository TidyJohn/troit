package app.tunes

/** Lightweight duplicate protection: no artwork, track metadata, or audio URLs are kept in history. */
data class RadioExclusions(val ids: Set<String>, val recordings: Set<String>) {
    fun contains(song: Song): Boolean = radioSongId(song) in ids || PlaylistRadioClient.trackKey(song) in recordings

    companion object {
        val Empty = RadioExclusions(emptySet(), emptySet())
        fun from(songs: List<Song>) = RadioExclusions(
            songs.mapTo(hashSetOf(), ::radioSongId), songs.mapTo(hashSetOf()) { PlaylistRadioClient.trackKey(it) })
    }
}

internal fun radioSongId(song: Song): String = PlaylistRadioClient.videoId(song.url) ?: song.url

/** All session state is bounded, including sessions that run for days. Used by both native players. */
internal class RadioQueueSession {
    var seeds: List<Song> = emptyList()
        private set
    private val original = LinkedHashMap<String, String>()
    private val recent = LinkedHashMap<String, String>()
    internal val originalIdentityCount get() = original.size
    internal val recentIdentityCount get() = recent.size

    fun reset(queue: List<Song>) {
        seeds = PlaylistRadioClient.seeds(queue).ifEmpty { queue.distinctBy { it.artist }.take(3) }
            .map { it.copy(artwork = null) }
        original.clear()
        recent.clear()
        queue.take(MAX_IDENTITIES).forEach { original[radioSongId(it)] = PlaylistRadioClient.trackKey(it) }
    }

    fun exclusions(queue: List<Song>): RadioExclusions {
        val ids = HashSet<String>(original.size + recent.size + queue.size)
        val recordings = HashSet<String>(original.size + recent.size + queue.size)
        ids.addAll(original.keys); ids.addAll(recent.keys)
        recordings.addAll(original.values); recordings.addAll(recent.values)
        queue.forEach { ids += radioSongId(it); recordings += PlaylistRadioClient.trackKey(it) }
        return RadioExclusions(ids, recordings)
    }

    fun remember(songs: List<Song>) {
        songs.forEach { song ->
            val id = radioSongId(song)
            recent.remove(id)
            recent[id] = PlaylistRadioClient.trackKey(song)
        }
        while (recent.size > MAX_IDENTITIES) recent.remove(recent.keys.first())
    }

    companion object {
        const val MAX_IDENTITIES = 1_000
        const val PLAYED_TAIL = 40
        const val MAX_BATCH = 20
    }
}

internal data class RadioQueueAppend(val songs: List<Song>, val nextIndex: Int)

/** Trim only already-played history. Upcoming songs in a large user queue are never discarded. */
internal fun appendRadioQueue(queue: List<Song>, index: Int, discoveries: List<Song>): RadioQueueAppend {
    require(index in queue.indices)
    val start = (index + 1 - RadioQueueSession.PLAYED_TAIL).coerceAtLeast(0)
    val retained = queue.subList(start, queue.size)
    return RadioQueueAppend(retained + discoveries.take(RadioQueueSession.MAX_BATCH), index - start + 1)
}
