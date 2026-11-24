package app.tunes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

/** Search and audio lookup through NewPipeExtractor. Every call blocks on the network: keep it off the main thread. */
object Yt {
    const val SONGS = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
    const val ALBUMS = YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS
    const val PLAYLISTS = YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS
    const val ARTISTS = YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS
    const val VIDEOS = YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val http = OkHttpClient()
    private val yt get() = ServiceList.YouTube

    init {
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val method = request.httpMethod()
                // OkHttp rejects POST without a body, the extractor sometimes sends none.
                val body = request.dataToSend()?.toRequestBody()
                    ?: if (method == "POST") ByteArray(0).toRequestBody() else null
                val builder = okhttp3.Request.Builder()
                    .url(request.url())
                    .method(method, body)
                    .header("User-Agent", USER_AGENT)
                for ((name, values) in request.headers()) {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                http.newCall(builder.build()).execute().use { response ->
                    if (response.code == 429) {
                        throw ReCaptchaException("YouTube is rate limiting this device", request.url())
                    }
                    return Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        response.body?.string(),
                        response.request.url.toString(),
                    )
                }
            }
        })
    }

    /** One page of search results. [more] fetches the next page; null when there are no more. */
    class Results(val items: List<Item>, val more: (() -> Results)?)

    /** [filter] is one of [SONGS], [ARTISTS], [ALBUMS], [PLAYLISTS], [VIDEOS]. */
    fun search(query: String, filter: String = SONGS, preferences: ListeningPreferences = ListeningPreferences()): List<Item> =
        searchPage(query, filter, preferences).items

    fun searchPage(query: String, filter: String, preferences: ListeningPreferences = ListeningPreferences()): Results {
        val handler = yt.searchQHFactory.fromQuery(query, listOf(filter), "")
        val extractor = searchExtractor(handler, preferences)
        extractor.fetchPage()
        val info = SearchInfo.getInfo(extractor)
        return Results(preferences.filterItems(info.relatedItems.mapNotNull(::toItem)), nextPage(handler, info.nextPage, preferences))
    }

    private fun searchExtractor(handler: SearchQueryHandler, preferences: ListeningPreferences): SearchExtractor =
        yt.getSearchExtractor(handler).apply {
            // Per-request country keeps concurrent imports/searches from changing each other's locale.
            preferences.market?.let { forceContentCountry(ContentCountry(it)) }
        }

    private fun nextPage(handler: SearchQueryHandler, page: Page?, preferences: ListeningPreferences): (() -> Results)? {
        if (!Page.isValid(page)) return null
        return {
            val more = searchExtractor(handler, preferences).getPage(page)
            Results(preferences.filterItems(more.items.mapNotNull(::toItem)), nextPage(handler, more.nextPage, preferences))
        }
    }

    private fun toItem(item: InfoItem): Item? = when (item) {
        is StreamInfoItem -> Song(item.name, item.uploaderName.orEmpty(), item.url, item.thumbnails.pick(), item.duration)
        is PlaylistInfoItem -> Playlist(
            item.name,
            item.uploaderName.orEmpty(),
            "VL" + item.url.substringAfter("list=").substringBefore('&'),
            item.thumbnails.pick(),
        )
        is ChannelInfoItem -> Artist(item.name, "Artist", item.url.substringAfterLast('/'), item.thumbnails.pick())
        else -> null
    }

    private fun List<Image>.pick(): String? = maxByOrNull { it.height }?.url?.let(Music::sharpen)

    private val audioUrls = AudioSourceCache()
    private val expireParam = Regex("[?&]expire=(\\d+)")

    /** Direct link to the best-quality audio for a song. Links expire after a few hours, so ask right before playing. */
    fun audioUrl(songUrl: String): String = audioSource(songUrl).url

    /** A downloaded copy wins, so offline songs never wait on the network. */
    internal fun audioSource(songUrl: String): AudioSource = Downloads.localSource(songUrl) ?: remoteAudioSource(songUrl)

    internal fun remoteAudioSource(songUrl: String): AudioSource = audioUrls.resolve(songUrl) {
        val best = bestAudioStream(StreamInfo.getInfo(yt, songUrl).audioStreams)
            ?: error("No playable audio found for this song")
        AudioSource(best.content, best.sourceQuality())
    }

    /** Resolve the picture separately from the active audio. Some search rows hide the upload's full title. */
    internal suspend fun videoSource(song: Song, maxHeight: Int = 1080, h264Only: Boolean = false, targetSeconds: Long = 0): VideoSource {
        val page = MusicSearch.searchPage(MusicVideoMatch.query(song), VIDEOS)
        val candidates = MusicVideoMatch.ranked(song, page.items.filterIsInstance<Song>(), targetSeconds, page.videoTypes)
        for (candidate in candidates.take(3)) {
            val info = try {
                runInterruptible(Dispatchers.IO) { StreamInfo.getInfo(yt, candidate.url) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: ReCaptchaException) {
                throw limited
            } catch (_: Exception) {
                continue
            }
            if (!MusicVideoMatch.acceptsUpload(song, info.name)) continue
            fun playable(stream: VideoStream) = stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.height in 1..maxHeight && (!h264Only || stream.codec?.startsWith("avc") == true ||
                stream.format?.suffix == "mp4" && stream.codec.isNullOrBlank())
            val quality = compareBy<VideoStream>({ it.height }, { it.bitrate })
            val picture = info.videoOnlyStreams.filter(::playable).maxWithOrNull(quality)
            val audio = bestAudioStream(info.audioStreams)
            if (picture != null && audio != null) return VideoSource(info.name, picture.content, audio.content, info.duration)
            val combined = info.videoStreams.filter(::playable).maxWithOrNull(quality)
            if (combined != null) return VideoSource(info.name, combined.content, null, info.duration)
        }
        error("No matching music video available")
    }

    private data class VideoKey(val songUrl: String, val maxHeight: Int, val h264Only: Boolean)
    private val videos = object : LinkedHashMap<VideoKey, VideoSource>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<VideoKey, VideoSource>?) = size > 16
    }

    /** A previous track's network lookup must never hold a lock while the new track looks for its picture. */
    internal suspend fun cachedVideoSource(song: Song, maxHeight: Int = 1080, h264Only: Boolean = false, targetSeconds: Long = 0): VideoSource {
        val key = VideoKey(song.url, maxHeight, h264Only)
        synchronized(videos) { videos[key] }?.takeIf { isFresh(it.videoUrl) }?.let { return it }
        return videoSource(song, maxHeight, h264Only, targetSeconds).also { synchronized(videos) { videos[key] = it } }
    }

    /** Reads only cached metadata; safe for the UI and never makes a network request. */
    internal fun cachedAudioQuality(songUrl: String): AudioQuality? = Downloads.quality(songUrl) ?: audioUrls.quality(songUrl)

    /** YouTube audio links carry their own expiry (epoch seconds) in the `expire` parameter. Unknown means stale. */
    internal fun isFresh(audioUrl: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiresAtSeconds = expireParam.find(audioUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        return expiresAtSeconds * 1000 > nowMs + 10 * 60_000 // 10 min of slack to finish loading a song
    }

    fun forgetAudioUrls() { audioUrls.clear(); synchronized(videos) { videos.clear() } }
}
