package app.tunes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Search state tests use deferred local responses, never YouTube or an attached UI list. */
class SearchStateTest {
    private fun song(title: String) = Song(title, "Artist", "preview:$title")
    private fun page(title: String) = MusicSearch.Page(listOf(song(title)))
    private fun titles(state: SearchState) = state.result?.getOrNull().orEmpty().map { it.title }
    private suspend fun until(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(2) }

    private class Fixture(fetch: suspend (String, String, ListeningPreferences) -> MusicSearch.Page) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = SearchState(scope, fetch, { emptyList() })
        override fun close() { scope.cancel() }
    }

    @Test
    fun firstSearchFinishesWithoutAnAttachedResultsList() = runBlocking {
        Fixture { query, _, _ -> page(query) }.use { fixture ->
            val state = fixture.state
            state.updateQuery("first search")
            state.run()
            until { state.result?.isSuccess == true && !state.searching }
            assertEquals(listOf("first search"), titles(state))
            assertFalse(state.loadingMore)
        }
    }

    @Test
    fun latestQueryWinsEvenWhenTheOldTransportDoesNotCooperateWithCancellation() = runBlocking {
        val oldGate = CompletableDeferred<MusicSearch.Page>()
        var oldStarted = false
        Fixture { query, _, _ ->
            if (query == "old") {
                oldStarted = true
                withContext(NonCancellable) { oldGate.await() }
            } else page(query)
        }.use { fixture ->
            val state = fixture.state
            state.updateQuery("old"); state.run()
            until { oldStarted }
            state.updateQuery("new"); state.run()
            until { titles(state) == listOf("new") && !state.searching }
            oldGate.complete(page("old"))
            delay(20)
            assertEquals(listOf("new"), titles(state))
            assertFalse(state.searching)
            assertNull(state.more)
        }
    }

    @Test
    fun editingQueryInvalidatesTheOldRequestBeforeTheDebounceStartsAnotherOne() = runBlocking {
        val gate = CompletableDeferred<MusicSearch.Page>()
        var started = false
        Fixture { _, _, _ -> started = true; withContext(NonCancellable) { gate.await() } }.use { fixture ->
            val state = fixture.state
            state.updateQuery("old"); state.run()
            until { started }
            state.updateQuery("still typing")
            gate.complete(page("old"))
            delay(20)
            assertEquals("still typing", state.query)
            assertFalse(titles(state).contains("old"))
            assertNull(state.more)
        }
    }

    @Test
    fun clearCancelsTheTransportAndResetsAllBusyState() = runBlocking {
        val gate = CompletableDeferred<MusicSearch.Page>()
        var started = false
        var cancelled = false
        Fixture { _, _, _ ->
            started = true
            try { gate.await() } catch (failure: CancellationException) { cancelled = true; throw failure }
        }.use { fixture ->
            val state = fixture.state
            state.updateQuery("something"); state.run()
            until { started }
            state.clear()
            until { cancelled }
            assertEquals("", state.query)
            assertNull(state.result)
            assertNull(state.more)
            assertFalse(state.searching)
            assertFalse(state.loadingMore)
            assertFalse(state.moreFailed)
            gate.complete(page("late result"))
            delay(10)
            assertNull(state.result)
        }
    }

    @Test
    fun manualAndDebouncedSubmissionsDoNotRestartAnIdenticalActiveSearch() = runBlocking {
        val gate = CompletableDeferred<MusicSearch.Page>()
        var calls = 0
        Fixture { _, _, _ -> calls++; gate.await() }.use { fixture ->
            val state = fixture.state
            state.updateQuery("same")
            state.run(); state.run(); state.runIfChanged()
            assertEquals(1, calls)
            gate.complete(page("same"))
            until { !state.searching && state.result?.isSuccess == true }
            state.runIfChanged()
            assertEquals(1, calls)
            state.run() // Explicit Search is still allowed to refresh completed results.
            until { calls == 2 && !state.searching }
        }
    }

    @Test
    fun preferenceChangesOwnTheirResponsesAndDiscardTheEarlierMarket() = runBlocking {
        val oldGate = CompletableDeferred<MusicSearch.Page>()
        val observed = mutableListOf<ListeningPreferences>()
        Fixture { _, _, preferences ->
            observed += preferences
            if (preferences.market == null) withContext(NonCancellable) { oldGate.await() }
            else page("Indonesia results")
        }.use { fixture ->
            val state = fixture.state
            state.updateQuery("music"); state.run()
            until { observed.size == 1 }
            val changed = ListeningPreferences(market = "ID", excludedRegions = setOf("IN"))
            state.updatePreferences(changed)
            until { titles(state) == listOf("Indonesia results") && !state.searching }
            assertEquals(changed, observed.last())
            oldGate.complete(page("old region"))
            delay(20)
            assertEquals(listOf("Indonesia results"), titles(state))
        }
    }

    @Test
    fun duplicatePaginationIsSkippedAndAnOldPageCannotAppendToANewQuery() = runBlocking {
        val nextGate = CompletableDeferred<MusicSearch.Page>()
        var nextCalls = 0
        Fixture { query, _, _ ->
            if (query == "old") MusicSearch.Page(listOf(song("old first")), more = {
                nextCalls++
                withContext(NonCancellable) { nextGate.await() }
            }) else page("new first")
        }.use { fixture ->
            val state = fixture.state
            state.updateQuery("old"); state.run()
            until { !state.searching && state.more != null }
            state.loadMore(); state.loadMore()
            assertEquals(1, nextCalls)
            state.updateQuery("new"); state.run()
            until { titles(state) == listOf("new first") && !state.searching }
            nextGate.complete(page("old next"))
            delay(20)
            assertEquals(listOf("new first"), titles(state))
            assertFalse(state.loadingMore)
            assertFalse(state.moreFailed)
            assertNull(state.more)
        }
    }

    @Test
    fun failedSearchAndFailedPaginationRemainRetryable() = runBlocking {
        var firstAttempts = 0
        var nextAttempts = 0
        Fixture { _, _, _ ->
            if (++firstAttempts == 1) throw IOException("Offline fixture")
            MusicSearch.Page(listOf(song("first")), more = {
                if (++nextAttempts == 1) throw IOException("Page temporarily unavailable")
                page("next")
            })
        }.use { fixture ->
            val state = fixture.state
            state.updateQuery("retry"); state.run()
            until { state.result?.isFailure == true && !state.searching }
            assertNull(state.more)
            state.run()
            until { titles(state) == listOf("first") && !state.searching }
            state.loadMore()
            until { state.moreFailed && !state.loadingMore }
            assertEquals(listOf("first"), titles(state))
            assertNotNull(state.more)
            state.loadMore()
            until { titles(state) == listOf("first", "next") && !state.loadingMore }
            assertFalse(state.moreFailed)
            assertNull(state.more)
        }
    }

    @Test
    fun legacyDirectQueryAssignmentCannotCommitAnOldResponse() = runBlocking {
        val gate = CompletableDeferred<MusicSearch.Page>()
        var started = false
        Fixture { query, _, _ ->
            if (query == "old") { started = true; withContext(NonCancellable) { gate.await() } }
            else page(query)
        }.use { fixture ->
            val state = fixture.state
            state.query = "old"; state.run()
            until { started }
            state.query = "new"
            gate.complete(page("old"))
            delay(20)
            assertFalse(titles(state).contains("old"))
            state.run()
            until { titles(state) == listOf("new") && !state.searching }
        }
    }
}
