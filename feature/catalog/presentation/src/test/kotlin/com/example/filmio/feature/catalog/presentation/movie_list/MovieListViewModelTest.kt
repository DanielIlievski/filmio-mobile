package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.lifecycle.ViewModelStore
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.core.domain.Result
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MovieListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val repository = FakeCatalogRepository()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun offlineFailureIsRetryableAndLocalEmissionsDoNotClearIt() = runTest {
        repository.refresh = { Result.Error(DataError.Network.NO_INTERNET) }
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.movies.isEmpty())
        assertError(vm, R.string.error_offline)

        repository.local.value = listOf(Movie(1, "Cached", null))
        runCurrent()
        assertEquals("Cached", vm.state.value.movies.single().title)
        assertError(vm, R.string.error_offline)

        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals("Cached", vm.state.value.movies.single().title)
        assertEquals(2, repository.requests)
        assertEquals(1, repository.collections)
        assertError(vm, R.string.error_offline)
    }

    @Test
    fun retryRestartsFailedObservationAndRefreshesWithoutLosingLastReadMovies() = runTest {
        repository.read = {
            flow {
                emit(listOf(Movie(1, "Cached", null)))
                throw IllegalStateException("private storage detail")
            }
        }
        val vm = viewModel()
        runCurrent()
        assertEquals("Cached", vm.state.value.movies.single().title)
        assertError(vm, R.string.error_storage)

        val gate = CompletableDeferred<Unit>()
        repository.read = { repository.local }
        repository.local.value = listOf(Movie(2, "Recovered", null))
        repository.refresh = { gate.await(); Result.Success(Unit) }
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
        assertEquals("Recovered", vm.state.value.movies.single().title)
        assertEquals(2, repository.collections)
        assertEquals(2, repository.requests)

        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun localReadFailureTakesPriorityRegardlessOfWhichOperationFinishesFirst() = runTest {
        for (readFailsFirst in listOf(true, false)) {
            val readGate = CompletableDeferred<Unit>()
            val refreshGate = CompletableDeferred<Unit>()
            repository.read = { flow { readGate.await(); throw IllegalStateException("read failed") } }
            repository.refresh = { refreshGate.await(); Result.Error(DataError.Network.SERIALIZATION) }
            val vm = viewModel()
            runCurrent()

            if (readFailsFirst) readGate.complete(Unit) else refreshGate.complete(Unit)
            runCurrent()
            if (readFailsFirst) refreshGate.complete(Unit) else readGate.complete(Unit)
            runCurrent()
            assertError(vm, R.string.error_storage)
            assertFalse(vm.state.value.isLoading)
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun refreshAlsoRetriesFailedObservationButRemoteSuccessCannotClearAnotherReadFailure() = runTest {
        repository.read = { flow { throw IllegalStateException("unavailable storage") } }
        val vm = viewModel()
        runCurrent()
        vm.onAction(MovieListAction.OnRefreshClick)
        runCurrent()
        assertEquals(2, repository.collections)
        assertEquals(2, repository.requests)
        assertError(vm, R.string.error_storage)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun retryClearsTypedWriteErrorAndPreservesCachedMovies() = runTest {
        repository.local.value = listOf(Movie(1, "Cached", null))
        repository.refresh = { Result.Error(DataError.Local.DISK_FULL) }
        val vm = viewModel()
        runCurrent()
        assertError(vm, R.string.error_disk_full)
        assertFalse(vm.state.value.isLoading)
        assertEquals("Cached", vm.state.value.movies.single().title)

        repository.refresh = { delay(100); Result.Error(DataError.Network.SERIALIZATION) }
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.isLoading)
        assertEquals("Cached", vm.state.value.movies.single().title)
        advanceUntilIdle()
        assertError(vm, R.string.error_malformed)
        assertFalse(vm.state.value.isLoading)

        repository.refresh = { Result.Success(Unit) }
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(1, repository.collections)
    }

    @Test
    fun cachedObservationDoesNotWaitForRefreshAndRepeatedActionsDoNotQueueRequests() = runTest {
        repository.local.value = listOf(Movie(1, "Cached", null))
        repository.refresh = { delay(100); Result.Success(Unit) }
        val vm = viewModel()
        runCurrent()
        assertEquals("Cached", vm.state.value.movies.single().title)
        assertTrue(vm.state.value.isLoading)
        vm.onAction(MovieListAction.OnRefreshClick)
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals(1, repository.requests)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals(1, repository.requests)
        vm.onAction(MovieListAction.OnRefreshClick)
        advanceUntilIdle()
        assertEquals(2, repository.requests)
    }

    @Test
    fun emptySuccessWaitsForBothOperationsAndRefreshDoesNotManufactureMovies() = runTest {
        for (readFinishesFirst in listOf(true, false)) {
            val readGate = CompletableDeferred<Unit>()
            val refreshGate = CompletableDeferred<Unit>()
            repository.read = { flow { readGate.await(); emit(emptyList()) } }
            repository.refresh = { refreshGate.await(); Result.Success(Unit) }
            val vm = viewModel()
            runCurrent()
            if (readFinishesFirst) readGate.complete(Unit) else refreshGate.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.isLoading)
            assertTrue(vm.state.value.movies.isEmpty())
            val requests = repository.requests
            vm.onAction(MovieListAction.OnRefreshClick)
            vm.onAction(MovieListAction.OnRetryClick)
            runCurrent()
            assertEquals(requests, repository.requests)
            if (readFinishesFirst) refreshGate.complete(Unit) else readGate.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.isLoading)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.movies.isEmpty())
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun loadingStartsOnSubscriptionAndResubscriptionDoesNotRepeatInitialWork() = runTest {
        val vm = MovieListViewModel(repository).also { store.put("movie-list", it) }
        runCurrent()
        assertEquals(0, repository.requests)
        assertEquals(0, repository.collections)
        val firstCollector = backgroundScope.launch { vm.state.collect() }
        runCurrent()
        assertEquals(1, repository.requests)
        assertEquals(1, repository.collections)
        firstCollector.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        repository.local.value = listOf(Movie(1, "Stored while away", null))
        backgroundScope.launch { vm.state.collect() }
        runCurrent()
        assertEquals("Stored while away", vm.state.value.movies.single().title)
        assertEquals(1, repository.requests)
        assertEquals(1, repository.collections)
    }

    @Test
    fun loadingGuardIgnoresActionsBeforeTheRequestCoroutineStarts() = runTest {
        val vm = viewModel()
        runCurrent()
        repository.refresh = { delay(100); Result.Success(Unit) }
        vm.onAction(MovieListAction.OnRefreshClick)
        vm.onAction(MovieListAction.OnRefreshClick)
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertEquals(2, repository.requests)
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        assertEquals(2, repository.requests)
    }

    @Test
    fun retryRestartsACancelledObserverWithoutReportingAnOrdinaryError() = runTest {
        repository.read = { flow { throw CancellationException("fixture read cancellation") } }
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)

        repository.local.value = listOf(Movie(1, "Recovered", null))
        repository.read = { repository.local }
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
        assertEquals("Recovered", vm.state.value.movies.single().title)
        assertEquals(2, repository.collections)
        assertEquals(2, repository.requests)
    }

    @Test
    fun cancellationFinishesLoadingWithoutAnOrdinaryErrorAndAllowsLaterRetry() = runTest {
        repository.refresh = { throw CancellationException("fixture cancellation") }
        val vm = viewModel()
        runCurrent()
        assertFalse(vm.state.value.isLoading)
        assertNull(vm.state.value.error)
        val cancelled = CompletableDeferred<Unit>()
        repository.refresh = {
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        vm.onAction(MovieListAction.OnRetryClick)
        runCurrent()
        assertTrue(vm.state.value.isLoading)
        assertEquals(2, repository.requests)
        store.clear()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertNull(vm.state.value.error)
    }

    @Test
    fun clearingViewModelCancelsTheActiveLocalRead() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        repository.read = {
            flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val vm = viewModel()
        runCurrent()
        assertTrue(vm.state.value.isLoading)
        store.clear()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertNull(vm.state.value.error)
    }

    private fun TestScope.viewModel(): MovieListViewModel = MovieListViewModel(repository).also {
        store.put("movie-list", it)
        backgroundScope.launch { it.state.collect() }
    }

    private fun assertError(vm: MovieListViewModel, resourceId: Int) {
        assertEquals(resourceId, (vm.state.value.error as UiText.Resource).id)
    }
}

private class FakeCatalogRepository : CatalogRepository {
    var requests = 0
    var collections = 0
    val local = MutableStateFlow<List<Movie>>(emptyList())
    var read: () -> Flow<List<Movie>> = { local }
    var refresh: suspend () -> EmptyResult<DataError> = { Result.Success(Unit) }

    override suspend fun fetchMovies(page: Int): EmptyResult<DataError> {
        assertEquals(1, page)
        requests++
        return refresh()
    }

    override fun getMovies(): Flow<List<Movie>> = flow {
        collections++
        emitAll(read())
    }
}
