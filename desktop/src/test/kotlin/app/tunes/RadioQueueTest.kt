package app.tunes

import org.junit.Assert.*
import org.junit.Test

class RadioQueueTest {
    private fun song(index: Int) = Song("Track $index", "Artist ${index % 8}",
        "https://music.youtube.com/watch?v=%011d".format(index), "https://art.example/large-$index", 180)

    @Test
    fun thousandsOfAutoplayBatchesKeepAConstantQueueAndLightweightHistory() {
        val original = (0 until 1000).map(::song)
        val session = RadioQueueSession().apply { reset(original) }
        var queue = original
        var total = 1000
        repeat(3_000) {
            val discoveries = (total until total + RadioQueueSession.MAX_BATCH).map(::song)
            val exclusions = session.exclusions(queue)
            assertFalse(discoveries.any(exclusions::contains))
            assertTrue("Original playlist stays excluded after it leaves the visible queue", exclusions.contains(original.first()))
            val appended = appendRadioQueue(queue, queue.lastIndex, discoveries)
            session.remember(discoveries)
            queue = appended.songs
            assertEquals(discoveries.first().url, queue[appended.nextIndex].url)
            assertTrue(queue.size <= RadioQueueSession.PLAYED_TAIL + RadioQueueSession.MAX_BATCH)
            assertTrue(session.recentIdentityCount <= RadioQueueSession.MAX_IDENTITIES)
            assertEquals(1000, session.originalIdentityCount)
            assertTrue(session.seeds.size <= 3)
            assertTrue(session.seeds.all { it.artwork == null })
            total += RadioQueueSession.MAX_BATCH
        }
        assertEquals(1000, original.size)
        assertEquals(1000, session.recentIdentityCount)
        assertTrue(session.exclusions(queue).ids.size <= 2000)
        assertTrue(session.exclusions(queue).recordings.size <= 2000)
    }

    @Test
    fun trimmingDropsOnlyPlayedTracksAndPreservesEveryUpcomingTrackInALargePlaylist() {
        val original = (0 until 1000).map(::song)
        val appended = appendRadioQueue(original, 100, listOf(song(1001)))
        assertEquals(original[101], appended.songs[appended.nextIndex])
        assertEquals(original.drop(101), appended.songs.subList(appended.nextIndex, appended.songs.lastIndex))
        assertEquals(original[100], appended.songs[appended.nextIndex - 1])
        assertEquals(song(1001), appended.songs.last())
        assertEquals(1000, original.size)
    }

    @Test
    fun historyProtectsAlternateUrlsAndRecordingsAndResetReleasesPreviousSession() {
        val old = song(10)
        val session = RadioQueueSession().apply { reset(listOf(old)); remember(listOf(song(20))) }
        val exclusions = session.exclusions(emptyList())
        assertTrue(exclusions.contains(old.copy(url = "https://youtu.be/00000000010")))
        assertTrue(exclusions.contains(old.copy(title = "TRACK 10 (Official Audio)", url = song(30).url)))
        assertTrue(exclusions.contains(song(20)))
        assertEquals(listOf(song(40)), PlaylistRadioClient.novel(listOf(listOf(old, song(20), song(40))),
            emptyList(), ListeningPreferences(), exclusions))
        session.reset(listOf(song(100)))
        assertEquals(0, session.recentIdentityCount)
        assertEquals(1, session.originalIdentityCount)
        assertFalse(session.exclusions(emptyList()).contains(old))
    }
}
