package app.tunes

import androidx.compose.runtime.Immutable

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers

/** Anything the app can show as a row or a tile. */
sealed interface Item {
    val title: String
    val subtitle: String
    val artwork: String?
}

@Immutable
data class Song(
    override val title: String,
    val artist: String,
    val url: String,
    override val artwork: String? = null,
    val durationSeconds: Long = 0, // 0 when unknown
) : Item {
    override val subtitle get() = artist
}

/** A playlist or an album: both open the same kind of page. */
@Immutable
data class Playlist(
    override val title: String,
    override val subtitle: String,
    val browseId: String,
    override val artwork: String? = null,
) : Item

@Immutable
data class Artist(
    override val title: String,
    override val subtitle: String,
    val browseId: String,
    override val artwork: String? = null,
) : Item

/** A mood or genre. [color] is an ARGB accent, 0 if there is none. */
@Immutable
data class Category(
    override val title: String,
    val browseId: String,
    val params: String?,
    val color: Long,
) : Item {
    override val subtitle get() = ""
    override val artwork get() = null
}

/** [more] is the page behind this shelf's "See all", if it has one. */
class Section(val title: String, val items: List<Item>, val more: Item? = null)

/**
 * YouTube Music's own curated catalogue: home feed, new releases, charts, moods, albums, artists.
 * Talks to the same private API as music.youtube.com, the approach used by the open-source ytmusicapi and Metrolist.
 * Every call blocks on the network: keep it off the main thread.
 */
object Music {
    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
    private const val ORIGIN = "https://music.youtube.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    // If every feed starts failing, try a newer version: music.youtube.com shows it as INNERTUBE_CLIENT_VERSION.
    private const val CLIENT_VERSION = "1.20260101.01.00"
    private const val MAX_FEED_PAGES = 1 // Keep initial discovery quick; every shelf still has its full page.
    private const val MAX_PLAYLIST_PAGES = 5 // about 500 songs

    private val json = "application/json; charset=utf-8".toMediaType()
    private val imageSize = Regex("=w\\d+-h\\d+")

