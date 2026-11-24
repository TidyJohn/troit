package app.tunes

/**
 * Songs and albums travel through chat as ordinary text messages, so no server change is needed and an
 * older app still shows a readable title with a link.
 */
internal object SongShare {
    private val videoId = Regex("""^https://(?:music|www)\.youtube\.com/watch\?(?:[^#\s]*&)?v=([A-Za-z0-9_-]{6,20})(?:&[^#\s]*)?$""")
    private val browsePage = Regex("""^https://music\.youtube\.com/browse/([A-Za-z0-9_-]{2,100})$""")
    private const val SONG = "🎵 "
    private const val ALBUM = "💿 "
    private const val BY = " — "

    /** The one link shape the app plays; anything else a server or a friend sends is not a song. */
    fun songUrl(url: String): String? = videoId.matchEntire(url)?.let { "https://music.youtube.com/watch?v=${it.groupValues[1]}" }

    fun text(item: Item): String? = when (item) {
        is Song -> songUrl(item.url)?.let { "$SONG${clean(item.title)}$BY${clean(item.artist)}\n$it" }
        is Playlist -> "https://music.youtube.com/browse/${item.browseId}".takeIf(browsePage::matches)?.let { "$ALBUM${clean(item.title)}\n$it" }
        else -> null
    }

    /** Null for ordinary messages. Only text in exactly the shape [text] writes becomes a card. */
    fun parse(text: String): Item? {
        val lines = text.trim().split('\n')
        if (lines.size != 2) return null
        val (label, link) = lines
        return when {
            label.startsWith(SONG) -> videoId.matchEntire(link)?.let { match ->
                val name = label.removePrefix(SONG)
                val split = name.lastIndexOf(BY)
                if (split <= 0) null else Song(name.substring(0, split), name.substring(split + BY.length), checkNotNull(songUrl(link)),
                    "https://i.ytimg.com/vi/${match.groupValues[1]}/hqdefault.jpg")
            }
            label.startsWith(ALBUM) -> browsePage.matchEntire(link)?.let { Playlist(label.removePrefix(ALBUM), "", it.groupValues[1]) }
            else -> null
        }
    }

    private fun clean(value: String) = value.replace('\n', ' ').trim().take(300).ifEmpty { "Unknown" }
}
