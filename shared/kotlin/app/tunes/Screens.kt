package app.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

private val Corner = RoundedCornerShape(16.dp)
private val TileSize = 168.dp
private val DiscoveryMoods = listOf("Late night", "Focus", "Feel good", "Indie", "Electronic", "Slow Sunday")

typealias Play = (List<Song>, Int) -> Unit
typealias Open = (Item) -> Unit

/** Stable catalogue identity; occurrence suffixes below also support deliberate duplicate tracks. */
internal fun Item.listIdentity(): String = when (this) {
    is Song -> "song:$url"
    is Playlist -> "playlist:$browseId"
    is Artist -> "artist:$browseId"
    is Category -> "category:$browseId:${params.orEmpty()}"
    is SavedPlaylist -> "saved:$id"
    is CollaborativePlaylist -> "collaborative:$id"
}

internal fun <T> stableOccurrenceKeys(values: List<T>, identity: (T) -> String): List<String> {
    val occurrences = HashMap<String, Int>(values.size)
    return values.map { value ->
        val id = identity(value)
        val occurrence = occurrences[id] ?: 0
        occurrences[id] = occurrence + 1
        "$id#$occurrence"
    }
}

private fun Item.rowContentType(): String = when (this) {
    is Song -> "song"
    is Artist -> "artist"
    else -> "collection"
}

/** Runs blocking network work off the main thread. Failures come back as a Result; cancellation still propagates. */
suspend fun <T> io(work: () -> T): Result<T> = try {
    Result.success(withContext(Dispatchers.IO) { work() })
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

private fun Result<*>.reason() = exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()

/** One network load with a retry. [result] is null while it is running. */
class Loader<T>(private val scope: CoroutineScope, private val updates: Flow<T>) {
    constructor(scope: CoroutineScope, load: () -> T) : this(scope, flow { emit(withContext(Dispatchers.IO) { load() }) })

    var result by mutableStateOf<Result<T>?>(null)
        private set
    private var job: Job? = null

    fun start() {
        if (result == null && job?.isActive != true) retry()
    }

    fun retry() {
        job?.cancel()
        result = null
        job = scope.launch {
            try {
                updates.collect { result = Result.success(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result = Result.failure(error)
            }
        }
    }

    fun reset() {
        job?.cancel()
        job = null
        result = null
    }
}

/** Shows the loading, failed and empty states of a [Loader], and [content] once there is something to show. */
@Composable
private fun <T> Loaded(loader: Loader<List<T>>, content: @Composable (List<T>) -> Unit) {
    LaunchedEffect(loader) { loader.start() }
    val result = loader.result
    val value = result?.getOrNull()
    when {
        result == null -> Message("Loading…")
        value == null -> Message("Couldn't load this. ${result.reason()}", loader::retry)
        value.isEmpty() -> Message("Nothing here right now.")
        else -> content(value)
    }
}

/** Tapping a song plays it with the songs around it as the queue; anything else opens its page. */
private fun tap(item: Item, siblings: List<Item>, onPlay: Play, onOpen: Open, sourceIndex: Int = siblings.indexOf(item)) {
    if (item is Song) {
        val songs = siblings.filterIsInstance<Song>()
        onPlay(songs, siblings.take(sourceIndex).count { it is Song })
    } else {
        onOpen(item)
    }
}

// ---------- Shelves: Home, artist, mood and genre pages ----------

@Composable
private fun Shelves(
    sections: List<Section>,
    listState: LazyListState,
    onPlay: Play,
    onOpen: Open,
    header: (@Composable () -> Unit)? = null,
) {
    val sectionKeys = remember(sections) { stableOccurrenceKeys(sections) { it.title } }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) item(key = "page-header", contentType = "header") { header() }
        itemsIndexed(sections, key = { index, _ -> sectionKeys[index] }, contentType = { _, _ -> "shelf" }) { _, section -> MusicShelf(section, onPlay, onOpen) }
    }
}

@Composable
private fun MusicShelf(section: Section, onPlay: Play, onOpen: Open) {
    val itemKeys = remember(section.items) { stableOccurrenceKeys(section.items, Item::listIdentity) }
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    section.more?.let { more -> TextButton(onClick = { onOpen(more) }) { Text("See all") } }
                }
                Spacer(Modifier.height(if (section.more == null) 12.dp else 4.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(section.items, key = { index, _ -> itemKeys[index] }, contentType = { _, item -> item.rowContentType() }) { index, item ->
                        Tile(item) { tap(item, section.items, onPlay, onOpen, index) }
                    }
                }
            }
}

