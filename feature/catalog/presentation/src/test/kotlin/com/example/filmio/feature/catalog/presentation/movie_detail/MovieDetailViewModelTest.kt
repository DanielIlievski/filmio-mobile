package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import com.example.filmio.core.domain.*
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.model.MovieDetails
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.CatalogStorageException
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = DetailRepositoryFake()
    private val connectivity = DetailConnectivityFake()
    private val cached = Movie(7, "Arrival", "A linguist meets visitors.", details = MovieDetails(runtimeMinutes = 116))
    private fun vm(id: Long = 7, observer: ConnectivityObserver = connectivity) =
        MovieDetailViewModel(id, repository, observer).also { store.put("detail$id", it) }
    private fun TestScope.collectState(vm: MovieDetailViewModel): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    private fun TestScope.collectEvents(vm: MovieDetailViewModel, seen: MutableList<MovieDetailEvent> = mutableListOf()): Job =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { seen += it } }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun UiText?.resourceId() = (this as? UiText.Resource)?.id

    @Test fun firstTimeOfflineIsUnavailableAndSummaryOfflineRemainsVisible() = runTest(dispatcher) {
        repository.fetch = { _ -> Result.Error(DataError.Network.NO_INTERNET) }
        val empty = vm()
        collectState(empty)
        runCurrent()
        assertNull(empty.state.value.movie)
        assertFalse(empty.state.value.isLoading)
        assertEquals(R.string.error_offline, empty.state.value.error.resourceId())
        repository.local.value = Result.Success(cached.copy(details = null))
        runCurrent()
        assertEquals(cached.copy(details = null), empty.state.value.movie)
        assertEquals(R.string.error_offline, empty.state.value.error.resourceId())
        assertNull(empty.state.value.movie!!.details)
    }

    @Test fun remoteNotFoundAndCachedRefreshFailureRetainContentWithSafeText() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        repository.fetch = { _ -> Result.Error(DataError.Network.NOT_FOUND) }
        val vm = vm(); collectState(vm); collectEvents(vm); runCurrent()
        assertEquals(cached, vm.state.value.movie)
        assertEquals(R.string.error_movie_not_found, vm.state.value.error.resourceId())
        repository.fetch = { _ -> Result.Error(DataError.Local.DISK_FULL) }
        connectivity.signals.emit(false); runCurrent()
        connectivity.signals.emit(true); runCurrent()
        assertEquals(cached, vm.state.value.movie)
        assertEquals(R.string.error_disk_full, vm.state.value.error.resourceId())
        assertEquals(listOf(7L, 7L), repository.requests)
    }

    @Test fun entryFetchStartsBeforeTheLocalSourceEmits() = runTest(dispatcher) {
        val readGate = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        repository.beforeObserve = { readGate.await() }
        repository.local.value = Result.Success(cached)
        repository.fetch = { _ -> fetchGate.await(); Result.Success(Unit) }
        val vm = vm(); collectState(vm); runCurrent()
        assertEquals(listOf(7L), repository.requests)
        assertEquals(1, repository.inFlight)
        assertNull(vm.state.value.movie)
        readGate.complete(Unit); runCurrent()
        assertEquals(cached, vm.state.value.movie)
        assertEquals(1, repository.inFlight)
        fetchGate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertEquals(0, repository.inFlight)
    }

    @Test fun cachedContentAndLocalUpdatesAppearBeforeFetchCompletes() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached.copy(details = null))
        val gate = CompletableDeferred<Unit>()
        repository.fetch = { _ -> gate.await(); Result.Success(Unit) }
        val vm = vm(); collectState(vm); runCurrent()
        assertEquals(cached.copy(details = null), vm.state.value.movie)
        assertEquals(1, repository.inFlight)
        repository.local.value = Result.Success(cached); runCurrent()
        assertEquals(cached, vm.state.value.movie)
        assertEquals(1, repository.inFlight)
        gate.complete(Unit); runCurrent()
        assertEquals(0, repository.inFlight)
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test fun fetchCompletionDoesNotManufactureLocalContent() = runTest(dispatcher) {
        val vm = vm(); collectState(vm); runCurrent()
        assertNull(vm.state.value.movie)
        assertFalse(vm.state.value.isLoading)
        repository.local.value = Result.Success(cached.copy(details = MovieDetails())); runCurrent()
        assertNotNull(vm.state.value.movie!!.details)
        assertEquals(listOf(7L), repository.requests)
    }

    @Test fun recollectionReadsCurrentLocalContentWithoutRepeatingEntryFetch() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached.copy(details = MovieDetails()))
        val vm = vm(); val first = collectState(vm); runCurrent()
        assertEquals(MovieDetails(), vm.state.value.movie!!.details)
        assertEquals(listOf(7L), repository.requests)
        first.cancel(); advanceTimeBy(5_001); runCurrent()
        assertEquals(0, repository.activeObservers)
        val updated = cached.copy(title = "Updated while unsubscribed")
        repository.local.value = Result.Success(updated)
        collectState(vm); runCurrent()
        assertEquals(updated, vm.state.value.movie)
        assertEquals(listOf(7L), repository.requests)
    }

    @Test fun reopeningAlreadyFetchedDetailsAlwaysStartsAnotherFetch() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        val first = vm(); collectState(first); runCurrent()
        store.clear(); runCurrent()
        val reopened = vm(); collectState(reopened); runCurrent()
        assertEquals(cached, reopened.state.value.movie)
        assertEquals(listOf(7L, 7L), repository.requests)
        assertEquals(2, repository.subscriptions)
    }

    @Test fun initialAndRepeatedReconnectNeverOverlapBusyFetch() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        val gate = CompletableDeferred<Unit>()
        repository.fetch = { _ -> gate.await(); Result.Success(Unit) }
        val vm = vm(); collectState(vm); collectEvents(vm); runCurrent()
        repeat(10) {
            connectivity.signals.emit(false); runCurrent()
            connectivity.signals.emit(true); runCurrent()
        }
        assertEquals(listOf(7L), repository.requests)
        assertEquals(1, repository.maxInFlight)
        gate.complete(Unit); runCurrent()
        assertFalse(vm.state.value.isLoading)
    }

    @Test fun invalidIdsAreRejectedByRepositoryPreconditionBeforeWorkStarts() = runTest(dispatcher) {
        for (id in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { vm(id) }
        }
        assertEquals(0, repository.subscriptions)
        assertTrue(repository.requests.isEmpty())
    }

    @Test fun clearCancelsOwnedFetchAndSourceWithoutErrorOrNavigationEffects() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        var canceled = false
        repository.fetch = { _ -> try { awaitCancellation() } finally { canceled = true } }
        val vm = vm(); collectState(vm)
        val seen = mutableListOf<MovieDetailEvent>(); collectEvents(vm, seen); runCurrent()
        store.clear(); runCurrent()
        assertTrue(canceled)
        assertEquals(0, repository.activeObservers)
        assertEquals(0, connectivity.collectors)
        assertNull(vm.state.value.error)
        assertTrue(seen.isEmpty())
    }

    @Test fun separateImmutableIdsCannotPublishAnotherDestinationsContent() = runTest(dispatcher) {
        repository.observe = { id -> flowOf(Movie(id, "Movie $id", null)) }
        val first = vm(7); val second = vm(8)
        collectState(first); collectState(second); runCurrent()
        assertEquals(7L, first.state.value.movie!!.id)
        assertEquals(8L, second.state.value.movie!!.id)
        assertEquals(listOf(7L, 8L), repository.requests)
    }

    @Test fun activeReconnectFetchesOnceAndInitialOrDuplicateAvailableDoesNothing() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        val vm = vm(); collectState(vm)
        val events = collectEvents(vm); runCurrent()
        connectivity.signals.emit(true); runCurrent()
        assertEquals(listOf(7L), repository.requests)
        connectivity.signals.emit(false); runCurrent()
        assertEquals(false, vm.state.value.isConnected)
        connectivity.signals.emit(true); runCurrent()
        connectivity.signals.emit(true); runCurrent()
        assertEquals(listOf(7L, 7L), repository.requests)
        events.cancel(); runCurrent()
        assertEquals(0, connectivity.collectors)
        connectivity.signals.emit(false); connectivity.signals.emit(true); runCurrent()
        collectEvents(vm); runCurrent()
        assertEquals(listOf(7L, 7L), repository.requests)
    }

    @Test fun connectivityObservationUnavailableStillAllowsLocalContentAndInitialFetch() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        val vm = vm(observer = object : ConnectivityObserver { override val isConnected = emptyFlow<Boolean>() })
        collectState(vm); collectEvents(vm); runCurrent()
        assertEquals(cached, vm.state.value.movie)
        assertNull(vm.state.value.isConnected)
        assertEquals(listOf(7L), repository.requests)
    }

    @Test fun typedErrorLocalizationAndBackEffectsStaySafeAndOneOff() = runTest(dispatcher) {
        repository.local.value = Result.Success(cached)
        val vm = vm(); collectState(vm)
        val seen = mutableListOf<MovieDetailEvent>(); collectEvents(vm, seen); runCurrent()
        for ((error, text) in listOf(DataError.Network.UNAUTHORIZED to R.string.error_configuration,
            DataError.Network.REQUEST_TIMEOUT to R.string.error_timeout,
            DataError.Network.TOO_MANY_REQUESTS to R.string.error_rate_limit,
            DataError.Network.SERIALIZATION to R.string.error_malformed,
            DataError.Network.SERVICE_UNAVAILABLE to R.string.error_service)) {
            repository.fetch = { _ -> Result.Error(error) }
            connectivity.signals.emit(false); runCurrent()
            connectivity.signals.emit(true); runCurrent()
            assertEquals(text, vm.state.value.error.resourceId())
            assertEquals(cached, vm.state.value.movie)
        }
        assertTrue(seen.isEmpty())
        vm.onAction(MovieDetailAction.OnBackClick); runCurrent()
        assertEquals(listOf(MovieDetailEvent.NavigateBack), seen)
        connectivity.signals.emit(false); runCurrent()
        connectivity.signals.emit(true); runCurrent()
        assertEquals(listOf(MovieDetailEvent.NavigateBack), seen)
    }
}

