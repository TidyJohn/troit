package app.tunes

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only live schema check; requires the public music catalogue, never an account. */
class PlaylistRadioLiveTest {
    @Test
    fun providerRadioReturnsNovelSongsForAKnownMusicSeed() = runBlocking {
        val seed = Song("Get Lucky", "Daft Punk", "https://music.youtube.com/watch?v=5NV6Rdv1a3I")
        val client = PlaylistRadioClient(Yt.http, fallbackSearch = { _, _ -> error("The native radio response must supply this smoke test.") })
        val songs = client.songs(listOf(seed), ListeningPreferences())
        println("Native radio suggestions: " + songs.take(5).joinToString(" | ") { "${it.artist}: ${it.title}" })
        assertTrue("Provider radio returned no usable new music", songs.isNotEmpty())
        assertTrue(songs.all { it.title.isNotBlank() && it.artist.isNotBlank() && it.url != seed.url })
    }
}
