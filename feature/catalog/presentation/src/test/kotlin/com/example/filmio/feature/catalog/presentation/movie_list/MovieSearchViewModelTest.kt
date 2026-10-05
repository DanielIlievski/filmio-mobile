package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.CatalogPagingException
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = SearchRepositoryFake()
    private val connected = MutableStateFlow(true)
    private val observer = object : ConnectivityObserver { override val isConnected = connected }
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        MovieListViewModel(repository, observer, handle).also { store.put("list", it) }
    private fun TestScope.collect(vm: MovieListViewModel) = backgroundScope.launch {
        launch { vm.state.collect { } }
        vm.movies.flatMapLatest { it }.collect { }
    }
    private fun TestScope.changeQuery(vm: MovieListViewModel, query: String) {
        vm.state.value.queryTextState.setTextAndPlaceCursorAtEnd(query)
        Snapshot.sendApplyNotifications()
        runCurrent()
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun immediateLocalReadsAnd499500BoundaryIgnoreSurroundingWhitespace() = runTest(dispatcher) {
        val vm = vm()
        collect(vm)
        changeQuery(vm, " Inter ")
        runCurrent()
        assertEquals(listOf("Inter" to false), repository.searches)
        assertTrue(vm.state.value.isSearchActive)
        assertTrue(vm.state.value.isDebouncing)
        advanceTimeBy(499); runCurrent()
        assertEquals(1, repository.searches.size)
        changeQuery(vm, "  Inter  ")
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("Inter" to false, "Inter" to true), repository.searches)
        assertEquals("  Inter  ", vm.state.value.queryTextState.text.toString())
        assertFalse(vm.state.value.isDebouncing)
        assertTrue(vm.state.value.isSearchActive)
    }

    @Test fun rapidChangesAndClearCancelOldTimersAndOwnedStreams() = runTest(dispatcher) {
        val vm = vm()
        collect(vm); runCurrent()
        for (query in listOf("I", "In", "Inter")) {
            changeQuery(vm, query); runCurrent(); advanceTimeBy(100)
        }
        assertEquals(listOf("I" to false, "In" to false, "Inter" to false), repository.searches)
        assertEquals(listOf("Home", "I:false", "In:false"), repository.canceled)
        vm.onAction(MovieListAction.OnClearQuery); Snapshot.sendApplyNotifications(); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertTrue(repository.searches.none { it.second })
        assertEquals(2, repository.homes)
        assertFalse(vm.state.value.isSearchActive)
        assertFalse(vm.state.value.isDebouncing)
        changeQuery(vm, "  "); runCurrent()
        assertEquals(2, repository.homes)
        changeQuery(vm, "Arrival"); runCurrent()
        advanceTimeBy(499); runCurrent()
        assertEquals("Arrival" to false, repository.searches.last())
        advanceTimeBy(1); runCurrent()
        assertEquals("Arrival" to true, repository.searches.last())
    }

    @Test fun restoredInputStartsLocalAndNewRemoteWhileRetainedStreamRecollectionDoesNotRepeat() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(MovieListViewModel.QUERY_KEY to " Inter "))
        val vm = vm(handle)
        val collector = collect(vm); runCurrent()
        assertEquals(" Inter ", vm.state.value.queryTextState.text.toString())
        advanceTimeBy(500); runCurrent()
        val movies = vm.movies.value
        val editor = vm.state.value.queryTextState
        editor.edit { selection = TextRange(1, 4) }
        Snapshot.sendApplyNotifications(); runCurrent()
        collector.cancel(); runCurrent()
        advanceTimeBy(6_000); runCurrent() // State sharing has stopped during details.
        collect(vm); runCurrent()
        assertSame(movies, vm.movies.value)
        assertSame(editor, vm.state.value.queryTextState)
        assertEquals(TextRange(1, 4), vm.state.value.queryTextState.selection)
        assertEquals(listOf("Inter" to false, "Inter" to true), repository.searches)
        changeQuery(vm, "Dune")
        assertEquals("Dune", handle.get<String>(MovieListViewModel.QUERY_KEY))
    }

    @Test fun stagesSharePagingFlowAndAbandonedCallbacksAndQueuedCommandsAreIgnored() = runTest(dispatcher) {
        val vm = vm()
        collect(vm)
        changeQuery(vm, "A"); runCurrent()
        val moviesA = vm.movies.value
        val localA = vm.state.value.generation
        advanceTimeBy(500); runCurrent()
        val remoteA = vm.state.value.generation
        assertSame(moviesA, vm.movies.value)
        assertNotEquals(localA, remoteA)
        // The same presenter can still report local load states after the stage changes.
        vm.onAction(feedback(vm, source = LoadState.Error(IllegalStateException("old local source")),
            token = remoteA, withRemote = false)); runCurrent()
        assertNull(vm.state.value.refreshError)
        vm.onAction(feedback(vm, hasItems = true)); runCurrent()
        vm.onAction(MovieListAction.OnRefreshClick(remoteA)) // queued with no event receiver
        changeQuery(vm, "B"); runCurrent()
        vm.onAction(feedback(vm, remote = LoadState.Error(CatalogPagingException(DataError.Network.NO_INTERNET)), token = remoteA)); runCurrent()
        val seen = mutableListOf<MovieListEvent>()
        backgroundScope.launch { vm.events.collect { seen += it } }; runCurrent()
        assertTrue(seen.isEmpty())
        assertNull(vm.state.value.refreshError)
        assertEquals("B", vm.state.value.queryTextState.text.toString().trim())
        assertTrue(repository.canceled.contains("A:true"))
        changeQuery(vm, "A"); runCurrent()
        assertNotSame(moviesA, vm.movies.value)
        assertNotEquals(localA, vm.state.value.generation)
    }

    @Test fun localOnlyAndNonterminalRemoteNeverConfirmEmptyAndStorageFailureWins() = runTest(dispatcher) {
        val vm = vm()
        collect(vm)
        changeQuery(vm, "missing"); runCurrent()
        vm.onAction(feedback(vm, withRemote = false)); runCurrent()
        assertFalse(vm.state.value.isEmpty)
        advanceTimeBy(500); runCurrent()
        vm.onAction(feedback(vm, remote = LoadState.Loading)); runCurrent()
        vm.onAction(feedback(vm)); runCurrent()
        assertFalse(vm.state.value.isEmpty)
        vm.onAction(feedback(vm, remoteAppend = LoadState.Loading)); runCurrent()
        assertFalse(vm.state.value.isEmpty)
        vm.onAction(feedback(vm, remoteAppend = LoadState.NotLoading(true))); runCurrent()
        assertTrue(vm.state.value.isEmpty)
        assertEquals("missing", vm.state.value.queryTextState.text.toString())
        vm.onAction(feedback(vm, source = LoadState.Error(IllegalStateException("private")), remoteAppend = LoadState.NotLoading(true), hasItems = true)); runCurrent()
        assertEquals(R.string.error_storage, (vm.state.value.refreshError as UiText.Resource).id)
        assertFalse(vm.state.value.isEmpty)
    }

    @Test fun reconnectAndRecoveryRespectDebounceCurrentTokenBusyGuardsAndOfflineNoMatch() = runTest(dispatcher) {
        val vm = vm()
        collect(vm)
        val seen = mutableListOf<MovieListEvent>()
        val events = backgroundScope.launch { vm.events.collect { seen += it } }
        changeQuery(vm, "Inter"); runCurrent()
        vm.onAction(feedback(vm, withRemote = false)); runCurrent()
        connected.value = false; runCurrent()
        assertTrue(vm.state.value.hasNoCachedMatches)
        assertFalse(vm.state.value.isEmpty)
        connected.value = true; runCurrent()
        vm.onAction(MovieListAction.OnRefreshClick(vm.state.value.generation))
        assertTrue(seen.isEmpty())
        advanceTimeBy(500); runCurrent()
        assertEquals(1, repository.searches.count { it.second })
        val token = vm.state.value.generation
        vm.onAction(feedback(vm, remote = LoadState.Error(CatalogPagingException(DataError.Network.NO_INTERNET)), hasItems = true)); runCurrent()
        vm.onAction(MovieListAction.OnRetryClick(token)); vm.onAction(MovieListAction.OnRetryClick(token)); runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies(token)), seen)
        vm.onAction(feedback(vm, hasItems = true)); runCurrent()
        connected.value = false; runCurrent(); connected.value = true; runCurrent()
        assertEquals(MovieListEvent.RefreshMovies(token), seen.last())
        assertEquals("Inter", vm.state.value.queryTextState.text.toString())
        events.cancel(); runCurrent()
        connected.value = false; runCurrent(); connected.value = true; runCurrent()
        assertEquals(2, seen.size)
    }
}

