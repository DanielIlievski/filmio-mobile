package com.example.filmio.feature.catalog.presentation

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.*
import androidx.paging.testing.asSnapshot
import com.example.filmio.core.domain.*
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.*
import com.example.filmio.feature.catalog.presentation.movie_list.*
import com.example.filmio.feature.catalog.presentation.movie_detail.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMoviesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repo = SavedRepositoryFake()
    private val connectivity = object : ConnectivityObserver {
        val status = MutableStateFlow(true)
        override val isConnected = status
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun TestScope.list(handle: SavedStateHandle = SavedStateHandle()): MovieListViewModel {
        val vm = MovieListViewModel(repo, connectivity, handle)
        store.put("list", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); return vm
    }
    private fun TestScope.detail(id: Long = 7): MovieDetailViewModel {
        val vm = MovieDetailViewModel(id, repo, connectivity)
        store.put("detail$id", vm)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); return vm
    }
    private fun MovieListViewModel.save(id: Long = 7, desired: Boolean = true) = onAction(MovieListAction.OnSetFavorite(id, desired))
    private fun TestScope.collectPages(vm: MovieListViewModel) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        vm.movies.flatMapLatest { it }.collect()
    }
    private fun query(vm: MovieListViewModel, text: String) {
        vm.state.value.queryTextState.setTextAndPlaceCursorAtEnd(text); Snapshot.sendApplyNotifications()
    }

    @Test fun listWriteFailurePreservesObservedStatusAndNextToggleCanRetrySameTarget() = runTest(dispatcher) {
        val list = list(); val gate = CompletableDeferred<Unit>()
        repo.write = { _, _ -> gate.await(); Result.Error(DataError.Local.DISK_FULL) }
        list.save(); runCurrent(); assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
        gate.complete(Unit); runCurrent()
        assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
        repo.write = { id, desired -> repo.commit(id, desired); Result.Success(Unit) }
        list.save(); runCurrent()
        assertEquals(listOf(7L to true, 7L to true), repo.writes)
        assertEquals(setOf(7L), list.state.value.favoriteIds)
    }

    @Test fun listReflectsCommittedChangesAndAllowsIndependentCommands() = runTest(dispatcher) {
        val list = list()
        val gate = CompletableDeferred<Unit>()
        repo.write = { id, desired -> repo.commit(id, desired); gate.await(); Result.Success(Unit) }
        list.save(); list.save(); list.save(8); runCurrent()
        assertEquals(3, repo.writes.size)
        assertEquals(setOf(7L, 8L), list.state.value.favoriteIds)
        gate.complete(Unit); runCurrent()
        repo.write = { _, _ -> Result.Success(Unit) }
        list.save(desired = false); runCurrent()
        assertEquals(setOf(7L, 8L), list.state.value.favoriteIds)
        repo.commit(7, false); runCurrent()
        assertEquals(setOf(8L), list.state.value.favoriteIds)
    }

    @Test fun detailReadFailureDuringWriteRetainsStatusAndRecoversOnRecollection() = runTest(dispatcher) {
        val detail = MovieDetailViewModel(7, repo, connectivity)
        store.put("detail7", detail)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.write = { id, desired -> gate.await(); repo.commit(id, desired); Result.Success(Unit) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        repo.favoriteData.value = Result.Error(DataError.Local.UNKNOWN); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        assertNotNull(detail.state.value.error)
        repo.favoriteData.value = Result.Success(emptySet())
        gate.complete(Unit); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        subscription.cancel(); advanceTimeBy(5_001); runCurrent()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        assertTrue(detail.state.value.isFavorite)
        assertEquals(1, repo.detailRequests)
    }

    @Test fun detailRecollectionAllowsCommandsWhileEarlierWriteIsBusy() = runTest(dispatcher) {
        val detail = MovieDetailViewModel(7, repo, connectivity)
        store.put("detail7", detail)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repo.write = { id, desired -> gate.await(); repo.commit(id, desired); Result.Success(Unit) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        repo.favoriteData.value = Result.Error(DataError.Local.UNKNOWN); runCurrent()
        subscription.cancel(); advanceTimeBy(5_001); runCurrent()
        repo.favoriteData.value = Result.Success(emptySet())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertEquals(2, repo.writes.size)
        gate.complete(Unit); runCurrent()
        assertTrue(detail.state.value.isFavorite)
    }

    @Test fun detailReadFailureRetainsLastObservedSavedStatus() = runTest(dispatcher) {
        repo.commit(7, true)
        val detail = detail()
        repo.favoriteData.value = Result.Error(DataError.Local.UNKNOWN); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        assertNotNull(detail.state.value.error)
        repo.write = { _, _ -> Result.Error(DataError.Local.DISK_FULL) }
        detail.onAction(MovieDetailAction.OnSetFavorite(false)); runCurrent()
        assertEquals(listOf(7L to false), repo.writes)
        assertTrue(detail.state.value.isFavorite)
    }

    @Test fun defaultDetailStatusAllowsWritesWhileListWaitsForObservation() = runTest(dispatcher) {
        repo.readGate = CompletableDeferred()
        val list = list(); val detail = detail()
        assertFalse(detail.state.value.isFavorite)
        list.save(); detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertEquals(listOf(7L to true), repo.writes)
        assertFalse(detail.state.value.isFavorite)
        detail(0).onAction(MovieDetailAction.OnSetFavorite(true)); detail(-1).onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertTrue(repo.observedIds.all { it > 0 }); assertEquals(1, repo.writes.size)
        repo.readGate!!.complete(Unit); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        list.save(0); list.save(-1); runCurrent(); assertEquals(1, repo.writes.size)
    }

    @Test fun summaryOnlySaveSynchronizesListDetailSavedAndRecreatedConsumers() = runTest(dispatcher) {
        val list = list(); val detail = detail()
        assertNull(detail.state.value.movie!!.details)
        val savedEmissions = mutableListOf<PagingData<Movie>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.getPagedSavedMovies().collect { savedEmissions += it } }
        list.save(); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        assertEquals(listOf(7L), repo.getPagedSavedMovies().asSnapshot().map { it.id })
        detail.onAction(MovieDetailAction.OnSetFavorite(false)); runCurrent()
        assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
        assertTrue(repo.getPagedSavedMovies().asSnapshot().isEmpty()); assertTrue(savedEmissions.size >= 3)
        assertNotNull(detail.state.value.movie)
        store.clear(); runCurrent()
        assertFalse(detail().state.value.isFavorite)
        assertEquals(emptySet<Long>(), list().state.value.favoriteIds)
    }

    @Test fun conflictingConsumersSettleEvenWhenIntermediateMembershipIsConflated() = runTest(dispatcher) {
        val list = list(); val detail = detail()
        list.save()
        detail.onAction(MovieDetailAction.OnSetFavorite(false))
        runCurrent()
        assertEquals(listOf(7L to true, 7L to false), repo.writes)
        assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
        assertFalse(detail.state.value.isFavorite)
    }

    @Test fun failedUnsaveKeepsSavedRowAndNextToggleRemovesOnlyMembership() = runTest(dispatcher) {
        repo.commit(7, true)
        val list = list(SavedStateHandle(mapOf(MovieListViewModel.VIEW_KEY to CatalogView.SAVED.name)))
        repo.write = { _, _ -> Result.Error(DataError.Local.UNKNOWN) }
        list.save(desired = false); runCurrent()
        assertEquals(setOf(7L), list.state.value.favoriteIds)
        assertEquals(listOf(7L), repo.getPagedSavedMovies().asSnapshot().map { it.id })
        repo.write = { id, desired -> repo.commit(id, desired); Result.Success(Unit) }
        list.save(desired = false); runCurrent()
        assertEquals(listOf(7L to false, 7L to false), repo.writes)
        assertEquals(CatalogView.SAVED, list.state.value.catalogView)
        assertTrue(repo.getPagedSavedMovies().asSnapshot().isEmpty())
        assertTrue(repo.local.value.any { it.id == 7L }); assertEquals(0, repo.homeRequests)
    }

    @Test fun detailUsesContinuousObservationWithoutPostWriteReads() = runTest(dispatcher) {
        val detail = detail()
        val completion = CompletableDeferred<Unit>()
        repo.write = { id, desired -> repo.commit(id, desired); completion.await(); Result.Success(Unit) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        repo.commit(7, false); runCurrent()
        completion.complete(Unit); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        assertEquals(listOf(7L), repo.observedIds)

        repo.write = { _, _ -> Result.Success(Unit) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        repo.commit(7, true); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        assertEquals(listOf(7L), repo.observedIds)
        detail(99).onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent(); assertEquals(2, repo.writes.size)
    }

    @Test fun independentListWriteFailuresPreserveObservedMembership() = runTest(dispatcher) {
        val list = list(); val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        repo.write = { id, _ -> (if (id == 7L) first else second).await(); Result.Error(DataError.Local.DISK_FULL) }
        list.save(); list.save(8); runCurrent()
        second.complete(Unit); runCurrent(); assertEquals(listOf(7L to true, 8L to true), repo.writes)
        assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
        first.complete(Unit); runCurrent(); assertEquals(emptySet<Long>(), list.state.value.favoriteIds)
    }

    @Test fun recollectionReadsCurrentCommittedMembershipWithoutRepeatingEntryFetch() = runTest(dispatcher) {
        val vm = MovieDetailViewModel(7, repo, connectivity); store.put("recollect", vm)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); first.cancel(); advanceTimeBy(5_001); runCurrent()
        repo.commit(7, true)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent(); assertTrue(vm.state.value.isFavorite); assertEquals(1, repo.detailRequests)
    }

    @Test fun detailFavoriteObserverStopsWithStateSubscriptionAndViewModelClearing() = runTest(dispatcher) {
        val detail = MovieDetailViewModel(7, repo, connectivity)
        store.put("detail7", detail)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        assertEquals(1, repo.activeFavoriteObservers)
        subscription.cancel(); advanceTimeBy(5_001); runCurrent()
        assertEquals(0, repo.activeFavoriteObservers)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { detail.state.collect() }
        runCurrent()
        assertEquals(1, repo.activeFavoriteObservers)
        store.clear(); runCurrent()
        assertEquals(0, repo.activeFavoriteObservers)
    }

    @Test fun savedRestorationSearchAndReconnectRemainLocal() = runTest(dispatcher) {
        repo.commit(7, true)
        val handle = SavedStateHandle(mapOf(MovieListViewModel.VIEW_KEY to CatalogView.SAVED.name,
            MovieListViewModel.QUERY_KEY to " Arrival "))
        val list = list(handle); collectPages(list); runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(CatalogView.SAVED, list.state.value.catalogView)
        assertFalse(list.state.value.isDebouncing); assertEquals(listOf("Arrival"), repo.savedQueries)
        assertEquals(0, repo.homeRequests); assertTrue(repo.searches.isEmpty())
        val events = mutableListOf<MovieListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.events.collect { events += it } }
        connectivity.status.value = false; runCurrent(); connectivity.status.value = true; runCurrent()
        list.onAction(MovieListAction.OnRefreshClick(list.state.value.generation)); runCurrent()
        assertTrue(events.isEmpty()); assertTrue(repo.searches.isEmpty())
        list.save(desired = false); runCurrent()
        assertEquals(" Arrival ", list.state.value.queryTextState.text.toString())
        list.onAction(MovieListAction.OnClearQuery); Snapshot.sendApplyNotifications(); runCurrent()
        assertEquals(CatalogView.SAVED, list.state.value.catalogView); assertEquals(0, repo.homeRequests)
    }

    @Test fun scopeSwitchCancelsSearchAndRetainsBlankAllGeneration() = runTest(dispatcher) {
        val list = list(); collectPages(list); runCurrent()
        val home = list.movies.value
        query(list, "Arrival"); runCurrent(); advanceTimeBy(501); runCurrent()
        assertTrue(repo.searches.contains("Arrival" to true))
        val oldGeneration = list.state.value.generation
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.SAVED)); runCurrent()
        assertTrue(repo.canceledSearches.contains("Arrival"))
        assertFalse(list.state.value.isDebouncing)
        list.onAction(MovieListAction.OnLoadStatesChanged(loads(LoadState.Error(CatalogPagingException(DataError.Network.NO_INTERNET)), true), false, oldGeneration))
        assertNull(list.state.value.refreshError)
        list.onAction(MovieListAction.OnClearQuery); Snapshot.sendApplyNotifications(); runCurrent()
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.ALL)); runCurrent()
        assertSame(home, list.movies.value); assertEquals(1, repo.homeRequests)
    }

    @Test fun rapidQueryAndScopeChangesKeepLatestSelectionAndDebounceOnlyAllSearch() = runTest(dispatcher) {
        val list = list(); collectPages(list); runCurrent()
        query(list, "Arrival")
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.SAVED))
        runCurrent(); advanceTimeBy(501); runCurrent()
        assertEquals(CatalogView.SAVED, list.state.value.catalogView)
        assertFalse(list.state.value.isDebouncing)
        assertTrue(repo.savedQueries.contains("Arrival"))
        assertTrue(repo.searches.isEmpty())

        query(list, "Moon")
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.ALL))
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.SAVED))
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.ALL))
        runCurrent()
        assertEquals(CatalogView.ALL, list.state.value.catalogView)
        assertTrue(list.state.value.isDebouncing)
        assertFalse(repo.searches.any { it.second })
        advanceTimeBy(501); runCurrent()
        assertFalse(list.state.value.isDebouncing)
        assertEquals(listOf("Moon" to true), repo.searches.filter { it.second })
    }

    @Test fun savedEmptyAndReadFailureAreSourceOnlyAndPreserveFavoriteState() = runTest(dispatcher) {
        val list = list(); repo.commit(7, true); runCurrent()
        list.onAction(MovieListAction.OnCatalogViewChange(CatalogView.SAVED)); runCurrent()
        val generation = list.state.value.generation
        list.onAction(MovieListAction.OnLoadStatesChanged(loads(LoadState.NotLoading(false)), false, generation)); runCurrent()
        assertTrue(list.state.value.isEmpty); assertFalse(list.state.value.isInitialLoading)
        assertEquals(setOf(7L), list.state.value.favoriteIds)
        list.onAction(MovieListAction.OnLoadStatesChanged(loads(LoadState.Error(IllegalStateException())), false, generation)); runCurrent()
        assertFalse(list.state.value.isEmpty); assertNotNull(list.state.value.refreshError)
        val events = mutableListOf<MovieListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.events.collect { events += it } }
        list.onAction(MovieListAction.OnRetryClick(generation)); runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies(generation)), events)
        assertEquals(1, repo.homeRequests)
    }

    @Test fun detailWriteFailurePreservesObservedStatusAndRemainsRetryableAfterNetworkCompletion() = runTest(dispatcher) {
        val network = CompletableDeferred<Unit>()
        repo.fetch = { network.await(); Result.Success(Unit) }
        val detail = detail()
        repo.write = { _, _ -> Result.Error(DataError.Local.DISK_FULL) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        assertNotNull(detail.state.value.error)
        network.complete(Unit); runCurrent()
        assertFalse(detail.state.value.isFavorite)
        assertFalse(detail.state.value.isLoading)
        repo.write = { id, desired -> repo.commit(id, desired); Result.Success(Unit) }
        detail.onAction(MovieDetailAction.OnSetFavorite(true)); runCurrent()
        assertTrue(detail.state.value.isFavorite)
        assertEquals(listOf(7L to true, 7L to true), repo.writes)
    }

    @Test fun cancellationAfterCommitAndRelaunchReadCommittedValue() = runTest(dispatcher) {
        val list = list(); repo.write = { id, desired -> repo.commit(id, desired); awaitCancellation() }
        list.save(); runCurrent(); store.clear(); runCurrent()
        assertEquals(setOf(7L), repo.favoriteData.value.let { (it as Result.Success).data })
        val recreated = list()
        assertEquals(setOf(7L), recreated.state.value.favoriteIds)
    }

    private fun loads(refresh: LoadState, remote: Boolean = false): CombinedLoadStates {
        val source = LoadStates(refresh, LoadState.NotLoading(true), LoadState.NotLoading(true))
        return CombinedLoadStates(refresh, source.prepend, source.append, source, source.takeIf { remote })
    }
}

