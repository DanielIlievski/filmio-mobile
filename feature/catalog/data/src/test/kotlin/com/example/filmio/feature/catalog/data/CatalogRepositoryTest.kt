package com.example.filmio.feature.catalog.data

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.cachedIn
import androidx.paging.testing.asSnapshot
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import com.example.filmio.feature.catalog.data.paging.FakeMovieDao
import com.example.filmio.feature.catalog.data.paging.FakeTmdbService
import com.example.filmio.feature.catalog.data.paging.cachedMovie
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeMovieDao()
    private val service = FakeTmdbService()
    private fun repository() = OfflineFirstCatalogRepository(service, dao, FakeMovieDetailDao(dao), Clock.systemUTC())
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun initialLocalCacheBoundaryCanAppendBeforeItemAccess() = runTest(dispatcher) {
        service.respond = { page ->
            PopularMoviesResponseDto(page, if (page == 1) (1L..20L).map { MovieDto(it, "Movie %03d".format(it)) } else emptyList())
        }
        val presenter = object : PagingDataPresenter<Movie>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Movie>) = Unit
        }
        backgroundScope.launch { repository().getPagedMovies().collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        assertEquals(listOf(1, 2), service.pages)
        assertEquals(20, presenter.size)
        assertEquals(1, service.maxInFlight)
    }

    @Test fun fullPipelineContinuesThroughNullAndDuplicatePagesToUsableRows() = runTest(dispatcher) {
        service.respond = { page ->
            when (page) {
                1 -> PopularMoviesResponseDto(page, listOf(MovieDto(1, "Arrival")))
                2 -> PopularMoviesResponseDto(page, listOf(null))
                3 -> PopularMoviesResponseDto(page, listOf(MovieDto(1, "Arrival")))
                else -> PopularMoviesResponseDto(page, listOf(MovieDto(2, "Zodiac")), totalPages = 4)
            }
        }
        val snapshot = repository().getPagedMovies().asSnapshot { scrollTo(10) }
        assertEquals(listOf("Arrival", "Zodiac"), snapshot.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), service.pages)
        assertEquals(1, service.maxInFlight)
    }

    @Test fun localPagingReadsRemainingBatchesBeforeRemoteAppendAndKeepsCacheDuringPendingRefresh() = runTest(dispatcher) {
        dao.rows.value = (1L..60L).map { cachedMovie(it) }
        val gate = CompletableDeferred<Unit>()
        service.respond = { page -> gate.await(); PopularMoviesResponseDto(page, listOf(MovieDto(1, "Movie 001"))) }
        val presenter = object : PagingDataPresenter<Movie>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Movie>) = Unit
        }
        backgroundScope.launch { repository().getPagedMovies().collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        assertEquals(20, presenter.size)
        assertEquals(listOf(1), service.pages)
        gate.complete(Unit)
        runCurrent()
        presenter[19]
        runCurrent()
        assertEquals(40, presenter.size)
        assertEquals(listOf(1), service.pages)
        val appendGate = CompletableDeferred<Unit>()
        service.respond = { page ->
            // Paging exhausts the final local batch before asking the mediator for another page.
            assertTrue(dao.localLoads.contains(40))
            appendGate.await()
            PopularMoviesResponseDto(page, emptyList())
        }
        presenter[39]
        runCurrent()
        assertEquals(60, presenter.size)
        assertEquals(listOf(1, 2), service.pages)
        repeat(10) { presenter[presenter.size - 1] }
        runCurrent()
        assertEquals(listOf(1, 2), service.pages)
        assertEquals(1, service.maxInFlight)
        appendGate.complete(Unit)
        runCurrent()
    }

    @Test fun pagingRetryRepeatsFailedAppendWhileRefreshRestartsAtOne() = runTest(dispatcher) {
        dao.rows.value = (1L..20L).map { cachedMovie(it) }
        service.respond = { page ->
            if (page == 2) throw UnknownHostException()
            PopularMoviesResponseDto(page, listOf(MovieDto(1, "Movie 001")))
        }
        val presenter = object : PagingDataPresenter<Movie>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Movie>) = Unit
        }
        backgroundScope.launch { repository().getPagedMovies().collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        presenter[19]
        runCurrent()
        assertTrue(presenter.loadStateFlow.value!!.mediator!!.append is LoadState.Error)
        repeat(10) { presenter[19] }
        runCurrent()
        assertEquals(listOf(1, 2), service.pages)
        service.respond = { page -> PopularMoviesResponseDto(page, emptyList()) }
        presenter.retry()
        runCurrent()
        assertEquals(listOf(1, 2, 2), service.pages)
        assertEquals(20, presenter.size)
        presenter.refresh()
        runCurrent()
        assertEquals(listOf(1, 2, 2, 1), service.pages)
        assertEquals(20, dao.rows.value.size)
        assertTrue(presenter.snapshot().items.isNotEmpty())
    }

    @Test fun repeatedRefreshRetainsAbsoluteAnchorWhenPlaceholdersAreDisabled() = runTest(dispatcher) {
        dao.rows.value = (1L..60L).map { cachedMovie(it) }
        service.respond = { page -> PopularMoviesResponseDto(page, listOf(MovieDto(1, "Movie 001")), totalPages = 1) }
        val presenter = object : PagingDataPresenter<Movie>(dispatcher) {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<Movie>) = Unit
        }
        backgroundScope.launch { repository().getPagedMovies().collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        presenter[19]; runCurrent()
        presenter[39]; runCurrent()
        presenter[59]; runCurrent()
        repeat(3) {
            presenter.refresh()
            runCurrent()
            assertEquals(60L, presenter.snapshot().items.last().id)
            presenter[presenter.size - 1]
            runCurrent()
        }
        assertEquals(listOf(1, 1, 1, 1), service.pages)
    }

    @Test fun cachedStreamRecollectionReusesGenerationAndNewStreamStartsAtOne() = runTest(dispatcher) {
        service.respond = { page -> PopularMoviesResponseDto(page, listOf(MovieDto(1, "Arrival")), totalPages = 1) }
        val repository = repository()
        val cached = repository.getPagedMovies().cachedIn(backgroundScope)
        assertEquals(1, cached.asSnapshot().size)
        assertEquals(1, cached.asSnapshot().size)
        assertEquals(listOf(1), service.pages)
        assertEquals(1, repository.getPagedMovies().asSnapshot().size)
        assertEquals(listOf(1, 1), service.pages)
    }
}