private val idleSearch = LoadState.NotLoading(false)
private fun feedback(
    vm: MovieListViewModel, source: LoadState = idleSearch, remote: LoadState = idleSearch,
    remoteAppend: LoadState = idleSearch, hasItems: Boolean = false,
    token: Long = vm.state.value.generation, withRemote: Boolean = true,
): MovieListAction.OnLoadStatesChanged {
    val local = LoadStates(source, idleSearch, idleSearch)
    val mediator = if (withRemote) LoadStates(remote, idleSearch, remoteAppend) else null
    return MovieListAction.OnLoadStatesChanged(CombinedLoadStates(remote, idleSearch, remoteAppend, local, mediator), hasItems, token)
}

private class SearchRepositoryFake : CatalogRepository {
    val searches = mutableListOf<Pair<String, Boolean>>()
    val canceled = mutableListOf<String>()
    var homes = 0
    override fun getPagedMovies(): Flow<PagingData<Movie>> {
        homes++
        return stream("Home")
    }
    override fun searchMovies(query: String, fetchRemote: Boolean): Flow<PagingData<Movie>> {
        searches += query to fetchRemote
        return stream("$query:$fetchRemote")
    }
    private fun stream(owner: String) = flow {
        try { emit(PagingData.from(listOf(Movie(1, owner, null)))); awaitCancellation() }
        finally { canceled += owner }
    }
    override suspend fun fetchMovieDetails(movieId: Long) = error("Unused")
    override fun getMovieDetails(movieId: Long): Flow<Movie?> = error("Unused")
}