    // Anonymous session id from YouTube. Later feed pages come back empty without it.
    // A continuation belongs to one anonymous session. Parallel feeds must not replace its visitor id.
    private val visitorData = ThreadLocal.withInitial { mutableMapOf<String, String>() }
    /** An optional market selects the discovery feed; it is not a song-origin filter. */
    private fun post(fields: JSONObject, preferences: ListeningPreferences): JSONObject {
        val marketKey = preferences.market.orEmpty()
        val sessions = requireNotNull(visitorData.get())
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "en")
        preferences.market?.let { client.put("gl", it) }
        sessions[marketKey]?.let { client.put("visitorData", it) }
        val body = fields.put("context", JSONObject().put("client", client)).toString().toRequestBody(json)
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .build()
        val response = Yt.http.newCall(request).execute().use {
            if (!it.isSuccessful) error("YouTube Music returned HTTP ${it.code}")
            JSONObject(it.body!!.string())
        }
        response.optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotEmpty() }?.let { sessions[marketKey] = it }
        return response
    }

    private fun browse(browseId: String, params: String? = null, preferences: ListeningPreferences): JSONObject {
        val fields = JSONObject().put("browseId", browseId)
        params?.let { fields.put("params", it) }
        return post(fields, preferences)
    }

    /** The Home screen: personalised-by-region feed, then new releases, trending and charts. */
    fun home(preferences: ListeningPreferences = ListeningPreferences()): List<Section> =
        runBlocking { homeFeed(preferences).last() }

    internal fun homeFeed(preferences: ListeningPreferences): Flow<List<Section>> = progressiveHome(preferences, listOf(
        { regionalHome(preferences) },
        { sections("FEmusic_explore", preferences = preferences) },
        { sections("FEmusic_charts", preferences = preferences) },
    ))

    private fun regionalHome(preferences: ListeningPreferences): List<Section> {
        val first = sectionList(browse("FEmusic_home", preferences = preferences))
        val feed = first?.list("contents").orEmpty().toMutableList()
        var token = first?.nextToken()
        var pages = 0
        while (token != null && pages++ < MAX_FEED_PAGES) {
            val more = catalogueResult {
                post(JSONObject().put("continuation", token), preferences)
                    .path("continuationContents", "sectionListContinuation")
            }.getOrNull() ?: break
            feed += more.list("contents")
            token = more.nextToken()
        }
        return parseSections(feed)
    }

    /** Publish playable shelves as each request finishes. Preserve catalogue priority in the final feed. */
    internal fun progressiveHome(preferences: ListeningPreferences, sources: List<() -> List<Section>>): Flow<List<Section>> = flow {
        val feeds = arrayOfNulls<Result<List<Section>>>(sources.size)
        channelFlow {
            sources.forEachIndexed { index, source ->
                launch {
                    val result = catalogueResult { runInterruptible(Dispatchers.IO) { source() } }
                    send(index to result)
                }
            }
        }.collect { (index, result) ->
            feeds[index] = result
            val complete = feeds.all { it != null }
            if (complete || result.isSuccess) {
                val sections = mergeHomeFeeds(feeds.filterNotNull(), preferences)
                if (complete || sections.isNotEmpty()) emit(sections)
            }
        }
    }

    /** A supplemental catalogue can fail without turning successful discovery into an error screen. */
    internal fun mergeHomeFeeds(feeds: List<Result<List<Section>>>, preferences: ListeningPreferences): List<Section> {
        if (feeds.isNotEmpty() && feeds.all { it.isFailure }) throw feeds.first().exceptionOrNull()!!
        return preferences.filterSections(feeds.flatMap { it.getOrDefault(emptyList()) })
            .filter { it.title.isNotBlank() && it.items.none { item -> item is Category } }
            .distinctBy { it.title }
    }

    private inline fun <T> catalogueResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        when (error) {
            is CancellationException -> throw error
            is InterruptedException -> { Thread.currentThread().interrupt(); throw error }
            else -> Result.failure(error)
        }
    }

    /** The grid of moods and genres. */
    fun categories(preferences: ListeningPreferences = ListeningPreferences()): List<Section> = sections("FEmusic_moods_and_genres", preferences = preferences)

    /** The shelves on an artist, mood or genre page. */
    fun sections(browseId: String, params: String? = null, preferences: ListeningPreferences = ListeningPreferences()): List<Section> =
        preferences.filterSections(parseSections(sectionList(browse(browseId, params, preferences))?.list("contents").orEmpty()))

    /** Every song in an album or playlist. */
    fun songsIn(browseId: String, preferences: ListeningPreferences = ListeningPreferences()): List<Song> {
        val page = browse(browseId, preferences = preferences).path("contents", "twoColumnBrowseResultsRenderer")
            ?: error("This album or playlist isn't available")
        val header = page.list("tabs").firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")
            ?.list("contents")?.firstOrNull()?.optJSONObject("musicResponsiveHeaderRenderer")
        val shelf = page.path("secondaryContents", "sectionListRenderer")?.list("contents")?.firstOrNull()
            ?.let { it.optJSONObject("musicPlaylistShelfRenderer") ?: it.optJSONObject("musicShelfRenderer") }
            ?: return emptyList()

        val rows = shelf.list("contents").toMutableList()
        var pages = 0
        while (pages++ < MAX_PLAYLIST_PAGES) {
            val token = rows.lastOrNull()
                ?.path("continuationItemRenderer", "continuationEndpoint", "continuationCommand")
                ?.optString("token")?.takeIf { it.isNotEmpty() } ?: break
            rows.removeAt(rows.lastIndex)
            val next = post(JSONObject().put("continuation", token), preferences)
                .list("onResponseReceivedActions").firstOrNull()
                ?.optJSONObject("appendContinuationItemsAction")?.list("continuationItems").orEmpty()
            if (next.isEmpty()) break
            rows += next
        }

        // Album tracks leave out the cover and artist, because the album header already shows them.
        val albumArtist = header?.optJSONObject("straplineTextOne").text()
        val albumArt = header?.optJSONObject("thumbnail").art()
        return rows.mapNotNull(::parseItem).filterIsInstance<Song>().map {
            it.copy(artist = it.artist.ifEmpty { albumArtist }, artwork = it.artwork ?: albumArt)
        }.filter(preferences::allows)
    }

    /** Asks the image server for a size that stays sharp on a tile. URLs without a size are left alone. */
    fun sharpen(url: String) = url.replace(imageSize, "=w544-h544")

    private fun sectionList(response: JSONObject): JSONObject? =
        response.path("contents", "singleColumnBrowseResultsRenderer")?.list("tabs")?.firstOrNull()
            ?.path("tabRenderer", "content", "sectionListRenderer")

    internal fun parseSections(contents: List<JSONObject>): List<Section> = contents.mapNotNull { section ->
        val carousel = section.optJSONObject("musicCarouselShelfRenderer")
        val shelf = section.optJSONObject("musicShelfRenderer")
        val grid = section.optJSONObject("gridRenderer")
        val carouselHeader = carousel?.path("header", "musicCarouselShelfBasicHeaderRenderer")
        val titleObject = when {
            carousel != null -> carouselHeader?.optJSONObject("title")
            shelf != null -> shelf.optJSONObject("title")
            grid != null -> grid.path("header", "gridHeaderRenderer")?.optJSONObject("title")
            else -> return@mapNotNull null
        }
        val rows = carousel?.list("contents") ?: shelf?.list("contents") ?: grid?.list("items").orEmpty()
        val title = titleObject.text()
        val items = rows.mapNotNull(::parseItem)
        // This is an audio-only app, so shelves that are nothing but music videos go.
        val videoShelf = title.contains("video", ignoreCase = true) && items.all { it is Song }
        val target = carouselHeader?.path("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")
            ?: shelf?.path("bottomEndpoint", "browseEndpoint")
            ?: titleObject?.list("runs")?.firstOrNull()?.path("navigationEndpoint", "browseEndpoint")
        if (items.isEmpty() || videoShelf) null else Section(title, items, target?.let { seeAll(title, it) })
    }

    private fun seeAll(title: String, target: JSONObject): Item? {
        val browseId = target.optString("browseId").takeIf { it.isNotEmpty() } ?: return null
        return if (browseId.startsWith("VL")) {
            Playlist(title, "", browseId)
        } else {
            Category(title, browseId, target.optString("params").takeIf { it.isNotEmpty() }, 0)
        }
    }

    /** Null for anything the app can't open: podcasts, unavailable tracks, unknown row types. */
    internal fun parseItem(row: JSONObject): Item? {
        row.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
            return toItem(
                title = r.optJSONObject("title").text(),
                subtitle = r.optJSONObject("subtitle").text(),
                artwork = r.optJSONObject("thumbnailRenderer").art(),
                nav = r.optJSONObject("navigationEndpoint"),
                videoId = null,
            )
        }
        row.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
            val columns = r.list("flexColumns").map {
                it.path("musicResponsiveListItemFlexColumnRenderer", "text")
            }
            val titleRun = columns.firstOrNull()?.list("runs")?.firstOrNull()
            return toItem(
                title = columns.firstOrNull().text(),
                subtitle = columns.getOrNull(1).text(),
                artwork = r.optJSONObject("thumbnail").art(),
                nav = r.optJSONObject("navigationEndpoint") ?: titleRun?.optJSONObject("navigationEndpoint"),
                videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() },
            )
        }
        row.optJSONObject("musicNavigationButtonRenderer")?.let { r ->
            val target = r.path("clickCommand", "browseEndpoint") ?: return null
            return Category(
                title = r.optJSONObject("buttonText").text(),
                browseId = target.optString("browseId"),
                params = target.optString("params").takeIf { it.isNotEmpty() },
                color = r.path("solid")?.optLong("leftStripeColor") ?: 0,
            )
        }
        return null
    }

    private fun toItem(title: String, subtitle: String, artwork: String?, nav: JSONObject?, videoId: String?): Item? {
        val id = videoId ?: nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotEmpty() }
        if (id != null) {
            return Song(title, subtitle.substringBefore(" • "), "https://music.youtube.com/watch?v=$id", artwork)
        }
        val target = nav?.optJSONObject("browseEndpoint") ?: return null
        val browseId = target.optString("browseId").takeIf { it.isNotEmpty() } ?: return null
        val pageType = target.path("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig")
            ?.optString("pageType")
        return when (pageType) {
            "MUSIC_PAGE_TYPE_ALBUM", "MUSIC_PAGE_TYPE_PLAYLIST" -> Playlist(title, subtitle, browseId, artwork)
            "MUSIC_PAGE_TYPE_ARTIST" -> Artist(title, subtitle, browseId, artwork)
            else -> null
        }
    }

    private fun JSONObject.path(vararg keys: String): JSONObject? =
        keys.fold(this as JSONObject?) { current, key -> current?.optJSONObject(key) }

    private fun JSONObject.list(key: String): List<JSONObject> =
        optJSONArray(key)?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }.orEmpty()

    private fun JSONObject.nextToken(): String? =
        list("continuations").firstOrNull()?.optJSONObject("nextContinuationData")
            ?.optString("continuation")?.takeIf { it.isNotEmpty() }

    private fun JSONObject?.text(): String =
        this?.list("runs")?.joinToString("") { it.optString("text") }.orEmpty()

    private fun JSONObject?.art(): String? =
        this?.path("musicThumbnailRenderer", "thumbnail")?.list("thumbnails")
            ?.maxByOrNull { it.optInt("width") }?.optString("url")
            ?.takeIf { it.isNotEmpty() }?.let(::sharpen)
}
