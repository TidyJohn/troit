package app.tunes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class SpotifyImportSource(val tracks: List<SpotifyTrack>, val warnings: List<String> = emptyList())

/** A resumable, bounded import. At most two lookups run; completed matches survive a pause in this session. */
internal class SpotifyImportSession(
    val tracks: List<SpotifyTrack>,
    val warnings: List<String> = emptyList(),
    private val match: suspend (SpotifyTrack) -> Match = ::matchSpotifyImport,
) {
    init { require(tracks.size <= Spotify.IMPORT_LIMIT) }
    private val results = arrayOfNulls<Match>(tracks.size)
    val done: Int get() = synchronized(results) { results.count { it != null } }
    val songs: List<Song> get() = synchronized(results) { results.mapNotNull { it?.song }.distinctBy { it.url } }
    val notFound: List<String> get() = synchronized(results) { results.filterNotNull().filter { it.song == null }
        .map { "${it.track.artists.joinToString()} - ${it.track.title}" } }

    suspend fun run(onProgress: (Int, Int) -> Unit): Spotify.Imported = coroutineScope {
        val pending = synchronized(results) { tracks.indices.filter { results[it] == null } }
        val cursor = AtomicInteger()
        onProgress(done, tracks.size)
        repeat(minOf(2, pending.size)) {
            launch {
                while (true) {
                    coroutineContext.ensureActive()
                    val position = cursor.getAndIncrement()
                    if (position >= pending.size) break
                    val index = pending[position]
                    val track = tracks[index]
                    val found = try { withTimeout(35_000) { match(track) } }
                        catch (_: TimeoutCancellationException) { Match(track, null, false) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (failure.message?.contains("rate limit", ignoreCase = true) == true)
                                throw IOException("Music search is rate limiting this import. Wait a moment, then resume.")
                            Match(track, null, false)
                        }
                    synchronized(results) { results[index] = found }
                    onProgress(done, tracks.size)
                }
            }
        }
        // coroutineScope waits for both workers before producing the ordered result.
    }.let { Spotify.Imported(songs, notFound, warnings) }
}

/** Uses the cancellable music search path instead of blocking extractor requests for a large batch. */
private suspend fun matchSpotifyImport(track: SpotifyTrack): Match {
    val query = "${track.artists.firstOrNull().orEmpty()} ${track.title}".take(256)
    val official = Spotify.best(track, MusicSearch.searchPage(query, Yt.SONGS).items.filterIsInstance<Song>())
    if (official != null) return Match(track, official, kotlin.math.abs(official.durationSeconds * 1000 - track.durationMs) <= 5_000)
    val video = Spotify.best(track, MusicSearch.searchPage(query, Yt.VIDEOS).items.filterIsInstance<Song>())
    return Match(track, video, video != null && kotlin.math.abs(video.durationSeconds * 1000 - track.durationMs) <= 5_000)
}

private val spotifyImportHttp by lazy { Yt.http.newBuilder().callTimeout(20, TimeUnit.SECONDS)
    .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

internal suspend fun prepareSpotifyImport(
    input: String,
    account: SpotifyAccount? = null,
    readLink: suspend (String, String) -> SpotifyImportSource = { type, id ->
        if (account?.connected == true) account.tracks(type, id) else readSpotifyImportLink(type, id)
    },
    matcher: suspend (SpotifyTrack) -> Match = ::matchSpotifyImport,
): SpotifyImportSession {
    require(input.length <= 2_000_000) { "Paste Spotify links under 2 MB." }
    val warnings = mutableListOf<String>()
    val tracks = mutableListOf<SpotifyTrack>()
        val links = Spotify.links(input)
        require(links.isNotEmpty()) { "Paste links that start with open.spotify.com." }
        for ((type, id) in links.take(Spotify.IMPORT_LIMIT)) {
            coroutineContext.ensureActive()
            if (tracks.size >= Spotify.IMPORT_LIMIT) { warnings += "Only the first 1,000 songs are included in one import."; break }
            val source = try { readLink(type, id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { warnings += failure.message?.take(240) ?: "Couldn't read Spotify $type $id."; continue }
            warnings += source.warnings
            val remaining = Spotify.IMPORT_LIMIT - tracks.size
            if (source.tracks.size > remaining) warnings += "Only the first 1,000 songs are included in one import."
            tracks += source.tracks.take(remaining)
        }
        if (links.size > Spotify.IMPORT_LIMIT) warnings += "Only the first 1,000 Spotify links were read."
    val unique = tracks.distinctBy { "${it.title.lowercase(Locale.ROOT)}|${it.artists.joinToString().lowercase(Locale.ROOT)}|${it.durationMs}" }
    require(unique.isNotEmpty()) { warnings.firstOrNull() ?: "No readable tracks were supplied." }
    return SpotifyImportSession(unique.take(Spotify.IMPORT_LIMIT), warnings.distinct().take(12), matcher)
}

private suspend fun readSpotifyImportLink(type: String, id: String): SpotifyImportSource {
    val request = Request.Builder().url("https://open.spotify.com/embed/$type/$id")
        .header("User-Agent", "Mozilla/5.0").build()
    val html = suspendCancellableCoroutine<String> { continuation ->
        val call = spotifyImportHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Spotify returned HTTP ${it.code}.")
                        val source = it.body?.source() ?: throw IOException("Spotify returned an empty page.")
                        source.request(4_000_001)
                        if (source.buffer.size > 4_000_000) throw IOException("Spotify returned an oversized page.")
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        })
    }
    return withContext(Dispatchers.Default) { Spotify.parseEmbed(html) }
}

