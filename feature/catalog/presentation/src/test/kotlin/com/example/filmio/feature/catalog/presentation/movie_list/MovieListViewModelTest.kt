package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.lifecycle.ViewModelStore
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.domain.repository.CatalogPagingException
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val connectivity = FakeConnectivityObserver()
    private val repository = FakeCatalogRepository()
    private fun vm(observer: ConnectivityObserver = connectivity) = MovieListViewModel(repository, observer).also { store.put("list", it) }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun defaultsAndLocalOnlyCompletionNeverShowFalseEmpty() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(loads())
        assertTrue(vm.state.value.isInitialLoading)
        assertFalse(vm.state.value.isEmpty)
        vm.onAction(loads(remote = LoadState.Loading))
        assertTrue(vm.state.value.isInitialLoading)
        assertFalse(vm.state.value.isEmpty)
    }

    @Test fun successfulEmptyWaitsForBothSourcesInEitherSignalOrder() = runTest(dispatcher) {
        for (localFirst in listOf(true, false)) {
            val vm = vm()
            vm.onAction(loads(source = LoadState.Loading, remote = LoadState.Loading))
            vm.onAction(if (localFirst) loads(remote = LoadState.Loading) else loads(source = LoadState.Loading))
            assertFalse(vm.state.value.isEmpty)
            vm.onAction(loads())
            assertTrue(vm.state.value.isEmpty)
            assertFalse(vm.state.value.isInitialLoading)
        }
    }

    @Test fun sourceFailureWinsInEitherOrderAndRemoteSuccessCannotEraseIt() = runTest(dispatcher) {
        val localError = LoadState.Error(IllegalStateException("private database detail"))
        for (localFirst in listOf(true, false)) {
            val vm = vm()
            vm.onAction(loads(source = LoadState.Loading, remote = LoadState.Loading))
            vm.onAction(if (localFirst) loads(source = localError, remote = LoadState.Loading) else loads(source = LoadState.Loading))
            vm.onAction(loads(source = localError))
            assertEquals(R.string.error_storage, (vm.state.value.refreshError as UiText.Resource).id)
            assertFalse(vm.state.value.isEmpty)
        }
    }

    @Test fun offlineWithoutCacheIsUnavailableAndCachedRefreshAndAppendErrorsAreSeparate() = runTest(dispatcher) {
        val vm = vm()
        val offline = LoadState.Error(CatalogPagingException(DataError.Network.NO_INTERNET))
        vm.onAction(loads(remote = offline))
        assertFalse(vm.state.value.isEmpty)
        assertFalse(vm.state.value.isInitialLoading)
        assertEquals(R.string.error_offline, (vm.state.value.refreshError as UiText.Resource).id)
        vm.onAction(loads(remote = offline, hasItems = true))
        assertNotNull(vm.state.value.refreshError)
        vm.onAction(loads(remoteAppend = offline, hasItems = true))
        assertNull(vm.state.value.refreshError)
        assertEquals(R.string.error_offline, (vm.state.value.appendError as UiText.Resource).id)
        vm.onAction(loads(sourceAppend = LoadState.Error(IllegalStateException("private")), remoteAppend = offline, hasItems = true))
        assertEquals(R.string.error_storage, (vm.state.value.appendError as UiText.Resource).id)
    }

    @Test fun settledTerminalRefreshDoesNotRequireSeeingTransientLoadingAndSurvivesRecollection() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(loads(remoteAppend = LoadState.NotLoading(true)))
        assertTrue(vm.state.value.isEmpty)
        vm.onAction(loads())
        assertTrue(vm.state.value.isEmpty)
    }

    @Test fun actionsEmitDifferentCommandsGuardBusyInputAndSuccessEmitsNothing() = runTest(dispatcher) {
        val vm = vm()
        val events = mutableListOf<MovieListEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        vm.onAction(loads(remoteAppend = LoadState.Error(CatalogPagingException(DataError.Network.REQUEST_TIMEOUT)), hasItems = true))
        vm.onAction(MovieListAction.OnRetryClick)
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies), events)
        assertTrue(vm.state.value.isAppending)
        vm.onAction(loads(hasItems = true))
        vm.onAction(MovieListAction.OnRefreshClick)
        vm.onAction(MovieListAction.OnRefreshClick)
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies, MovieListEvent.RefreshMovies), events)
        vm.onAction(loads(remote = LoadState.Loading, hasItems = true))
        vm.onAction(loads(hasItems = true))
        runCurrent()
        assertEquals(2, events.size)
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test fun allTypedFailuresAreLocalizedAndUnknownSourceTextIsNeverUsed() {
        val cases = mapOf(
            DataError.Network.UNAUTHORIZED to R.string.error_configuration,
            DataError.Network.FORBIDDEN to R.string.error_configuration,
            DataError.Network.NO_INTERNET to R.string.error_offline,
            DataError.Network.REQUEST_TIMEOUT to R.string.error_timeout,
            DataError.Network.TOO_MANY_REQUESTS to R.string.error_rate_limit,
            DataError.Network.SERIALIZATION to R.string.error_malformed,
            DataError.Network.SERVER_ERROR to R.string.error_service,
            DataError.Network.SERVICE_UNAVAILABLE to R.string.error_service,
            DataError.Local.DISK_FULL to R.string.error_disk_full,
            DataError.Local.UNKNOWN to R.string.error_storage,
        )
        for ((error, resource) in cases) assertEquals(resource, (CatalogPagingException(error).toPagingUiText() as UiText.Resource).id)
        assertEquals(R.string.error_storage, (IllegalStateException("secret").toPagingUiText() as UiText.Resource).id)
    }

    @Test fun cachedStreamReusesRepositoryWorkAcrossCollectors() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(0, repository.collections)
        assertEquals(listOf("Arrival"), vm.movies.asSnapshot().map { it.title })
        assertEquals(listOf("Arrival"), vm.movies.asSnapshot().map { it.title })
        assertEquals(1, repository.streams)
        assertEquals(1, repository.collections)
    }

    @Test fun reconnectIgnoresInitialAndDuplicatesAndStopsWithEventCollection() = runTest(dispatcher) {
        val vm = vm()
        val events = mutableListOf<MovieListEvent>()
        val collector = backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        assertEquals(1, connectivity.collectors)
        assertTrue(events.isEmpty())
        connectivity.status.value = false
        runCurrent()
        connectivity.status.value = true
        runCurrent()
        assertEquals(listOf(MovieListEvent.RefreshMovies), events)
        vm.onAction(loads(hasItems = true))
        connectivity.status.value = true
        runCurrent()
        assertEquals(1, events.size)
        collector.cancel()
        runCurrent()
        assertEquals(0, connectivity.collectors)
        connectivity.status.value = false
        runCurrent()
        connectivity.status.value = true
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        assertEquals(1, events.size)
        store.clear()
        runCurrent()
        assertEquals(0, connectivity.collectors)
    }

    @Test fun reconnectWithoutEventCollectionIsNotObservedOrQueuedForResume() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        assertEquals(0, connectivity.collectors)
        connectivity.status.value = false
        runCurrent()
        connectivity.status.value = true
        runCurrent()
        val events = mutableListOf<MovieListEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        assertTrue(events.isEmpty())
        assertFalse(vm.state.value.isRefreshing)
    }

    @Test fun connectivityObservationEndingLeavesCachedMoviesAndExplicitRecoveryAvailable() = runTest(dispatcher) {
        val vm = vm(object : ConnectivityObserver {
            override val isConnected = flowOf(false)
        })
        val events = mutableListOf<MovieListEvent>()
        val collector = backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()

        assertTrue(collector.isActive)
        assertTrue(events.isEmpty())
        assertEquals(listOf("Arrival"), vm.movies.asSnapshot().map { it.title })

        vm.onAction(loads(remote = LoadState.Error(CatalogPagingException(DataError.Network.NO_INTERNET)), hasItems = true))
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies), events)

        vm.onAction(loads(hasItems = true))
        vm.onAction(MovieListAction.OnRefreshClick)
        runCurrent()
        assertEquals(listOf(MovieListEvent.RetryMovies, MovieListEvent.RefreshMovies), events)
        assertTrue(collector.isActive)
    }

    @Test fun reconnectDuringCollectorStartupWaitsForReceiverInsteadOfDroppingRefresh() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = vm(object : ConnectivityObserver {
            override val isConnected = flow {
                emit(false)
                emit(true)
                awaitCancellation()
            }
        })
        val events = mutableListOf<MovieListEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        runCurrent()
        assertEquals(listOf(MovieListEvent.RefreshMovies), events)
        assertTrue(vm.state.value.isRefreshing)
    }

    @Test fun stoppingEventCollectionCancelsReconnectWaitingForBusyReceiver() = runTest(dispatcher) {
        val vm = vm()
        var received = 0
        val collector = backgroundScope.launch {
            vm.events.collect {
                received++
                awaitCancellation()
            }
        }
        runCurrent()
        connectivity.status.value = false
        runCurrent()
        connectivity.status.value = true
        runCurrent()
        assertEquals(1, received)

        vm.onAction(loads(hasItems = true))
        connectivity.status.value = false
        runCurrent()
        connectivity.status.value = true
        runCurrent()
        assertFalse(vm.state.value.isRefreshing)
        collector.cancel()
        runCurrent()
        assertEquals(0, connectivity.collectors)

        val resumedEvents = mutableListOf<MovieListEvent>()
        backgroundScope.launch { vm.events.collect { resumedEvents += it } }
        runCurrent()
        assertTrue(resumedEvents.isEmpty())
        assertFalse(vm.state.value.isRefreshing)
    }
}