@Composable
fun HomeScreen(home: Loader<List<Section>>, listState: LazyListState, onPlay: Play, onOpen: Open, onSearch: (String) -> Unit) {
    LaunchedEffect(home) { home.start() }
    val settings = LocalListeningPreferences.current
    val result = home.result
    val sections = remember(settings, result) { settings.filterSections(result?.getOrNull().orEmpty()) }
    val sectionKeys = remember(sections) { stableOccurrenceKeys(sections) { it.title } }
    val openPreferences = LocalOpenPreferences.current
    val listening = LocalListeningLibrary.current
    val history = listening?.history.orEmpty()
    val recent = remember(history, settings) {
        history.asSequence().map { it.song }.filter(settings::allows).distinctBy { it.url }.take(8).toList()
    }
    val openLiked = LocalOpenListeningLibrary.current
    val openDownloads = LocalOpenDownloads.current
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(key = "search-music", contentType = "action") {
            Surface(onClick = { onSearch("") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TuneIcon("search", Modifier.size(22.dp))
                    Text("Search songs, artists, or albums", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item(key = "saved-music", contentType = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LibraryShortcut("Liked songs", listening?.likedSongs?.size ?: 0, "library", Modifier.weight(1f), openLiked)
                LibraryShortcut("Downloads", Downloads.entries.size, "download", Modifier.weight(1f), openDownloads)
            }
        }
        if (recent.isNotEmpty()) item(key = "recent-tracks", contentType = "shelf") {
            MusicShelf(Section("Recently played", recent), onPlay, onOpen)
        }
        item(key = "discovery-moods", contentType = "moods") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Browse by mood", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleMedium)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DiscoveryMoods) { mood ->
                        OutlinedButton(onClick = { onSearch("$mood music") }) { Text(mood) }
                    }
                }
            }
        }
        if (settings.activeExclusions > 0 || settings.market != null) item(key = "taste-summary") {
            TasteSummary(Modifier.padding(horizontal = 20.dp))
        }
        if (result == null) item(key = "feed-state", contentType = "status") {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading recommendations…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else if (result.isFailure) item(key = "feed-state", contentType = "status") { Message("Couldn't load recommendations. Check your connection and try again.", home::retry) }
        else if (sections.isEmpty()) item(key = "feed-state", contentType = "status") {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("No recommendations yet", style = MaterialTheme.typography.titleLarge)
                Text("Try another discovery region or adjust your music filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = openPreferences) { Text("Edit music preferences") }
                TextButton(onClick = home::retry) { Text("Refresh discoveries") }
            }
        }
        itemsIndexed(sections, key = { index, _ -> sectionKeys[index] }, contentType = { _, _ -> "shelf" }) { _, section -> MusicShelf(section, onPlay, onOpen) }
    }
}

@Composable
private fun LibraryShortcut(title: String, count: Int, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TuneIcon(icon, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (count == 1) "1 song" else "$count songs", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An artist, mood or genre page. */
@Composable
fun ShelfPage(item: Item, onPlay: Play, onOpen: Open, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val preferences = LocalListeningPreferences.current
    val loader = remember(item, preferences) {
        Loader(scope) {
            when (item) {
                is Artist -> Music.sections(item.browseId, preferences = preferences)
                is Category -> Music.sections(item.browseId, item.params, preferences)
                else -> emptyList()
            }
        }
    }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Back") }
        val header = @Composable {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item is Artist) {
                    Artwork(item.artwork, Modifier.size(96.dp), CircleShape)
                    Spacer(Modifier.width(16.dp))
                }
                Text(item.title, style = MaterialTheme.typography.titleLarge)
            }
        }
        Loaded(loader) { sections ->
            if (sections.size == 1) {
                // A "See all" page is one long list, which reads better as rows than as a single sideways shelf.
                val rows = sections.single().items
                val rowKeys = remember(rows) { stableOccurrenceKeys(rows, Item::listIdentity) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = "page-header", contentType = "header") { header() }
                    itemsIndexed(rows, key = { index, _ -> rowKeys[index] }, contentType = { _, row -> row.rowContentType() }) { index, row ->
                        ItemRow(row) { tap(row, rows, onPlay, onOpen, index) }
                    }
                }
            } else {
                Shelves(sections, listState, onPlay, onOpen, header)
            }
        }
    }
}

