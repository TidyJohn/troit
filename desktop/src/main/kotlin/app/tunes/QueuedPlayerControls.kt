package app.tunes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/** Rechecks remote targets when a queued player job runs, rather than when it was submitted. */
internal class QueuedPlayerControls(
    private val enqueue: (() -> Unit) -> Unit,
    private val state: () -> NowPlaying,
    private val execute: (String, NowPlaying) -> Unit,
) {
    private val pending = ConcurrentHashMap.newKeySet<CompletableDeferred<Result<Boolean>>>()
    @Volatile private var closed = false

    suspend fun control(expectedUrl: String, expectedIndex: Int, action: String, allowed: () -> Boolean): Boolean {
        // Parent cancellation invalidates the queued job immediately, even before this waiter resumes.
        // Store failures as values so an audio error does not cancel the caller before it can ACK failure.
        val result = CompletableDeferred<Result<Boolean>>(currentCoroutineContext()[Job])
        pending += result
        try {
            if (closed) return false
            enqueue {
                if (result.isActive) {
                    try {
                        val live = state()
                        val valid = !closed && allowed() && live.song?.url == expectedUrl &&
                            live.index == expectedIndex && (!live.loading || (action == "pause" && live.playing)) &&
                            action in setOf("next", "previous", "play", "pause")
                        if (valid) execute(action, live)
                        result.complete(Result.success(valid))
                    } catch (failure: Exception) {
                        result.complete(Result.failure(failure))
                    }
                }
            }
            return result.await().getOrThrow()
        } finally {
            pending -= result
            // A cancelled caller must not leave an executable control waiting behind a slow audio load.
            result.cancel()
        }
    }

    fun close() {
        closed = true
        pending.forEach { it.complete(Result.success(false)) }
    }
}
