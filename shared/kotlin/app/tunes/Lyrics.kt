package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.random.Random

data class LyricLine(val timeMs: Long, val text: String)

data class LyricsResult(
    val lines: List<LyricLine> = emptyList(),
    val plainText: String? = null,
    val instrumental: Boolean = false,
    val sourceId: Long? = null,
) {
    val timed get() = lines.isNotEmpty()
    val available get() = timed || !plainText.isNullOrBlank() || instrumental

    /** The last timestamp at or before this position, including repeated timestamps. */
    fun activeIndex(positionMs: Long): Int {
        var low = 0; var high = lines.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
        }
        return low - 1
    }
}

/** LRC metadata is not lyric text. Offset is global and can appear after the timestamped rows. */
fun parseLrc(source: String): List<LyricLine> {
    require(source.length <= 128_000) { "The lyrics are too large." }
    val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offsetPattern = Regex("^\\[offset:([+-]?\\d{1,8})]$", RegexOption.IGNORE_CASE)
    val raw = ArrayList<LyricLine>()
    var offset = 0L
    source.lineSequence().take(4_000).forEach { row ->
        val text = row.trim().removePrefix("\uFEFF")
        val parsedOffset = offsetPattern.matchEntire(text)?.groupValues?.get(1)?.toLongOrNull()
        if (parsedOffset != null) { offset = parsedOffset.coerceIn(-600_000L, 600_000L); return@forEach }
        val matches = timestamp.findAll(text).toList()
        if (matches.isEmpty() || matches.first().range.first != 0) return@forEach
        var end = 0
        val times = ArrayList<Long>()
        for (match in matches) {
            if (match.range.first != end) break
            end = match.range.last + 1
            val minute = match.groupValues[1].toLongOrNull() ?: continue
            val second = match.groupValues[2].toLongOrNull() ?: continue
            if (second !in 0..59) continue
            val fraction = match.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toLongOrNull() ?: continue
            val time = minute * 60_000 + second * 1_000 + fraction
            if (time <= 604_800_000L) times += time
        }
        val lyric = text.substring(end).replace(Regex("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>"), "").trim().take(2_000)
        for (time in times) if (raw.size < 2_000) raw += LyricLine(time, lyric)
    }
    if (raw.none { it.text.isNotBlank() }) return emptyList()
    return raw.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) }
        .sortedBy { it.timeMs }.distinctBy { it.timeMs to it.text }.take(2_000)
}

internal fun lyricsLookupTitle(title: String): String {
    val presentation = Regex("[\\[(](?:official (?:audio|video|music video)|lyrics?|lyric video)[\\])]", RegexOption.IGNORE_CASE)
    return title.replace(presentation, " ").trim().replace(Regex("\\s+"), " ")
}
internal fun normalizedLyricsTitle(title: String): String = normalizeLyricsMetadata(lyricsLookupTitle(title))

