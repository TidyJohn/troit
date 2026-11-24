package app.tunes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

data class Download(val song: Song, val bytes: Long, val quality: AudioQuality)

/**
 * Offline copies of songs. Both players resolve audio through [Yt.audioSource], which asks here first,
 * so a downloaded song plays from disk on Windows, phone and TV without touching the network.
 * ponytail: no automatic eviction or size cap; the Downloads list shows the total and removes on request.
 * ponytail: downloads run only while the app process is alive. Move to WorkManager if large playlists
 * must finish with the Android app closed.
 */
object Downloads {
    var entries by mutableStateOf<List<Download>>(emptyList())
        private set
    private var urls by mutableStateOf<Set<String>>(emptySet())
    /** Song URL to 0..1 while queued or downloading. */
    val progress = mutableStateMapOf<String, Float>()
    var error by mutableStateOf<String?>(null)
        private set
    val totalBytes get() = entries.sumOf { it.bytes }

    private const val MAX_FILE_BYTES = 300L shl 20 // far above any song; stops a wrong link filling the disk
    internal var chunkBytes = 4L shl 20 // YouTube throttles un-ranged requests to playback speed
    internal var resolve: (String) -> AudioSource = { Yt.remoteAudioSource(it) }
    private var dir: File? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "troit-downloads").apply { isDaemon = true } }

    /** Safe to call from every entry point (window, activity, playback service); only the first call per folder reads. */
    @Synchronized fun init(dataDir: File) {
        val target = File(dataDir, "downloads")
        if (dir == target) return
        dir = target
        target.mkdirs()
        publish(load(target))
        // Unfinished downloads, and files that were still open in the player when they were removed.
        val keep = entries.mapTo(hashSetOf("index.json")) { fileName(it.song.url) }
        target.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    fun isDownloaded(songUrl: String) = songUrl in urls

    internal fun localSource(songUrl: String): AudioSource? {
        if (songUrl !in urls) return null
        val entry = entries.firstOrNull { it.song.url == songUrl } ?: return null
        val file = File(dir ?: return null, fileName(songUrl))
        return if (file.isFile) AudioSource(file.absolutePath, entry.quality) else null
    }

    internal fun quality(songUrl: String): AudioQuality? =
        if (songUrl in urls) entries.firstOrNull { it.song.url == songUrl }?.quality else null

    fun download(songs: List<Song>) {
        val target = dir ?: return
        error = null
        for (song in songs) {
            if (!song.url.startsWith("https://") || isDownloaded(song.url) || song.url in progress) continue
            progress[song.url] = 0f
            worker.execute {
                val file = File(target, fileName(song.url))
                val part = File(target, file.name + ".part")
                try {
                    if (song.url !in progress) return@execute // cancelled while it waited its turn
                    val source = fetch(song.url, part)
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    synchronized(this) {
                        if (dir == target) { publish(entries.filterNot { it.song.url == song.url } + Download(song, file.length(), source.quality)); save() }
                    }
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                    error = "Couldn't download ${song.title.take(80)}. Check your connection and try again."
                } finally { part.delete(); progress.remove(song.url) } // a failed or cancelled download leaves nothing behind
            }
        }
    }

    /** Also stops queued and running downloads of these songs. */
    @Synchronized fun remove(songUrls: Collection<String>) {
        val gone = songUrls.toSet()
        gone.forEach { progress.remove(it) }
        val target = dir ?: return
        if (entries.none { it.song.url in gone }) return
        publish(entries.filterNot { it.song.url in gone })
        save()
        gone.forEach { File(target, fileName(it)).delete() } // a file in use on Windows is cleared on the next start
    }

    private fun fetch(songUrl: String, part: File): AudioSource {
        var failure: IOException? = null
        repeat(2) {
            val source = resolve(songUrl)
            try { copy(source.url, part, songUrl); return source }
            catch (e: IOException) { failure = e; Yt.forgetAudioUrls() } // signed links expire: one fresh link is worth a retry
        }
        throw failure ?: IOException("Download failed")
    }

    private fun copy(audioUrl: String, part: File, songUrl: String) {
        part.outputStream().use { out ->
            var offset = 0L
            var total = -1L
            while (total < 0 || offset < total) {
                if (songUrl !in progress) throw CancellationException()
                val request = Request.Builder().url(audioUrl).header("Range", "bytes=$offset-${offset + chunkBytes - 1}").build()
                Yt.http.newCall(request).execute().use { response ->
                    val body = response.body ?: throw IOException("Empty response")
                    when (response.code) {
                        206 -> {
                            total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: throw IOException("No length")
                            if (total > MAX_FILE_BYTES) throw IOException("File too large")
                            val written = body.byteStream().copyTo(out)
                            if (written <= 0) throw IOException("Stalled")
                            offset += written
                        }
                        200 -> { // the server ignored Range: this body is the whole file
                            if (offset != 0L) throw IOException("Unexpected full response")
                            offset = body.byteStream().copyTo(out)
                            total = offset
                        }
                        else -> throw IOException("HTTP ${response.code}")
                    }
                }
                if (offset > MAX_FILE_BYTES) throw IOException("File too large")
                progress[songUrl] = if (total > 0) (offset.toFloat() / total).coerceIn(0f, 1f) else 0f
            }
        }
    }

    private fun publish(list: List<Download>) {
        entries = list
        urls = list.mapTo(HashSet()) { it.song.url }
    }

    private fun load(target: File): List<Download> = runCatching {
        val rows = JSONArray(File(target, "index.json").readText())
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val song = Library.songsFromJson(JSONArray().put(row.getJSONObject("song"))).single()
            if (!File(target, fileName(song.url)).isFile) null
            else Download(song, row.getLong("bytes"), AudioQuality(row.getString("codec"), row.optInt("kbps").takeIf { it > 0 }))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val target = dir ?: return
        File(target, "index.json").writeAtomically(JSONArray(entries.map { row ->
            JSONObject().put("song", Library.songsToJson(listOf(row.song)).getJSONObject(0)).put("bytes", row.bytes)
                .put("codec", row.quality.codec).put("kbps", row.quality.bitrateKbps ?: 0)
        }).toString())
    }

    private fun fileName(songUrl: String) = MessageDigest.getInstance("SHA-256").digest(songUrl.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32) + ".audio"
}
