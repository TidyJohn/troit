package app.tunes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Search uses one cancellable catalogue request instead of the extractor's suggestion bootstrap. */
object MusicSearch {
    class Page internal constructor(val items: List<Item>, internal val videoTypes: Map<String, String>, val more: (suspend () -> Page)?) {
        constructor(items: List<Item>, more: (suspend () -> Page)? = null) : this(items, emptyMap(), more)
    }

    private val client by lazy { MusicSearchClient(Yt.http) }

    suspend fun searchPage(query: String, filter: String, preferences: ListeningPreferences = ListeningPreferences()): Page =
        client.searchPage(query, filter, preferences)
}

/** Raw first pages are cached briefly; exclusions are applied afresh on every read. */
internal class MusicSearchClient(
    http: OkHttpClient,
    private val endpoint: HttpUrl = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false".toHttpUrl(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val cacheSize: Int = 32,
    private val cacheTtlMs: Long = 5 * 60_000L,
) {
    private data class Key(val query: String, val filter: String, val market: String?)
    private data class RawPage(val items: List<Item>, val token: String?, val visitor: String?, val videoTypes: Map<String, String>)
    private data class Cached(val at: Long, val page: RawPage)

    private val http = http.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val cache = object : LinkedHashMap<Key, Cached>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>?) = size > cacheSize
    }

    internal val cachedPages: Int get() = synchronized(cache) { cache.size }

    suspend fun searchPage(query: String, filter: String, preferences: ListeningPreferences = ListeningPreferences()): MusicSearch.Page {
        val normalized = query.trim().replace(whitespace, " ")
        require(normalized.isNotBlank() && normalized.length <= 256) { "Enter a search of up to 256 characters." }
        require(filter in filters) { "This search category is not supported." }
        val key = Key(normalized.lowercase(Locale.ROOT), filter, preferences.market)
        val cached = synchronized(cache) {
            cache[key]?.takeIf { clockMs() - it.at in 0 until cacheTtlMs }?.page.also {
                if (it == null) cache.remove(key)
            }
        }
        val page = cached ?: fetch(normalized, filter, preferences.market, null, null).also { raw ->
            synchronized(cache) { cache[key] = Cached(clockMs(), raw) }
        }
        return filtered(page, normalized, filter, preferences)
    }

    private fun filtered(raw: RawPage, query: String, filter: String, preferences: ListeningPreferences): MusicSearch.Page {
        val more: (suspend () -> MusicSearch.Page)? = raw.token?.let { token ->
            suspend { filtered(fetch(query, filter, preferences.market, token, raw.visitor), query, filter, preferences) }
        }
        return MusicSearch.Page(preferences.filterItems(raw.items), raw.videoTypes, more)
    }

    private suspend fun fetch(query: String, filter: String, market: String?, continuation: String?, visitor: String?): RawPage {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en")
        market?.let { client.put("gl", it) }
        visitor?.let { client.put("visitorData", it) }
        val fields = JSONObject().put("context", JSONObject().put("client", client))
            .put("query", query).put("params", filters.getValue(filter))
        val url = endpoint.newBuilder().apply {
            continuation?.let { addQueryParameter("ctoken", it); addQueryParameter("continuation", it) }
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { visitor?.let { header("X-Goog-Visitor-Id", it) } }
            .post(fields.toString().toRequestBody(json)).build()
        val response = execute(request)
        return withContext(Dispatchers.Default) { parse(JSONObject(response), filter, visitor) }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        // A new query also cancels a response body that is still being downloaded.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException(when (it.code) {
                            429 -> "Music search is being rate limited. Please wait a moment and retry."
                            else -> "Music search returned HTTP ${it.code}. Please try again."
                        })
                        val body = it.body ?: throw IOException("Music search returned an empty response.")
                        val source = body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("Music search returned too much data. Please try again.")
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        private const val CLIENT_VERSION = "1.20260101.01.00"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private const val MAX_RESPONSE_BYTES = 2_000_000L
        private val json = "application/json; charset=utf-8".toMediaType()
        private val whitespace = Regex("\\s+")
        private val durationPattern = Regex("\\d{1,3}:\\d{2}(?::\\d{2})?")

        // Public-catalogue filters used by ytmusicapi; no account cookies or suggestion call needed.
        internal val filters = mapOf(
            Yt.SONGS to "EgWKAQIIAWoMEA4QChADEAQQCRAF",
            Yt.VIDEOS to "EgWKAQIQAWoMEA4QChADEAQQCRAF",
            Yt.ALBUMS to "EgWKAQIYAWoMEA4QChADEAQQCRAF",
            Yt.ARTISTS to "EgWKAQIgAWoMEA4QChADEAQQCRAF",
            Yt.PLAYLISTS to "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D",
        )

        private fun parse(response: JSONObject, filter: String, previousVisitor: String?): RawPage {
            response.optJSONObject("error")?.let { throw IOException("Music search is unavailable. Please try again.") }
            val shelves = ArrayList<JSONObject>()
            val directRows = ArrayList<JSONObject>()
            var recognized = false
            response.path("continuationContents", "musicShelfContinuation")?.let { shelves += it; recognized = true }
            response.path("contents", "tabbedSearchResultsRenderer")?.list("tabs")?.firstOrNull()
                ?.path("tabRenderer", "content", "sectionListRenderer")?.let { section ->
                    recognized = true
                    collectShelves(section.list("contents"), shelves, directRows)
                }
            response.path("contents", "sectionListRenderer")?.let { section ->
                recognized = true
                collectShelves(section.list("contents"), shelves, directRows)
            }
            for (name in listOf("onResponseReceivedActions", "onResponseReceivedCommands")) {
                response.list(name).forEach { action ->
                    action.optJSONObject("appendContinuationItemsAction")?.let { appended ->
                        recognized = true
                        directRows += appended.list("continuationItems")
                    }
                }
            }
            if (!recognized) throw IOException("Music search returned an unfamiliar response. Please try again.")
            val items = ArrayList<Item>()
            val videoTypes = mutableMapOf<String, String>()
            var token: String? = null
            for (shelf in shelves) {
                val rows = shelf.list("contents")
                val parsed = rows.mapNotNull { parseItem(it, filter, videoTypes) }
                items += parsed
                if (token == null) token = shelf.nextToken() ?: rowToken(rows)
            }
            if (directRows.isNotEmpty()) {
                items += directRows.mapNotNull { parseItem(it, filter, videoTypes) }
                token = token ?: rowToken(directRows)
            }
            require(items.size <= 100) { "Unexpected music search page size." }
            val visitor = response.path("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank) ?: previousVisitor
            return RawPage(items.toList(), token, visitor, videoTypes)
        }

        private fun collectShelves(contents: List<JSONObject>, shelves: MutableList<JSONObject>, directRows: MutableList<JSONObject>) {
            for (section in contents) {
                section.optJSONObject("musicShelfRenderer")?.let { shelves += it }
                section.optJSONObject("itemSectionRenderer")?.let { directRows += it.list("contents") }
                section.optJSONObject("musicCardShelfRenderer")?.let { directRows += it.list("contents") }
            }
        }

        private fun parseItem(row: JSONObject, filter: String, videoTypes: MutableMap<String, String>): Item? {
            val renderer = row.optJSONObject("musicResponsiveListItemRenderer")
            if (renderer?.optString("musicItemRendererDisplayPolicy") == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") return null
            val item = Music.parseItem(row) ?: return null
            val expected = when (filter) {
                Yt.SONGS, Yt.VIDEOS -> item is Song
                Yt.ARTISTS -> item is Artist
                Yt.ALBUMS, Yt.PLAYLISTS -> item is Playlist
                else -> false
            }
            if (!expected) return null
            if (item !is Song || renderer == null) return item
            val columns = renderer.list("flexColumns").mapNotNull { it.path("musicResponsiveListItemFlexColumnRenderer", "text") }
            if (filter == Yt.VIDEOS) {
                val endpoint = columns.firstOrNull()?.list("runs")?.firstOrNull()?.optJSONObject("navigationEndpoint")
                    ?: renderer.optJSONObject("navigationEndpoint")
                endpoint?.path("watchEndpoint", "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig")
                    ?.optString("musicVideoType")?.takeIf(String::isNotBlank)?.let { videoTypes[item.url] = it }
            }
            val artists = columns.getOrNull(1)?.list("runs").orEmpty().filter { run ->
                val type = run.path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointMusicConfig")?.optString("pageType")
                type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL"
            }.map { it.optString("text") }.filter(String::isNotBlank)
            val duration = (columns.drop(1).flatMap { it.list("runs") }.map { it.optString("text").trim() } +
                renderer.list("fixedColumns").mapNotNull { it.path("musicResponsiveListItemFixedColumnRenderer", "text")?.text() })
                .lastOrNull { durationPattern.matches(it) }
                ?.split(':')?.fold(0L) { total, component -> total * 60 + component.toLong() } ?: item.durationSeconds
            return item.copy(artist = artists.joinToString(", ").ifBlank { item.artist }, durationSeconds = duration)
        }

        private fun rowToken(rows: List<JSONObject>): String? = rows.lastOrNull()
            ?.path("continuationItemRenderer", "continuationEndpoint", "continuationCommand")
            ?.optString("token")?.takeIf(String::isNotBlank)

        private fun JSONObject.nextToken() = list("continuations").firstOrNull()?.optJSONObject("nextContinuationData")
            ?.optString("continuation")?.takeIf(String::isNotBlank)
        private fun JSONObject.path(vararg keys: String): JSONObject? = keys.fold(this as JSONObject?) { value, key -> value?.optJSONObject(key) }
        private fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }.orEmpty()
        private fun JSONObject.text() = list("runs").joinToString("") { it.optString("text") }
    }
}