// ---------- Search ----------

private val FILTERS = listOf(
    "Songs" to Yt.SONGS,
    "Artists" to Yt.ARTISTS,
    "Albums" to Yt.ALBUMS,
    "Playlists" to Yt.PLAYLISTS,
    "Other uploads" to Yt.VIDEOS, // unreleased tracks, live sets and anything else outside the official catalogue
)

/** Lives above the screen so results survive switching tabs or opening a page. */
class SearchState(
    private val scope: CoroutineScope,
    private val fetchPage: suspend (String, String, ListeningPreferences) -> MusicSearch.Page = MusicSearch::searchPage,
    private val fetchCategories: (ListeningPreferences) -> List<Section> = Music::categories,
) {
    val resultsScroll = LazyListState()
    val categoryScroll = LazyGridState()
    private var preferences = ListeningPreferences()
    private var lastRequest: Triple<String, String, ListeningPreferences>? = null
    private var resultOwner: Triple<String, String, ListeningPreferences>? = null
    private var generation = 0L
    var query by mutableStateOf("")
    var filter by mutableStateOf(Yt.SONGS)
    var searching by mutableStateOf(false)
    var waitingForQuery by mutableStateOf(false)
        private set
    var result by mutableStateOf<Result<List<Item>>?>(null) // null until the first search
    var more by mutableStateOf<(suspend () -> MusicSearch.Page)?>(null)
    var loadingMore by mutableStateOf(false)
    var moreFailed by mutableStateOf(false)
    val categories = Loader(scope) { fetchCategories(preferences) }
    private var job: Job? = null
    private var pagingJob: Job? = null

    private fun currentRequest() = Triple(query.trim(), filter, preferences)
    private fun owns(version: Long, request: Triple<String, String, ListeningPreferences>) =
        generation == version && currentRequest() == request

    private fun cancelRequests() {
        generation++
        job?.cancel()
        pagingJob?.cancel()
        job = null
        pagingJob = null
        searching = false
        loadingMore = false
    }

    /** Invalidate immediately while the UI debounces; an old response cannot populate a new query. */
    fun updateQuery(value: String) {
        val text = value.take(200)
        if (text == query) return
        cancelRequests()
        query = text
        result = null
        resultOwner = null
        more = null
        moreFailed = false
        lastRequest = null
        waitingForQuery = text.isNotBlank()
    }

    fun updatePreferences(value: ListeningPreferences) {
        if (preferences == value) return
        preferences = value
        categories.reset()
        if (query.isNotBlank()) run()
    }

    fun clear() {
        cancelRequests()
        query = ""
        result = null
        more = null
        searching = false
        loadingMore = false
        moreFailed = false
        lastRequest = null
        resultOwner = null
        waitingForQuery = false
    }

    fun runIfChanged() {
        if (lastRequest != currentRequest()) run()
    }

    fun run() {
        if (query.isBlank()) return
        val requestedQuery = query.trim()
        val requestedFilter = filter
        val requestedPreferences = preferences
        val request = Triple(requestedQuery, requestedFilter, requestedPreferences)
        if (searching && lastRequest == request) return
        cancelRequests()
        val version = generation
        lastRequest = request
        waitingForQuery = false
        searching = true
        moreFailed = false
        more = null
        if (resultOwner != request) result = null
        job = scope.launch {
            try {
                val page = withTimeout(16_000) { fetchPage(requestedQuery, requestedFilter, requestedPreferences) }
                if (!owns(version, request)) return@launch
                result = Result.success(page.items)
                resultOwner = request
                more = page.more
                // Never suspend waiting for a list that is hidden while searching.
                resultsScroll.requestScrollToItem(0)
            } catch (_: TimeoutCancellationException) {
                if (owns(version, request)) {
                    result = Result.failure(java.io.IOException("Search took too long. Check your connection and try again."))
                    resultOwner = request
                    more = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (owns(version, request)) {
                    result = Result.failure(error)
                    resultOwner = request
                    more = null
                }
            } finally {
                if (owns(version, request)) searching = false
            }
        }
    }

    fun loadMore() {
        val next = more ?: return
        val request = resultOwner ?: return
        val version = generation
        if (loadingMore || searching || !owns(version, request)) return
        loadingMore = true
        moreFailed = false
        pagingJob = scope.launch {
            try {
                val page = withTimeout(16_000) { next() }
                if (owns(version, request)) {
                    result = Result.success((result?.getOrNull().orEmpty() + page.items).distinctBy(Item::listIdentity))
                    more = page.more
                }
            } catch (_: TimeoutCancellationException) {
                if (owns(version, request)) moreFailed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owns(version, request)) moreFailed = true
            } finally {
                if (owns(version, request)) loadingMore = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(state: SearchState, onPlay: Play, onOpen: Open, focusOnEntry: Boolean = false, onFocused: () -> Unit = {}) {
    val input = remember { FocusRequester() }
    LaunchedEffect(focusOnEntry) {
        if (focusOnEntry) { input.requestFocus(); onFocused() }
    }
    val preferences = LocalListeningPreferences.current
    val openPreferences = LocalOpenPreferences.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun submit() { focus.clearFocus(); keyboard?.hide(); state.run() }
    LaunchedEffect(state.query) {
        if (state.query.isBlank()) { state.clear(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        state.runIfChanged()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = state::updateQuery,
            label = { Text("Search music") },
            placeholder = { Text("Song, artist, or album") },
            leadingIcon = { TuneIcon("search", Modifier.size(21.dp)) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) IconButton(onClick = state::clear, modifier = Modifier.semantics { contentDescription = "Clear search" }) { TuneIcon("close", Modifier.size(18.dp)) }
                    TextButton(onClick = ::submit, enabled = state.query.isNotBlank()) { Text("Search") }
                }
            },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.focusRequester(input).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (!typing && (preferences.activeExclusions > 0 || preferences.market != null)) TasteSummary(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp))
        if (state.query.isBlank()) {
            Loaded(state.categories) { CategoryGrid(it, onOpen, state.categoryScroll) }
            return@Column
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((label, filter) in FILTERS) {
                FilterChip(
                    selected = state.filter == filter,
                    onClick = {
                        state.filter = filter
                        submit()
                    },
                    label = { Text(label) },
                )
            }
        }
        val result = state.result
        val found = remember(result, preferences) { result?.getOrNull()?.let(preferences::filterItems) }
        val foundKeys = remember(found) { stableOccurrenceKeys(found.orEmpty(), Item::listIdentity) }
        if (state.searching && found != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        when {
            (state.searching || state.waitingForQuery) && found == null -> Column {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Message("Searching…")
            }
            result == null -> Message("Type a song, artist, or album to start exploring.")
            found == null -> Message("Search failed. ${result.reason()}") { state.run() }
            found.isEmpty() -> Column {
                Message("No matches with your music preferences. Try different words or adjust your filters.")
                TextButton(onClick = openPreferences, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Edit music preferences") }
                if (state.more != null) TextButton(onClick = state::loadMore, enabled = !state.loadingMore, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(if (state.loadingMore) "Loading…" else "Search more results")
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = state.resultsScroll) {
                item(key = "result-count", contentType = "header") { Text("${found.size} results", Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(found, key = { index, _ -> foundKeys[index] }, contentType = { _, item -> item.rowContentType() }) { index, item ->
                    ItemRow(item) { tap(item, found, onPlay, onOpen, index) }
                }
                if (state.more != null) {
                    item(key = "load-more", contentType = "footer") {
                        TextButton(
                            onClick = state::loadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        ) {
                            Text(
                                when {
                                    state.loadingMore -> "Loading…"
                                    state.moreFailed -> "Couldn't load more. Try again"
                                    else -> "Show more"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Library ----------

/** Lives above the screen so an import keeps running while you browse. */
class ImportState(private val scope: CoroutineScope, private val library: Library, val spotify: SpotifyAccount? = null) {
    var links by mutableStateOf("")
    var name by mutableStateOf("")
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var report by mutableStateOf("")
    private var job: Job? = null
    private var session: SpotifyImportSession? = null
    private var sessionInput = ""
    private var sessionTitle = ""
    val canResume get() = !running && session != null && sessionInput == links &&
        sessionTitle == name.trim().ifEmpty { "Spotify import" } && done < total
    val foundCount get() = session?.songs?.size ?: 0

    fun start() {
        if (running) return
        if (links.isBlank()) {
            report = "Paste Spotify playlist, album, or song links."
            return
        }
        val text = links
        val title = name.trim().ifEmpty { "Spotify import" }
        running = true
        job = scope.launch {
            report = ""
            try {
                if (session == null || sessionInput != text || sessionTitle != title) {
                    session = null
                    done = 0; total = 0
                    sessionInput = text; sessionTitle = title
                    session = prepareSpotifyImport(text, spotify)
                    total = session!!.tracks.size
                }
                val result = session!!.run { finished, count ->
                    done = finished
                    total = count
                }
                save(result)
            } catch (cancelled: CancellationException) {
                report = if (session != null) "Import paused at $done of $total. Resume to continue, or save the songs already matched."
                    else "Import canceled before songs were read."
                throw cancelled
            } catch (failure: Exception) { report = "Import failed. ${failure.message?.take(240).orEmpty()}"
            } finally { running = false; job = null }
        }
    }

    fun cancel() { job?.cancel() }

    fun saveFound() {
        if (running) return
        val partial = session ?: return
        if (partial.songs.isNotEmpty()) save(Spotify.Imported(partial.songs, partial.notFound,
            partial.warnings + "Saved the matched songs; the unfinished import was stopped."))
    }

    private fun save(imported: Spotify.Imported) {
        val warning = imported.warnings.distinct().take(4).joinToString(" ")
        report = if (imported.songs.isEmpty()) "None of those songs could be found, so nothing was saved. Check your connection. $warning"
        else {
            library.add(SavedPlaylist(sessionTitle, imported.songs))
            links = ""; name = ""
            val missing = imported.notFound
            "Saved ${imported.songs.size} songs to \"$sessionTitle\"." +
                (if (missing.isEmpty()) "" else " Not found (${missing.size}): ${missing.take(10).joinToString("; ")}" +
                    if (missing.size > 10) "; and ${missing.size - 10} more." else ".") +
                if (warning.isEmpty()) "" else " $warning"
        }
        session = null
    }
}

@Composable
private fun CategoryGrid(sections: List<Section>, onOpen: Open, listState: LazyGridState) {
    val sectionKeys = remember(sections) { stableOccurrenceKeys(sections) { it.title } }
    val categoryKeys = remember(sections) {
        sections.mapIndexed { sectionIndex, section ->
            stableOccurrenceKeys(section.items, Item::listIdentity).map { "${sectionKeys[sectionIndex]}:$it" }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 144.dp),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "category-header:${sectionKeys[sectionIndex]}", contentType = "header", span = { GridItemSpan(maxLineSpan) }) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
            }
            itemsIndexed(section.items, key = { itemIndex, _ -> categoryKeys[sectionIndex][itemIndex] }, contentType = { _, _ -> "category" }) { _, item ->
                CategoryTile(item as? Category ?: return@itemsIndexed, onOpen)
            }
        }
    }
}

/** Catalogue color accents use the monochrome app palette. */
@Composable
private fun CategoryTile(category: Category, onOpen: Open) {
    Row(
        Modifier
            .height(64.dp)
            .clip(Corner)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onOpen(category) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        Text(
            category.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
    }
}

@Composable
internal fun AccountCard(sync: Sync) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sync across devices", style = MaterialTheme.typography.titleMedium)
        val signedIn = sync.email
        if (signedIn != null) {
            Text("Signed in as $signedIn")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = sync::poke) { Text("Sync now") }
                TextButton(onClick = sync::signOut) { Text("Sign out") }
            }
        } else {
            Text(
                "Sign in with the same account on your PC, phone and TV to share playlists and control playback.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            val ready = !sync.busy && address.isNotBlank() && password.isNotEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sync.signIn(address, password) }, enabled = ready) { Text("Sign in") }
                TextButton(onClick = { sync.signUp(address, password) }, enabled = ready) { Text("Create account") }
            }
        }
        if (sync.status.isNotEmpty()) Text(sync.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Playlist / album ----------

@Composable
fun PlaylistScreen(opened: Item, library: Library, onPlay: Play, onBack: () -> Unit,
    onCollaborativeCreated: (CollaborativePlaylist) -> Unit = {}) {
    var renaming by remember(opened) { mutableStateOf(false) }
    var collaborating by remember(opened) { mutableStateOf(false) }
    var sharing by remember(opened) { mutableStateOf(false) }
    val collaborationState = LocalCollaborativePlaylists.current
    val social = LocalSocialState.current
    var query by rememberSaveable(opened.listIdentity()) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val preferences = LocalListeningPreferences.current
    val openPreferences = LocalOpenPreferences.current
    val save = LocalSave.current
    // A saved playlist is read live, so adding, removing or a sync shows up straight away.
    val saved = (opened as? SavedPlaylist)?.let { page ->
        library.playlists.firstOrNull { it.id == page.id } ?: page.copy(songs = emptyList())
    }
    val playlist = saved ?: opened
    val loader = remember(opened, preferences) {
        Loader(scope) { if (opened is Playlist) Music.songsIn(opened.browseId, preferences) else emptyList() }
    }
    LaunchedEffect(loader) { if (opened is Playlist) loader.start() }

    val loaded = if (saved != null) Result.success(saved.songs) else loader.result
    val allSongs = loaded?.getOrNull()
    val songs = remember(allSongs, preferences) { allSongs?.filter(preferences::allows) }
    val songKeys = remember(songs) { stableOccurrenceKeys(songs.orEmpty(), Item::listIdentity) }
    val matches = remember(songs, query) { searchPlaylist(songs.orEmpty(), query) }
    val matchingSongs = remember(matches) { matches.map { it.song } }
    val listState = rememberLazyListState()
    var previousQuery by remember(opened.listIdentity()) { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (previousQuery != query) {
            // Keep the search field in view while the keyboard is open; preserve restored scroll on entry.
            listState.scrollToItem(if (songs.isNullOrEmpty()) 0 else 1)
            previousQuery = query
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "playlist-header", contentType = "header") {
            TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Back") }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            val coverSize = if (maxWidth < 360.dp) 88.dp else 120.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(playlist.artwork, Modifier.size(coverSize))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (saved != null) "YOUR PLAYLIST" else "ALBUM / PLAYLIST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        playlist.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (playlist.subtitle.isNotEmpty()) {
                        Text(
                            playlist.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(matchingSongs, 0) }, enabled = matchingSongs.isNotEmpty()) { TuneIcon("play", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play") }
                IconButton(onClick = { onPlay(matchingSongs.shuffled(), 0) }, enabled = matchingSongs.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Shuffle matching songs" }) { TuneIcon("shuffle", Modifier.size(21.dp)) }
                if (saved == null) TextButton(
                    onClick = { save(SaveRequest(songs.orEmpty(), playlist.title)) },
                    enabled = !songs.isNullOrEmpty(),
                ) { Text("Save all") }
                else TextButton(onClick = { renaming = true }) { Text("Rename") }
            }
            if (!songs.isNullOrEmpty()) Row(Modifier.padding(horizontal = 16.dp)) {
                val missing = songs.count { !Downloads.isDownloaded(it.url) }
                val running = songs.count { it.url in Downloads.progress }
                TextButton(enabled = missing > 0 && running == 0, onClick = { Downloads.download(songs) }) {
                    TuneIcon("download", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(when { running > 0 -> "Downloading · $running left"; missing == 0 -> "Downloaded"; else -> "Download all" })
                }
                if (opened is Playlist && social != null) TextButton(onClick = { sharing = true }) { Text("Send to a friend") }
            }
            Spacer(Modifier.height(8.dp))
            if (saved != null && collaborationState != null && social != null) TextButton(onClick = { collaborating = true },
                modifier = Modifier.padding(horizontal = 16.dp)) {
                TuneIcon("friends", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Make collaborative")
            }
            if (allSongs != null && songs != null && songs.size < allSongs.size) {
                TextButton(onClick = openPreferences, modifier = Modifier.padding(horizontal = 16.dp)) { Text("${allSongs.size - songs.size} songs hidden by your music preferences · Edit") }
            }
        }
        if (songs != null && songs.isNotEmpty()) item(key = "playlist-search", contentType = "search") {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    placeholder = { Text("Search in this playlist") },
                    leadingIcon = { TuneIcon("search", Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Clear playlist search" }) {
                            TuneIcon("close", Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search playlist by song or artist" },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (query.isNotBlank()) Text("${matches.size} of ${songs.size} songs", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            loaded == null -> item(key = "playlist-state", contentType = "status") { Message("Loading songs…") }
            songs == null -> item(key = "playlist-state", contentType = "status") { Message("Couldn't load the songs. ${loaded.reason()}", loader::retry) }
            songs.isEmpty() -> item(key = "playlist-state", contentType = "status") { Message("There are no playable songs here.") }
            matches.isEmpty() -> item(key = "playlist-no-matches", contentType = "status") { Message("No songs match your search. Try a song title or artist.") }
            else -> items(matches, key = { songKeys[it.queueIndex] }, contentType = { "song" }) { match ->
                val song = match.song
                val remove: (@Composable () -> Unit)? = saved?.let { list ->
                    @Composable { TextButton(onClick = { library.removeSong(list.id, song) }) { Text("Remove") } }
                }
                ItemRow(song, trailing = remove) { onPlay(songs, match.queueIndex) }
            }
        }
    }
    if (renaming && saved != null) RenamePlaylistDialog(saved, library) { renaming = false }
    if (sharing) SendToFriendDialog(opened) { sharing = false }
    if (collaborating && saved != null && collaborationState != null && social != null) {
        MakeCollaborativeDialog(collaborationState, social, saved,
            onCreated = { shared -> collaborating = false; onCollaborativeCreated(shared) }, onDismiss = { collaborating = false })
    }
}

// ---------- Shared pieces ----------

/** Square cover image. The grey background doubles as the placeholder while it loads or if it is missing. */
@Composable
fun Artwork(model: Any?, modifier: Modifier = Modifier, shape: Shape = Corner) {
    var failed by remember(model) { mutableStateOf(false) }
    var loading by remember(model) { mutableStateOf(true) }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (model == null || failed || loading) TuneIcon("music", Modifier.size(26.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
        // AsyncImage's layout constraints resolve the decode size; no full-resolution request or crossfade per tile.
        if (model != null && !failed) AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onLoading = { loading = true },
            onSuccess = { loading = false },
            onError = { failed = true; loading = false },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Tile(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(TileSize).clip(Corner).clickable(onClick = onClick)) {
        Box {
            Artwork(item.artwork, Modifier.size(TileSize), if (item is Artist) CircleShape else Corner)
            if (item is Song) Surface(color = Color.Black.copy(alpha = .7f), shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { TuneIcon("play", Modifier.size(16.dp), Color.White) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // minLines keeps every tile the same height so the row doesn't jump while scrolling
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ItemRow(item: Item, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) = ListItem(
    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    supportingContent = { Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    leadingContent = { Artwork(item.artwork, Modifier.size(48.dp), if (item is Artist) CircleShape else Corner) },
    trailingContent = trailing ?: (item as? Song)?.let { song -> @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Feedback where the download was asked for: a spinner while it runs, an arrow once it is on the device.
            if (song.url in Downloads.progress) CircularProgressIndicator(Modifier.size(15.dp).semantics { contentDescription = "Downloading" }, strokeWidth = 2.dp)
            else if (Downloads.isDownloaded(song.url)) TuneIcon("download", Modifier.size(15.dp).semantics { contentDescription = "Downloaded" }, MaterialTheme.colorScheme.primary)
            if (song.durationSeconds > 0) Text("${song.durationSeconds / 60}:${(song.durationSeconds % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LikeButton(song)
            SongQueueMenu(song)
        }
    } },
    modifier = Modifier.clickable(onClick = onClick),
)

// ---------- Save to playlist ----------

class SaveRequest(val songs: List<Song>, val suggestedName: String)

/** Lets any song row ask for the save dialog without passing a callback through every screen. */
val LocalSave = staticCompositionLocalOf<(SaveRequest) -> Unit> { {} }

@Composable
fun SaveButton(song: Song) {
    val save = LocalSave.current
    Hint("Save to playlist") {
        IconButton(onClick = { save(SaveRequest(listOf(song), "")) }, modifier = Modifier.semantics { contentDescription = "Save ${song.title} to playlist" }) { TuneIcon("plus", Modifier.size(20.dp)) }
    }
}

/** Pick a playlist or make a new one. [onDone] gets a message to show, or null if cancelled. */
@Composable
fun SaveDialog(request: SaveRequest, library: Library, onDone: (String?) -> Unit) {
    var name by remember(request) { mutableStateOf(request.suggestedName) }
    val collaborations = LocalCollaborativePlaylists.current
    var savingShared by remember(request) { mutableStateOf(false) }
    var saveError by remember(request) { mutableStateOf<String?>(null) }
    LaunchedEffect(collaborations?.userId) {
        savingShared = false; saveError = null
        collaborations?.refresh()
    }
    val count = request.songs.size
    val what = if (count == 1) "\"${request.songs.single().title}\"" else "$count songs"

    fun addTo(playlist: SavedPlaylist) {
        val added = library.addSongs(playlist.id, request.songs)
        onDone(
            when {
                added == 0 -> "Already in \"${playlist.title}\"."
                added == count -> "Saved $what to \"${playlist.title}\"."
                else -> "Added $added new songs to \"${playlist.title}\". The rest were already there."
            },
        )
    }

    fun create() {
        val title = name.trim().take(100)
        library.add(SavedPlaylist(title, request.songs.distinctBy { it.url }))
        onDone("Saved $what to \"$title\".")
    }

    AlertDialog(
        onDismissRequest = { if (!savingShared) onDone(null) },
        title = { Text("Save to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(what, maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New playlist name") },
                    singleLine = true,
                    enabled = !savingShared,
                    modifier = Modifier.fillMaxWidth(),
                )
                val playlists = library.playlists
                val sharedPlaylists = collaborations?.summaries.orEmpty()
                if (playlists.isNotEmpty() || sharedPlaylists.isNotEmpty()) {
                    Text("Or add to", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        if (sharedPlaylists.isNotEmpty()) item(key = "shared-heading", contentType = "heading") {
                            Text("Collaborative playlists", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(sharedPlaylists, key = { "shared:${it.id}" }, contentType = { "shared-playlist" }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(playlist.subtitle) },
                                modifier = Modifier.clickable(enabled = !savingShared && collaborations?.busy != true) {
                                    savingShared = true; saveError = null
                                    collaborations?.addSongs(playlist.id, request.songs) { message ->
                                        savingShared = false
                                        if (message != null) onDone(message)
                                        else saveError = collaborations.error ?: "Couldn't save to this collaboration. Please try again."
                                    }
                                },
                            )
                        }
                        if (sharedPlaylists.isNotEmpty() && playlists.isNotEmpty()) item(key = "personal-heading", contentType = "heading") {
                            Text("Personal playlists", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        items(playlists, key = { "personal:${it.id}" }, contentType = { "saved-playlist" }) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(playlist.subtitle) },
                                modifier = Modifier.clickable(enabled = !savingShared) { addTo(playlist) },
                            )
                        }
                    }
                }
                if (savingShared) Text("Saving to the shared playlist…", style = MaterialTheme.typography.bodySmall)
                saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = ::create, enabled = name.isNotBlank() && !savingShared) { Text("Create") } },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = !savingShared) { Text("Cancel") } },
    )
}

/** Loading, empty and error text for any list. Pass [onRetry] when trying again can help. */
@Composable
private fun Message(text: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
    }
}