private class SavedRepositoryFake : CatalogRepository {
    val favoriteData = MutableStateFlow<Result<Set<Long>, DataError.Local>>(Result.Success(emptySet()))
    val local = MutableStateFlow(listOf(Movie(7, "Arrival", "Summary"), Movie(8, "Moon", null)))
    val writes = mutableListOf<Pair<Long, Boolean>>()
    val observedIds = mutableListOf<Long>()
    val savedQueries = mutableListOf<String>()
    val searches = mutableListOf<Pair<String, Boolean>>()
    val canceledSearches = mutableListOf<String>()
    var homeRequests = 0
    var activeFavoriteObservers = 0
    var detailRequests = 0
    var readGate: CompletableDeferred<Unit>? = null
    var fetch: suspend () -> EmptyResult<DataError> = { Result.Success(Unit) }
    var write: suspend (Long, Boolean) -> EmptyResult<DataError.Local> = { id, desired -> commit(id, desired); Result.Success(Unit) }
    fun commit(id: Long, desired: Boolean) {
        val ids = (favoriteData.value as Result.Success).data
        favoriteData.value = Result.Success(if (desired) ids + id else ids - id)
    }
    override fun observeFavoriteMovieIds() = flow {
        activeFavoriteObservers++
        try {
            readGate?.await()
            emitAll(favoriteData.map { when (it) {
                is Result.Success -> it.data
                is Result.Error -> throw CatalogStorageException(it.error)
            } })
        } finally { activeFavoriteObservers-- }
    }
    override fun observeIsMovieFavorite(movieId: Long): Flow<Boolean> {
        observedIds += movieId
        return observeFavoriteMovieIds().map { movieId in it }
    }
    override suspend fun setMovieFavorite(movieId: Long, isFavorite: Boolean): EmptyResult<DataError.Local> {
        writes += movieId to isFavorite; return write(movieId, isFavorite)
    }
    override fun getPagedMovies(): Flow<PagingData<Movie>> { homeRequests++; return local.map { PagingData.from(it) } }
    override fun getPagedSavedMovies(query: String): Flow<PagingData<Movie>> {
        savedQueries += query
        return combine(local, observeFavoriteMovieIds()) { movies, ids ->
            PagingData.from(movies.filter { it.id in ids && it.title.contains(query, ignoreCase = true) },
                sourceLoadStates = LoadStates(LoadState.NotLoading(false), LoadState.NotLoading(true), LoadState.NotLoading(true)))
        }
    }
    override fun searchMovies(query: String, fetchRemote: Boolean) = flow {
        searches += query to fetchRemote
        try { emit(PagingData.empty<Movie>()); awaitCancellation() }
        finally { canceledSearches += query }
    }
    override suspend fun fetchMovieDetails(movieId: Long): EmptyResult<DataError> { detailRequests++; return fetch() }
    override fun getMovieDetails(movieId: Long) = local.map { list -> list.find { it.id == movieId } }
}
