package app.tunes

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Short, synchronized LRU: signed stream links must not accumulate during endless radio playback. */
internal class AudioSourceCache(
    private val capacity: Int = 64,
    private val fresh: (String) -> Boolean = { Yt.isFresh(it) },
) {
    private val sources = LinkedHashMap<String, AudioSource>(capacity, .75f, true)
    private val loading = mutableMapOf<String, FutureTask<AudioSource>>()
    internal val size: Int get() = synchronized(sources) { sources.size }

    fun source(songUrl: String): AudioSource? = synchronized(sources) {
        prune()
        sources[songUrl]
    }

    fun put(songUrl: String, source: AudioSource) = synchronized(sources) {
        prune()
        sources[songUrl] = source
        while (sources.size > capacity) sources.remove(sources.keys.first())
    }

    fun quality(songUrl: String): AudioQuality? = synchronized(sources) { sources[songUrl]?.quality }
    /** Playback, gapless preload and handoff can all request the same signed link. */
    fun resolve(songUrl: String, fetch: () -> AudioSource): AudioSource {
        val task = synchronized(sources) {
            source(songUrl)?.let { return it }
            loading.getOrPut(songUrl) { FutureTask(fetch) }
        }
        task.run() // Only the first caller runs the loader; other songs can resolve concurrently.
        val result = try {
            task.get()
        } catch (failure: ExecutionException) {
            synchronized(sources) { loading.remove(songUrl, task) }
            throw failure.cause ?: failure
        }
        synchronized(sources) {
            if (loading.remove(songUrl, task)) put(songUrl, result)
        }
        return result
    }

    fun clear() = synchronized(sources) { sources.clear(); loading.clear() }
    private fun prune() { sources.entries.removeAll { !fresh(it.value.url) } }
}
