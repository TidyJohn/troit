package app.tunes

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A playlist kept on this device. [deleted] rows stay as markers so a delete can reach your other devices. */
data class SavedPlaylist(
    override val title: String,
    val songs: List<Song>,
    val id: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) : Item {
    override val subtitle get() = "${songs.size} songs"
    override val artwork get() = songs.firstOrNull()?.artwork
}

/** Replaces the file in one step, so a crash mid-save can't corrupt what was there. */
internal fun File.writeAtomically(text: String) {
    val temp = File(parentFile, "$name.tmp")
    temp.writeText(text)
    Files.move(temp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * ponytail: one JSON file, rewritten on each change. Fine for playlists; move to a database when likes and
 * play history arrive, since those write constantly.
 */
class Library(private val file: File) {
    val all = mutableStateListOf<SavedPlaylist>()
    private val activePlaylists = derivedStateOf { all.filter { !it.deleted } }
    val playlists get() = activePlaylists.value

    /** Called after a change made on this device. */
    var onChange: (() -> Unit)? = null

    init {
        // No file yet just means nothing has been saved.
        runCatching {
            val text = file.readText()
            all += decode(text)
            // Files from before sync have no ids. Save the new ones now, or each launch would invent different ids.
            if (all.isNotEmpty() && "\"id\"" !in text) save()
        }
    }

    fun add(playlist: SavedPlaylist) {
        all.add(0, playlist)
        save()
        onChange?.invoke()
    }

    fun remove(playlist: SavedPlaylist) {
        val index = all.indexOfFirst { it.id == playlist.id }
        if (index < 0) return
        val current = all[index]
        if (current.deleted) return
        all[index] = current.copy(songs = emptyList(), deleted = true,
            updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1))
        save()
        onChange?.invoke()
    }

    /** Rename the current version, preserving its songs and identity for cross-device sync. */
    fun rename(playlistId: String, title: String): Boolean {
        val name = title.trim().take(100)
        if (name.isEmpty()) return false
        val index = all.indexOfFirst { it.id == playlistId && !it.deleted }
        if (index < 0 || all[index].title == name) return false
        update(index, all[index].copy(title = name))
        return true
    }

    /** Adds the songs that aren't in the playlist yet and returns how many that was. */
    fun addSongs(playlistId: String, songs: List<Song>): Int {
        val index = all.indexOfFirst { it.id == playlistId && !it.deleted }
        if (index < 0) return 0
        val playlist = all[index]
        val existing = playlist.songs.map { it.url }.toSet()
        val fresh = songs.distinctBy { it.url }.filter { it.url !in existing }
        if (fresh.isEmpty()) return 0
        update(index, playlist.copy(songs = playlist.songs + fresh))
        return fresh.size
    }

    fun removeSong(playlistId: String, song: Song) {
        val index = all.indexOfFirst { it.id == playlistId && !it.deleted }
        if (index < 0) return
        val playlist = all[index]
        update(index, playlist.copy(songs = playlist.songs.filter { it.url != song.url }))
    }

    private fun update(index: Int, changed: SavedPlaylist) {
        // A second edit within the same millisecond still needs a newer sync revision.
        all[index] = changed.copy(updatedAt = maxOf(System.currentTimeMillis(), all[index].updatedAt + 1))
        save()
        onChange?.invoke()
    }

    /** Takes the result of a sync. Doesn't call [onChange], or every sync would trigger another. */
    fun replaceAll(merged: List<SavedPlaylist>) {
        if (merged == all.toList()) return
        all.clear()
        all += merged
        save()
    }

    private fun save() = file.writeAtomically(encode(all))

    companion object {
        internal fun songsToJson(songs: List<Song>) = JSONArray().apply {
            for (song in songs) {
                put(
                    JSONObject()
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("url", song.url)
                        .put("artwork", song.artwork ?: JSONObject.NULL)
                        .put("duration", song.durationSeconds),
                )
            }
        }

        internal fun songsFromJson(songs: JSONArray): List<Song> = (0 until songs.length()).map { i ->
            val song = songs.getJSONObject(i)
            Song(
                song.getString("title"),
                song.getString("artist"),
                song.getString("url"),
                if (song.isNull("artwork")) null else song.getString("artwork"),
                song.optLong("duration"),
            )
        }

        internal fun encode(playlists: List<SavedPlaylist>): String = JSONArray().apply {
            for (playlist in playlists) {
                put(
                    JSONObject()
                        .put("id", playlist.id)
                        .put("title", playlist.title)
                        .put("songs", songsToJson(playlist.songs))
                        .put("updatedAt", playlist.updatedAt)
                        .put("deleted", playlist.deleted),
                )
            }
        }.toString()

        internal fun decode(text: String): List<SavedPlaylist> {
            val playlists = JSONArray(text)
            return (0 until playlists.length()).map { i ->
                val playlist = playlists.getJSONObject(i)
                SavedPlaylist(
                    title = playlist.getString("title"),
                    songs = songsFromJson(playlist.getJSONArray("songs")),
                    id = playlist.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    updatedAt = playlist.optLong("updatedAt"),
                    deleted = playlist.optBoolean("deleted"),
                )
            }
        }
    }
}