internal fun normalizeLyricsMetadata(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace(Regex("\\s+-\\s+topic$", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

class LyricsUnavailable(message: String, val retryAtMillis: Long = 0) : IOException(message)

/** One exact-record lookup only: opening lyrics never scans a library or uploads playback/account data. */
class LyricsClient(
    private val http: OkHttpClient = Yt.http.newBuilder().callTimeout(12, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://lrclib.net",
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttleMs: Long = 250,
    private val jitter: () -> Double = { Random.nextDouble(0.0, .3) },
) {
    private data class Key(val title: String, val artist: String, val duration: Long)
    private data class Cached(val result: LyricsResult, val expiresAt: Long)
    private data class Reply(val code: Int, val text: String, val retryAfter: String?)
    private val lock = Mutex()
    private val cache = LinkedHashMap<Key, Cached>(32, .75f, true)
    private var nextRequestAt = 0L
    private var retryAt = 0L
    internal val cacheSize get() = cache.size

    suspend fun get(song: Song, refresh: Boolean = false): LyricsResult = lock.withLock {
        val key = Key(normalizedLyricsTitle(song.title), normalizeLyricsMetadata(song.artist), song.durationSeconds)
        if (key.title.isBlank() || key.artist.isBlank()) return@withLock LyricsResult()
        if (!refresh) cache[key]?.takeIf { it.expiresAt > clock() }?.let { return@withLock it.result }
        if (clock() < retryAt) throw LyricsUnavailable("The lyrics provider is busy. Try again after its retry delay.", retryAt)
        if (clock() < nextRequestAt) delay((nextRequestAt - clock()).coerceAtMost(500))
        val url = (baseUrl.trimEnd('/') + "/api/get").toHttpUrl().newBuilder()
            .addQueryParameter("track_name", lyricsLookupTitle(song.title).take(500))
            .addQueryParameter("artist_name", song.artist.replace(Regex("\\s+-\\s+topic$", RegexOption.IGNORE_CASE), "").take(500)).apply {
                if (song.durationSeconds in 1..3_600) addQueryParameter("duration", song.durationSeconds.toString())
            }.build()
        val response = try { execute(Request.Builder().url(url)
            .header("User-Agent", "Troit v0.13 (Kotlin desktop and Android music player)")
            .header("Accept", "application/json").get().build()) }
            finally { nextRequestAt = clock() + throttleMs.coerceIn(0, 500) }
        if (response.code == 429 || response.code == 503) {
            val now = clock()
            val seconds = response.retryAfter?.trim()?.toLongOrNull()?.coerceAtLeast(1)
            val providerTime = if (seconds != null) safeFuture(now, seconds)
                else runCatching { ZonedDateTime.parse(response.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    ?.coerceAtLeast(now + 1_000) ?: (now + 5_000)
            val extra = ((providerTime - now).toDouble() * jitter().coerceIn(0.0, .3)).toLong()
            retryAt = if (Long.MAX_VALUE - providerTime < extra) Long.MAX_VALUE else providerTime + extra
            throw LyricsUnavailable("The lyrics provider is busy. Please wait before trying again.", retryAt)
        }
        if (response.code != 404 && response.code != 200) throw LyricsUnavailable("Couldn't connect to lyrics. Try again shortly.")
        val result = if (response.code == 404) LyricsResult() else decode(song, JSONObject(response.text))
        cache[key] = Cached(result, clock() + if (result.available) 6 * 60 * 60_000L else 10 * 60_000L)
        while (cache.size > 32) cache.remove(cache.keys.first())
        result
    }

    private fun decode(song: Song, json: JSONObject): LyricsResult {
        if (normalizedLyricsTitle(json.optString("trackName")) != normalizedLyricsTitle(song.title) ||
            normalizeLyricsMetadata(json.optString("artistName")) != normalizeLyricsMetadata(song.artist)) return LyricsResult()
        val duration = json.optDouble("duration", Double.NaN)
        if (song.durationSeconds > 0 && (!duration.isFinite() || abs(duration - song.durationSeconds) > 2.0)) return LyricsResult()
        val id = json.optLong("id", 0).takeIf { it > 0 }
        if (json.optBoolean("instrumental")) return LyricsResult(instrumental = true, sourceId = id)
        val synced = json.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
        val plain = json.optString("plainLyrics").takeUnless { it == "null" || it.isBlank() }
        require(synced.length <= 128_000 && (plain?.length ?: 0) <= 128_000) { "The lyrics are too large." }
        return LyricsResult(parseLrc(synced), plain, sourceId = id)
    }

    private suspend fun execute(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    if (!continuation.isActive) return
                    val text = if (it.code == 200) {
                        if (it.body?.contentType()?.subtype?.contains("json") != true) throw LyricsUnavailable("The lyrics provider returned an unexpected response.")
                        val source = it.body?.source() ?: throw LyricsUnavailable("The lyrics provider returned no response.")
                        source.request(256_001)
                        if (source.buffer.size > 256_000) throw LyricsUnavailable("The lyrics response was too large.")
                        source.readUtf8()
                    } else ""
                    if (continuation.isActive) continuation.resume(Reply(it.code, text, it.header("Retry-After")))
                } } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }

    private fun safeFuture(now: Long, seconds: Long): Long =
        if (seconds > (Long.MAX_VALUE - now) / 1_000) Long.MAX_VALUE else now + seconds * 1_000
}

object LyricsRepository { val client by lazy { LyricsClient() } }

class LyricsState(private val client: LyricsClient = LyricsRepository.client) {
    private var generation = 0L
    var result by mutableStateOf<LyricsResult?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    suspend fun load(song: Song, refresh: Boolean = false) {
        val epoch = ++generation
        loading = true; error = null; result = null
        try {
            val loaded = client.get(song, refresh)
            if (generation == epoch) result = loaded
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            if (generation == epoch) error = if (failure is LyricsUnavailable) failure.message else "Couldn't load lyrics. Check your connection and try again."
        } finally { if (generation == epoch) loading = false }
    }
}