private val idle = LoadState.NotLoading(false)
private fun loads(
    source: LoadState = idle,
    remote: LoadState = idle,
    sourceAppend: LoadState = idle,
    remoteAppend: LoadState = idle,
    hasItems: Boolean = false,
): MovieListAction.OnLoadStatesChanged {
    val local = LoadStates(source, idle, sourceAppend)
    val mediator = LoadStates(remote, idle, remoteAppend)
    return MovieListAction.OnLoadStatesChanged(CombinedLoadStates(remote, idle, remoteAppend, local, mediator), hasItems)
}

private class FakeCatalogRepository : CatalogRepository {
    var streams = 0
    var collections = 0
    override fun getPagedMovies(): Flow<PagingData<Movie>> {
        streams++
        return flow {
            collections++
            emit(PagingData.from(listOf(Movie(1, "Arrival", null)), sourceLoadStates = LoadStates(idle, LoadState.NotLoading(true), LoadState.NotLoading(true))))
        }
    }
}

private class FakeConnectivityObserver : ConnectivityObserver {
    val status = MutableStateFlow(true)
    var collectors = 0
    override val isConnected: Flow<Boolean> = flow {
        collectors++
        try { status.collect { emit(it) } } finally { collectors-- }
    }.distinctUntilChanged()
}
