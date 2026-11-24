package app.tunes

/** Search results keep their position in the complete playable queue, including repeated URLs. */
internal data class PlaylistSearchResult(val queueIndex: Int, val song: Song)

internal fun searchPlaylist(songs: List<Song>, query: String): List<PlaylistSearchResult> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return songs.mapIndexedNotNull { index, song ->
        if (terms.all { term -> song.title.contains(term, ignoreCase = true) || song.artist.contains(term, ignoreCase = true) })
            PlaylistSearchResult(index, song)
        else null
    }
}
