package app.tunes

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Runs the production mpv command gate against a manual audio-thread queue, with no audio/network. */
class QueuedPlayerControlsTest {
    private val first = Song("First", "Artist", "https://music.youtube.com/watch?v=first")
    private val second = Song("Second", "Artist", "https://music.youtube.com/watch?v=second")

    @Test
    fun remotePauseCanCancelPendingRadioWithoutAllowingSkipsDuringLoading() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        var state = NowPlaying(listOf(first), 0, playing = true, loading = true)
        val actions = mutableListOf<String>()
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { state }) { action, _ ->
            actions += action
            state = state.copy(playing = false, loading = false)
        }
        val skip = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        queue.removeFirst().invoke()
        assertFalse(skip.await())
        val pause = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "pause") { true } }
        queue.removeFirst().invoke()
        assertTrue(pause.await())
        assertEquals(listOf("pause"), actions)
        assertFalse(state.playing)
        assertFalse(state.loading)
    }

    @Test
    fun localPlayAlreadyQueuedBeforeRemoteSkipMakesItsOriginalTargetStale() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        var state = NowPlaying(listOf(first), 0, true)
        var skips = 0
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { state }) { _, _ -> skips++ }
        // The player may still expose the original song while a local change waits behind audio loading.
        queue.addLast { state = NowPlaying(listOf(second), 0, true) }
        val request = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        assertFalse(request.isCompleted)
        queue.removeFirst().invoke()
        queue.removeFirst().invoke()
        assertFalse(request.await())
        assertEquals(0, skips)
    }

    @Test
    fun acceptedControlCompletesOnlyAfterTheAudioThreadAppliesIt() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        val state = NowPlaying(listOf(first, second), 0, true)
        val actions = mutableListOf<String>()
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { state }) { action, live ->
            assertEquals(first.url, live.song?.url)
            actions += action
        }
        val request = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "pause") { true } }
        assertTrue(actions.isEmpty())
        assertFalse(request.isCompleted)
        queue.removeFirst().invoke()
        assertTrue(request.await())
        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun accountSwitchAndLoadingAreRecheckedWhenQueuedWorkRuns() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        var state = NowPlaying(listOf(first), 0, true)
        var accountMatches = true
        var executed = 0
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { state }) { _, _ -> executed++ }
        val account = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "pause") { accountMatches } }
        accountMatches = false
        queue.removeFirst().invoke()
        assertFalse(account.await())
        val loading = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        state = state.copy(loading = true)
        queue.removeFirst().invoke()
        assertFalse(loading.await())
        assertEquals(0, executed)
    }

    @Test
    fun cancelledOrClosedWaitersCannotExecuteTheirLateQueueJobs() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        var executed = 0
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { NowPlaying(listOf(first), 0, true) }) { _, _ -> executed++ }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        cancelled.cancel()
        // Run before the cancelled coroutine gets a chance to resume its finally block.
        queue.removeFirst().invoke()
        cancelled.join()
        assertEquals(0, executed)
        val closed = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        controls.close()
        assertFalse(closed.await())
        queue.removeFirst().invoke()
        assertFalse(controls.control(first.url, 0, "play") { true })
        assertEquals(0, executed)
    }

    @Test
    fun queueIndexUnsupportedActionsAndPlayerFailuresAreNotReportedAsExecuted() = runBlocking {
        val queue = ArrayDeque<() -> Unit>()
        var executed = 0
        val controls = QueuedPlayerControls({ queue.addLast(it) }, { NowPlaying(listOf(first, first), 1, true) }) { _, _ ->
            executed++
            throw IllegalStateException("Audio engine stopped")
        }
        val wrongIndex = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 0, "next") { true } }
        queue.removeFirst().invoke()
        assertFalse(wrongIndex.await())
        val unsupported = async(start = CoroutineStart.UNDISPATCHED) { controls.control(first.url, 1, "delete") { true } }
        queue.removeFirst().invoke()
        assertFalse(unsupported.await())
        val failed = async(start = CoroutineStart.UNDISPATCHED) { runCatching { controls.control(first.url, 1, "next") { true } } }
        queue.removeFirst().invoke()
        assertEquals("Audio engine stopped", failed.await().exceptionOrNull()?.message)
        assertEquals(1, executed)
    }

    @Test
    fun synchronousPlayerControlsKeepPlayPauseExplicitAndRejectChangedTargets() = runBlocking {
        val player = object : MusicPlayer {
            override var now = NowPlaying(listOf(first), 0, true)
            var toggles = 0
            override fun positionMs() = 0L
            override fun play(songs: List<Song>, index: Int, positionMs: Long) = Unit
            override fun toggle() { toggles++; now = now.copy(playing = !now.playing) }
            override fun next() = Unit
            override fun previous() = Unit
        }
        assertTrue(player.control(first.url, 0, "pause"))
        assertTrue(player.control(first.url, 0, "pause"))
        assertEquals(1, player.toggles)
        assertFalse(player.control(second.url, 0, "next"))
        assertFalse(player.control(first.url, 0, "play") { false })
        assertEquals(1, player.toggles)
    }
}
