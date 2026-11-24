package app.tunes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val playlistRadioClient by lazy { PlaylistRadioClient(Yt.http) }

/** The original playlist supplies the seeds; [played] also excludes previously queued radio songs. */
suspend fun playlistRadioSongs(
    queue: List<Song>,
    preferences: ListeningPreferences = ListeningPreferences(),
    played: List<Song> = queue,
    exclusions: RadioExclusions = RadioExclusions.Empty,
): List<Song> = playlistRadioClient.songs(queue, preferences, played, exclusions)

/** Anonymous provider radio, sampled across representative playlist artists rather than a global feed.
 * The catalogue does not expose reliable genre labels, so its seed-track relationships supply similarity.
 */
internal class PlaylistRadioClient(
    http: OkHttpClient,
    private val endpoint: HttpUrl = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false".toHttpUrl(),
    private val fallbackSearch: suspend (String, ListeningPreferences) -> List<Song> = { artist, preferences ->
        MusicSearch.searchPage(artist.take(256), Yt.SONGS, preferences).items.filterIsInstance<Song>()
    },
    private val timeoutMs: Long = 20_000,
) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(9, TimeUnit.SECONDS).build()

    suspend fun songs(queue: List<Song>, preferences: ListeningPreferences, played: List<Song> = queue,
        exclusions: RadioExclusions = RadioExclusions.Empty): List<Song> {
        val seeds = seeds(queue.filter(preferences::allows))
        if (seeds.isEmpty()) return emptyList()
        return withTimeoutOrNull(timeoutMs) {
            val candidates = supervisorScope {
                seeds.map { seed -> async { safely { radio(seed, preferences) } } }.awaitAll()
            }
            val novel = novel(candidates, queue + played, preferences, exclusions)
            if (novel.isNotEmpty()) novel else {
                // Radio can be unavailable in a market. Keep the fallback tied to actual playlist
                // artists rather than choosing unrelated popular tracks or silently replaying seeds.
                val artists = seeds.map { it.artist }.filter(String::isNotBlank).distinct().take(2)
                val fallback = supervisorScope {
                    artists.map { artist -> async {
                        safely { fallbackSearch(artist, preferences).filter { matchesArtist(it.artist, artist) } }
                    } }.awaitAll()
                }
                novel(fallback, queue + played, preferences, exclusions)
            }
        } ?: emptyList()
    }

    private suspend fun radio(seed: Song, preferences: ListeningPreferences): List<Song> {
        val id = videoId(seed.url) ?: return emptyList()
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", "1.20260101.01.00").put("hl", "en")
        preferences.market?.let { client.put("gl", it) }
        val body = JSONObject().put("context", JSONObject().put("client", client))
            .put("videoId", id).put("playlistId", "RDAMVM$id").put("params", "wAEB")
            .put("enablePersistentPlaylistPanel", true).put("isAudioOnly", true)
            .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val response = execute(request)
        return withContext(Dispatchers.Default) { parse(JSONObject(response)) }
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!continuation.isActive) return
                        if (!it.isSuccessful) throw IOException("Similar music returned HTTP ${it.code}.")
                        val source = it.body?.source() ?: throw IOException("Similar music returned an empty response.")
                        source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) throw IOException("Similar music returned too much data.")
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private suspend fun safely(block: suspend () -> List<Song>): List<Song> = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }

    companion object {
        private const val MAX_BYTES = 2_000_000L
        private val idPattern = Regex("[A-Za-z0-9_-]{11}")
        private val punctuation = Regex("[^\\p{L}\\p{N}]+")
        private val presentation = Regex("[\\[(](?:official (?:audio|video|music video)|lyrics?|lyric video)[\\])]", RegexOption.IGNORE_CASE)

        internal fun videoId(url: String): String? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val host = parsed.host.lowercase(Locale.ROOT)
            val id = when {
                host == "youtu.be" -> parsed.pathSegments.firstOrNull()
                host == "youtube.com" || host.endsWith(".youtube.com") -> parsed.queryParameter("v")
                    ?: parsed.pathSegments.takeIf { it.firstOrNull() in setOf("shorts", "embed") }?.getOrNull(1)
                else -> null
            }
            return id?.takeIf(idPattern::matches)
        }

        private fun normalized(value: String) = value.lowercase(Locale.ROOT).replace(presentation, "")
            .replace(punctuation, " ").trim()
        internal fun trackKey(song: Song) = "${normalized(song.artist)}:${normalized(song.title)}"
        private fun matchesArtist(value: String, seed: String): Boolean {
            val expected = normalized(seed)
            return expected.isNotEmpty() && normalized(value) == expected
        }

        internal fun seeds(queue: List<Song>): List<Song> = queue.distinctBy { videoId(it.url) ?: it.url }
            .filter { videoId(it.url) != null }
            .groupBy { normalized(it.artist).ifBlank { "unknown" } }
            .entries.sortedByDescending { it.value.size }.take(3).map { entry ->
                entry.value[entry.value.size / 2]
            }

        /** Shared radio results are stronger evidence than a candidate from only one seed. */
        internal fun novel(candidates: List<List<Song>>, played: List<Song>, preferences: ListeningPreferences,
            exclusions: RadioExclusions = RadioExclusions.Empty): List<Song> {
            val urls = played.mapTo(hashSetOf()) { videoId(it.url) ?: it.url }.apply { addAll(exclusions.ids) }
            val recordings = played.mapTo(hashSetOf(), ::trackKey).apply { addAll(exclusions.recordings) }
            val interleaved = candidates.maxOfOrNull { it.size }?.let { count ->
                (0 until count).flatMap { index -> candidates.mapNotNull { it.getOrNull(index) } }
            }.orEmpty()
            val frequency = candidates.flatMap { list -> list.map(::trackKey).distinct() }.groupingBy { it }.eachCount()
            val selectedUrls = hashSetOf<String>()
            val selectedRecordings = hashSetOf<String>()
            return interleaved.filter { song ->
                val id = videoId(song.url) ?: song.url
                val key = trackKey(song)
                song.title.isNotBlank() && song.artist.isNotBlank() && preferences.allows(song) &&
                    id !in urls && key !in recordings && selectedUrls.add(id) && selectedRecordings.add(key)
            }.sortedByDescending { frequency[trackKey(it)] ?: 1 }.take(20)
        }

        internal fun parse(response: JSONObject): List<Song> {
            val panels = ArrayList<JSONObject>()
            var visited = 0
            fun walk(value: Any?, depth: Int) {
                if (depth > 20 || visited++ > 10_000) return
                when (value) {
                    is JSONObject -> {
                        value.optJSONObject("playlistPanelRenderer")?.let(panels::add)
                        value.optJSONObject("playlistPanelContinuation")?.let(panels::add)
                        value.keys().forEach { key -> if (key !in setOf("playlistPanelRenderer", "playlistPanelContinuation")) walk(value.opt(key), depth + 1) }
                    }
                    is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it), depth + 1) }
                }
            }
            walk(response, 0)
            return panels.flatMap { panel ->
                val rows = panel.optJSONArray("contents") ?: JSONArray()
                (0 until minOf(300, rows.length())).mapNotNull songRows@{ index ->
                    val row = rows.optJSONObject(index) ?: return@songRows null
                    val primary = row.optJSONObject("playlistPanelVideoWrapperRenderer")?.optJSONObject("primaryRenderer") ?: row
                    val item = primary.optJSONObject("playlistPanelVideoRenderer") ?: return@songRows null
                    if (item.has("unplayableText") || item.optBoolean("isPlayable", true).not()) return@songRows null
                    val id = item.optString("videoId").takeIf(idPattern::matches) ?: return@songRows null
                    fun text(json: JSONObject?): String = json?.optString("simpleText")?.takeIf(String::isNotBlank)
                        ?: json?.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
                    val title = text(item.optJSONObject("title"))
                    val byline = item.optJSONObject("longBylineText") ?: item.optJSONObject("shortBylineText")
                    val runs = byline?.optJSONArray("runs") ?: JSONArray()
                    val artistNames = (0 until runs.length()).mapNotNull artistRuns@{ runIndex ->
                        val run = runs.optJSONObject(runIndex) ?: return@artistRuns null
                        val browse = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return@artistRuns null
                        run.optString("text").takeIf { browse.optString("browseId").startsWith("UC") && it.isNotBlank() }
                    }
                    val artist = artistNames.joinToString(", ").ifBlank { text(byline).split('•', '·').firstOrNull().orEmpty().trim() }
                    if (title.isBlank() || artist.isBlank()) return@songRows null
                    val thumbnails = item.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
                    val artwork = (0 until thumbnails.length()).mapNotNull(thumbnails::optJSONObject)
                        .maxByOrNull { it.optInt("width") }?.optString("url")?.takeIf(String::isNotBlank)?.let(Music::sharpen)
                    val duration = text(item.optJSONObject("lengthText")).split(':').mapNotNull(String::toLongOrNull)
                    val seconds = if (duration.size in 2..3) duration.fold(0L) { total, part -> total * 60 + part } else 0
                    Song(title, artist, "https://music.youtube.com/watch?v=$id", artwork, seconds)
                }
            }.distinctBy { it.url }.take(300)
        }
    }
}
