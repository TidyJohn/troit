package app.tunes

import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artists: List<String>, val durationMs: Long)

/** [song] is null if nothing trustworthy was found. [exact] means the length matched too, so it's the same recording. */
data class Match(val track: SpotifyTrack, val song: Song?, val exact: Boolean)

/**
 * Turns Spotify links into playable songs: reads the title, artist and length from Spotify's public embed page,
 * then finds the same recording on YouTube Music. Same technique as the open-source spotDL.
 * Every call blocks on the network: keep it off the main thread.
 */
object Spotify {
    const val IMPORT_LIMIT = 1_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val EXACT_SECONDS = 5L
    private const val CLOSE_SECONDS = 45L // music videos often run a little longer than the track

    private val link = Regex("(?:open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?|spotify:)(track|album|playlist)[/:]([A-Za-z0-9]{22})")
    private val nextData = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.+?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val brackets = Regex("[(\\[].*?[)\\]]")
    private val notAlphanumeric = Regex("[^\\p{L}\\p{N}]+")
    // Whole words only: "live" must not trip on "Oliver" or "Alive".
    private val otherVersion = Regex(
        "\\b(karaoke|instrumental|cover|tribute|made popular by|in the style of|slowed|sped up|nightcore|8d|reverb|remix|live)\\b",
    )
    private val coverLabels = setOf("karaoke", "tribute")

    /** Every Spotify link in [text], as (type, id), in order and without repeats. */
    fun links(text: String): List<Pair<String, String>> =
        link.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()

    fun tracks(type: String, id: String): List<SpotifyTrack> {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$type/$id")
            .header("User-Agent", USER_AGENT)
            .build()
        val html = Yt.http.newCall(request).execute().use {
            if (!it.isSuccessful) error("Spotify returned HTTP ${it.code}")
            it.body!!.string()
        }
        return parseEmbed(html).tracks
    }

    internal fun parseEmbed(html: String): SpotifyImportSource {
        val data = nextData.find(html)?.groupValues?.get(1) ?: error("Couldn't read this Spotify page")
        val entity = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: error("This Spotify link isn't available")
        val trackList = entity.optJSONArray("trackList")
        if (trackList != null) {
            val tracks = (0 until minOf(IMPORT_LIMIT, trackList.length())).mapNotNull(trackList::optJSONObject).map {
                // Lists give all the artists as one string, separated by commas.
                val artists = it.optString("subtitle").split(',').map(String::trim).filter(String::isNotEmpty)
                SpotifyTrack(it.optString("title"), artists, it.optLong("duration"))
            }.filter { it.title.isNotBlank() && it.artists.isNotEmpty() }
            val warning = if (entity.optString("type").equals("playlist", true) ||
                entity.optString("uri").startsWith("spotify:playlist:"))
                listOf("Spotify's public playlist preview may omit songs beyond its first 100. Connect Spotify to import a full playlist you own or collaborate on, up to 1,000 songs.")
            else emptyList()
            return SpotifyImportSource(tracks, warning)
        }
        val artists = entity.optJSONArray("artists")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
        }.orEmpty()
        val track = SpotifyTrack(entity.optString("name"), artists, entity.optLong("duration"))
        return SpotifyImportSource(listOf(track).filter { it.title.isNotBlank() && it.artists.isNotEmpty() })
    }

    /** Official songs first. Falls back to videos, which also holds what isn't officially released. */
    fun match(track: SpotifyTrack): Match {
        val query = "${track.artists.firstOrNull().orEmpty()} ${track.title}"
        val song = best(track, Yt.search(query, Yt.SONGS).filterIsInstance<Song>())
        if (song != null && isExact(track, song)) return Match(track, song, true)
        val fallback = song ?: best(track, Yt.search(query, Yt.VIDEOS).filterIsInstance<Song>())
        return Match(track, fallback, fallback != null && isExact(track, fallback))
    }

    class Imported(val songs: List<Song>, val notFound: List<String>, val warnings: List<String> = emptyList())

    /** Blocking compatibility entry point. The UI uses the same resumable two-worker pipeline directly. */
    fun import(text: String, onProgress: (done: Int, total: Int) -> Unit): Imported = kotlinx.coroutines.runBlocking {
        prepareSpotifyImport(text).run(onProgress)
    }

    /** The candidate closest in length, out of those that really are this track. */
    internal fun best(track: SpotifyTrack, candidates: List<Song>): Song? =
        candidates.filter { isSameTrack(track, it) }.minByOrNull { secondsOff(track, it) ?: CLOSE_SECONDS }

    internal fun isSameTrack(track: SpotifyTrack, song: Song): Boolean {
        val want = squash(brackets.replace(track.title, " ").substringBefore(" - ")).ifEmpty { squash(track.title) }
        val got = squash(song.title)
        if (want !in got) return false

        // The artist counts too: karaoke labels put "Karaoke" in the artist name and keep a clean title.
        val versions = versions(song.title) + (versions(song.artist) intersect coverLabels)
        if ((versions - versions(track.title)).isNotEmpty()) return false

        val artist = squash(song.artist)
        val artistFound = track.artists.map(::squash).filter { it.isNotEmpty() }
            .any { it in artist || artist in it && artist.isNotEmpty() || it in got }
        if (!artistFound) return false

        return (secondsOff(track, song) ?: 0L) <= CLOSE_SECONDS
    }

    private fun isExact(track: SpotifyTrack, song: Song) = secondsOff(track, song)?.let { it <= EXACT_SECONDS } ?: false

    /** Null when the candidate's length isn't known. */
    private fun secondsOff(track: SpotifyTrack, song: Song): Long? =
        song.durationSeconds.takeIf { it > 0 }?.let { abs(it - track.durationMs / 1000) }

    private fun squash(text: String) = text.lowercase().replace(notAlphanumeric, "")

    private fun versions(text: String) = otherVersion.findAll(text.lowercase()).map { it.value }.toSet()
}
