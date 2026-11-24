package app.tunes

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioSourceCacheTest {
    private val quality = AudioQuality("Opus", 160)

    @Test
    fun longRadioSessionsCapSignedSourcesAndKeepRecentlyUsedEntries() {
        val cache = AudioSourceCache(capacity = 64, fresh = { true })
        repeat(10_000) { cache.put("song-$it", AudioSource("https://audio.example/$it", quality)) }
        assertEquals(64, cache.size)
        assertNull(cache.source("song-0"))
        assertNotNull(cache.source("song-9936"))
        cache.put("new", AudioSource("https://audio.example/new", quality))
        assertNotNull("Read entries are promoted in LRU order", cache.source("song-9936"))
        assertNull(cache.source("song-9937"))
        assertEquals(quality, cache.quality("new"))
    }

    @Test
    fun expiredSourcesAreEvictedAndClearingReleasesMetadata() {
        var fresh = true
        val cache = AudioSourceCache(capacity = 64, fresh = { fresh })
        cache.put("song", AudioSource("https://audio.example/signed", quality))
        fresh = false
        assertNull(cache.source("song"))
        assertEquals(0, cache.size)
        fresh = true
        cache.put("next", AudioSource("https://audio.example/next", quality))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.quality("next"))
    }

    @Test fun overlappingRequestsShareOneLookupAndInvalidationFencesOldResults() {
        val cache = AudioSourceCache(fresh = { true })
        val calls = AtomicInteger()
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lookup = {
            calls.incrementAndGet(); fetching.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            AudioSource("stream", quality)
        }
        val first = CompletableFuture.supplyAsync { cache.resolve("track", lookup) }
        assertTrue(fetching.await(5, TimeUnit.SECONDS))
        val second = CompletableFuture.supplyAsync { cache.resolve("track", lookup) }
        release.countDown()
        assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
        assertEquals(1, calls.get())

        cache.clear()
        val stale = cache.resolve("track") {
            cache.clear()
            AudioSource("old", quality)
        }
        assertEquals("old", stale.url)
        assertNull(cache.source("track"))
        assertEquals("fresh", cache.resolve("track") { AudioSource("fresh", quality) }.url)
    }
}
