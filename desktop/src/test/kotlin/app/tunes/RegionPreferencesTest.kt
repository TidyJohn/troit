package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegionPreferencesTest {
    private fun song(title: String, artist: String = "Artist") = Song(title, artist, "https://music.youtube.com/watch?v=test")
    private fun file() = File.createTempFile("tunes-preferences", ".json").apply { delete(); deleteOnExit() }

    @Test
    fun defaultsAllowEveryRegionAndLanguage() {
        val preferences = RegionPreferences(file()).settings
        assertNull(preferences.market)
        assertEquals(0, preferences.activeExclusions)
        assertTrue(preferences.allows(song("Bollywood Hindi Hits")))
        assertTrue(preferences.allows(song("தமிழ் இசை")))
    }

    @Test
    fun countryLabelsFilterMusicWithoutGuessingArtistNationality() {
        val preferences = ListeningPreferences(excludedRegions = setOf("IN"))
        assertFalse(preferences.allows(song("The best BOLLYWOOD songs")))
        assertFalse(preferences.allows(Playlist("Top 100: India", "Charts", "x")))
        assertFalse(preferences.allows(Playlist("Weekly mix", "Indian music", "x")))
        assertFalse(preferences.allows(Playlist("India hits", "Charts", "x")))
        assertTrue(preferences.allows(song("Indian Summer", "India.Arie")))
        assertTrue(preferences.allows(song("Ordinary song", "Bollywood Band")))
        assertTrue(preferences.allows(Artist("India.Arie", "Artist", "x")))
        assertTrue("A country is separate from language", preferences.allows(song("Hindi song")))
    }

    @Test
    fun languageLabelsUseWholeWordsAndOnlyUnambiguousScripts() {
        val preferences = ListeningPreferences(excludedLanguages = setOf("hi", "ta", "ko"))
        assertFalse(preferences.allows(song("Hindi Acoustic Hits")))
        assertFalse(preferences.allows(song("தமிழ் இசை")))
        assertFalse(preferences.allows(song("한국 음악")))
        assertTrue(preferences.allows(song("Hindsight")))
        assertTrue("Devanagari alone does not prove Hindi", preferences.allows(song("नमस्ते")))
        assertTrue("An artist's name is not a song language", preferences.allows(song("Ordinary song", "Hindi Band")))
    }

    @Test
    fun hidingARegionRemovesItsShelvesAndFiltersOtherShelves() {
        val preferences = ListeningPreferences(excludedRegions = setOf("IN"))
        val keep = song("Get Lucky")
        val hidden = song("Bollywood Hits")
        val shelves = listOf(
            Section("Bollywood essentials", listOf(keep)),
            Section("Recommended", listOf(keep, hidden), Playlist("Bollywood Hits", "", "more")),
            Section("Only hidden tracks", listOf(hidden)),
        )
        val filtered = preferences.filterSections(shelves)
        assertEquals(listOf("Recommended"), filtered.map { it.title })
        assertEquals(listOf(keep), filtered.single().items)
        assertNull(filtered.single().more)
        assertEquals("Source data should survive so exclusions are reversible", 2, shelves[1].items.size)
        assertTrue(preferences.allows(SavedPlaylist("Bollywood collection", listOf(hidden))))
    }

    @Test
    fun marketAndExclusionsPersistIndependentlyAndReset() {
        val file = file()
        val preferences = RegionPreferences(file)
        preferences.setMarket("ID")
        preferences.setRegionExcluded("IN", true)
        preferences.setLanguageExcluded("hi", true)
        preferences.setRegionExcluded("IN", true)
        assertEquals(ListeningPreferences("ID", setOf("IN"), setOf("hi")), RegionPreferences(file).settings)

        preferences.setRegionExcluded("IN", false)
        assertEquals(setOf("hi"), RegionPreferences(file).settings.excludedLanguages)
        assertEquals(emptySet<String>(), RegionPreferences(file).settings.excludedRegions)
        preferences.setMarket(null)
        assertNull(RegionPreferences(file).settings.market)
        preferences.reset()
        assertEquals(ListeningPreferences(), RegionPreferences(file).settings)
    }

    @Test
    fun indianMusicPresetIsReversibleAndKeepsOtherPreferences() {
        val file = file()
        val preferences = RegionPreferences(file)
        preferences.setMarket("US")
        preferences.setRegionExcluded("KR", true)
        preferences.setLanguageExcluded("ko", true)
        preferences.setIndianMusicExcluded(true)
        assertEquals(setOf("IN", "KR"), RegionPreferences(file).settings.excludedRegions)
        assertTrue(preferences.settings.excludedLanguages.containsAll(MusicLanguages.indianMusicCodes))
        assertFalse(preferences.settings.allows(song("Tamil greatest hits")))
        preferences.setIndianMusicExcluded(false)
        assertEquals(ListeningPreferences("US", setOf("KR"), setOf("ko")), RegionPreferences(file).settings)
    }

    @Test
    fun corruptOrUnknownSavedValuesCannotBreakDiscovery() {
        val file = file().apply { writeText("broken json") }
        assertEquals(ListeningPreferences(), RegionPreferences(file).settings)
        file.writeText("""{"market":"UNKNOWN","excludedRegions":["IN","bogus"],"excludedLanguages":["hi","unknown"]}""")
        assertEquals(ListeningPreferences(null, setOf("IN"), setOf("hi")), RegionPreferences(file).settings)
    }
}
