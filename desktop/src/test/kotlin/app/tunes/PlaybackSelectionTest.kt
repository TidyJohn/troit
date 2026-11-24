package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSelectionTest {
    private val preferences = ListeningPreferences(excludedRegions = setOf("IN"))
    private fun song(title: String, url: String = title) = Song(title, "Artist", url)

    @Test
    fun filtersQueueAndRemapsTheSelectedIndex() {
        val songs = listOf(song("Bollywood Mix"), song("First"), song("Indian music playlist"), song("Selected"), song("Last"))
        val selected = preferences.selectPlayback(songs, 3)!!
        assertEquals(listOf("First", "Selected", "Last"), selected.songs.map { it.title })
        assertEquals(1, selected.index)
        assertEquals(5, songs.size) // Stored playlists are not mutated.
    }

    @Test
    fun refusesExcludedSelectedTracksAndInvalidIndexes() {
        val songs = listOf(song("First"), song("Bollywood Mix"))
        assertNull(preferences.selectPlayback(songs, 1))
        assertNull(preferences.selectPlayback(songs, -1))
        assertNull(preferences.selectPlayback(songs, 2))
        assertNull(preferences.selectPlayback(emptyList(), 0))
    }

    @Test
    fun repeatedUrlsKeepTheTappedOccurrence() {
        val songs = listOf(song("First occurrence", "same"), song("Bollywood Mix"), song("Second occurrence", "same"))
        val selected = preferences.selectPlayback(songs, 2)!!
        assertEquals(1, selected.index)
        assertEquals("Second occurrence", selected.songs[selected.index].title)
    }

    @Test
    fun defaultPreferencesPreserveQueueAndUnknownOrigins() {
        val songs = listOf(song("Unlabelled track"), song("Bollywood Mix"), song("Another track"))
        assertEquals(PlaybackSelection(songs, 1), ListeningPreferences().selectPlayback(songs, 1))
        assertEquals("Unlabelled track", preferences.selectPlayback(songs, 0)!!.songs.first().title)
    }
}