private class DetailRepositoryFake : CatalogRepository {
    override fun searchMovies(query: String, fetchRemote: Boolean): Flow<PagingData<Movie>> = error("Unused")
    val local = MutableStateFlow<Result<Movie?, DataError.Local>>(Result.Success(null))
    val requests = mutableListOf<Long>()
    var fetch: suspend (Long) -> EmptyResult<DataError> = { _ -> Result.Success(Unit) }
    var observe: ((Long) -> Flow<Movie?>)? = null
    var beforeObserve: suspend () -> Unit = {}
    var subscriptions = 0
    var activeObservers = 0
    var inFlight = 0
    var maxInFlight = 0
    override fun getPagedMovies(): Flow<PagingData<Movie>> = error("Unused")
    override fun getMovieDetails(movieId: Long): Flow<Movie?> {
        require(movieId > 0) { "Movie ID must be positive" }
        return flow {
            subscriptions++; activeObservers++
            try {
                beforeObserve()
                emitAll(observe?.invoke(movieId) ?: local.map { result ->
                    when (result) {
                        is Result.Success -> result.data
                        is Result.Error -> throw CatalogStorageException(result.error)
                    }
                })
            } finally { activeObservers-- }
        }
    }
    override suspend fun fetchMovieDetails(movieId: Long): EmptyResult<DataError> {
        requests += movieId
        inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
        return try { fetch(movieId) } finally { inFlight-- }
    }
}

private class DetailConnectivityFake : ConnectivityObserver {
    val signals = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 8).apply { tryEmit(true) }
    var collectors = 0
    override val isConnected: Flow<Boolean> = flow {
        collectors++
        try { emitAll(signals) } finally { collectors-- }
    }
}
