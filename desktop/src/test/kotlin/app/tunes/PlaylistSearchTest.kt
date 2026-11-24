package app.tunes

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistSearchTest {
    private val songs = listOf(
        Song("First song", "Evening Artist", "duplicate"),
        Song("Night drive", "Different Artist", "night"),
        Song("Second song", "Evening Artist", "duplicate"),
        Song("Bollywood mix", "Another Artist", "hidden"),
    )

    @Test
    fun titleArtistAndCombinedTermsMatchWithoutLosingDuplicateQueuePositions() {
        assertEquals(listOf(1), searchPlaylist(songs, " NIGHT ").map { it.queueIndex })
        assertEquals(listOf(0, 2), searchPlaylist(songs, "eVENING").map { it.queueIndex })
        val selected = searchPlaylist(songs, "SECOND evening").single()
        assertEquals(2, selected.queueIndex)
        assertSame(songs[2], selected.song)
        assertEquals("Second song", songs[selected.queueIndex].title)
        assertEquals(songs.indices.toList(), searchPlaylist(songs, " \t ").map { it.queueIndex })
        assertTrue(searchPlaylist(songs, "does not exist").isEmpty())
    }

    @Test
    fun searchKeepsFullPlayableQueueAndNeverChangesStoredPlaylist() {
        val dir = Files.createTempDirectory("troit-playlist-search").toFile()
        try {
            val file = File(dir, "library.json")
            val library = Library(file)
            val saved = SavedPlaylist("Night listening", songs)
            library.add(saved)
            val before = file.readText()
            val preferences = ListeningPreferences(excludedRegions = setOf("IN"))
            val playable = saved.songs.filter(preferences::allows)
            val matches = searchPlaylist(playable, "second")
            val selected = preferences.selectPlayback(playable, matches.single().queueIndex)!!
            assertEquals(3, selected.songs.size)
            assertEquals(2, selected.index)
            assertEquals("Second song", selected.songs[selected.index].title)
            assertTrue(searchPlaylist(playable, "Bollywood").isEmpty())
            assertEquals(saved, library.playlists.single())
            assertEquals(before, file.readText())
        } finally { dir.deleteRecursively() }
    }
}
