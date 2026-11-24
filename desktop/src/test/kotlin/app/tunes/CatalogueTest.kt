package app.tunes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CatalogueTest {
    @Test
    fun fastCatalogueIsUsableWhileAnotherIsStillLoading() = runBlocking {
        val song = Song("Track", "Artist", "url")
        val releaseHome = CountDownLatch(1)
        val snapshots = withTimeout(7000) {
            Music.progressiveHome(ListeningPreferences(), listOf(
                {
                    assertTrue("the fast catalogue was published first", releaseHome.await(5, TimeUnit.SECONDS))
                    listOf(Section("For you", listOf(song)))
                },
                { listOf(Section("Charts", listOf(song))) },
            )).onEach { releaseHome.countDown() }.toList()
        }
        assertEquals(listOf(listOf("Charts"), listOf("For you", "Charts")), snapshots.map { shelves -> shelves.map { it.title } })
    }

    @Test
    fun oneFailedCatalogueDoesNotDiscardSuccessfulDiscovery() {
        val song = Song("Get Lucky", "Daft Punk", "url")
        val home = Section("For you", listOf(song))
        val charts = Section("Charts", listOf(song))
        val result = Music.mergeHomeFeeds(listOf(
            Result.success(listOf(home)), Result.failure(IOException("Explore unavailable")), Result.success(listOf(charts)),
        ), ListeningPreferences())
        assertEquals(listOf("For you", "Charts"), result.map { it.title })
        assertEquals(listOf(listOf(song), listOf(song)), result.map { it.items })
    }

    @Test
    fun completeCatalogueFailureKeepsTheOriginalErrorForRetry() {
        val error = IOException("Offline")
        try {
            Music.mergeHomeFeeds(listOf(Result.failure(error), Result.failure(IOException("Timeout"))), ListeningPreferences())
            fail("All catalogues failing should show a retryable error")
        } catch (actual: IOException) {
            assertSame(error, actual)
        }
    }

    @Test
    fun fallbackCataloguesStillRespectExclusionsAndDeduplicateShelves() {
        val song = Song("Get Lucky", "Daft Punk", "url")
        val result = Music.mergeHomeFeeds(listOf(
            Result.failure(IOException("Home unavailable")),
            Result.success(listOf(Section("Bollywood hits", listOf(song)), Section("Charts", listOf(song)))),
            Result.success(listOf(Section("Charts", listOf(song)))),
        ), ListeningPreferences(excludedRegions = setOf("IN")))
        assertEquals(listOf("Charts"), result.map { it.title })
    }
}
