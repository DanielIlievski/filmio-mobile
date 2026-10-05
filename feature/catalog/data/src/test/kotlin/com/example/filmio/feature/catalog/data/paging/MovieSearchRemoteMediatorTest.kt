package com.example.filmio.feature.catalog.data.paging

import android.database.sqlite.SQLiteFullException
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.example.filmio.core.domain.DataError
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.MovieSearchResponseDto
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.repository.CatalogPagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
class MovieSearchRemoteMediatorTest {
    private val dao = FakeMovieDao()
    private val service = FakeTmdbService()
    private val mediator = MovieSearchRemoteMediator("Inter", service, dao, Clock.fixed(Instant.ofEpochMilli(123), ZoneOffset.UTC))
    private val state = PagingState<Int, MovieEntity>(emptyList(), null, PagingConfig(20), 0)
    private suspend fun load(type: LoadType) = mediator.load(type, state)

    @Test fun networkAndMismatchedPagePreserveRowsAndRetryPage() = runTest {
        load(LoadType.REFRESH)
        val cached = dao.rows.value
        val membership = mediator.remoteIds
        service.searchResponse = { throw UnknownHostException() }
        assertError(DataError.Network.NO_INTERNET, load(LoadType.APPEND))
        service.searchResponse = { MovieSearchResponseDto(9, emptyList()) }
        assertError(DataError.Network.SERIALIZATION, load(LoadType.APPEND))
        assertEquals(cached, dao.rows.value)
        assertEquals(membership, mediator.remoteIds)
        assertEquals(1, dao.writes)
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(MovieDto(2, "Second"))) }
        assertSuccess(false, load(LoadType.APPEND))
        assertEquals(listOf(1, 2, 2, 2), service.searchPages)
    }

    @Test fun failedRefreshRetriesOneAndStorageFailureDoesNotAdvanceAppend() = runTest {
        dao.rows.value = listOf(cachedMovie(50))
        service.searchResponse = { throw UnknownHostException() }
        assertError(DataError.Network.NO_INTERNET, load(LoadType.REFRESH))
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(MovieDto(page.toLong(), "Remote"))) }
        load(LoadType.REFRESH)
        val cached = dao.rows.value
        dao.beforeWrite = { throw mock(SQLiteFullException::class.java) }
        assertError(DataError.Local.DISK_FULL, load(LoadType.APPEND))
        assertEquals(cached, dao.rows.value)
        dao.beforeWrite = {}
        load(LoadType.APPEND)
        assertEquals(listOf(1, 1, 2, 2), service.searchPages)
    }

    @Test fun cancellationBeforeCommitKeepsCacheAndPageWithoutError() = runTest {
        load(LoadType.REFRESH)
        val cached = dao.rows.value
        dao.beforeWrite = { awaitCancellation() }
        val operation = async { load(LoadType.APPEND) }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals(cached, dao.rows.value)
        dao.beforeWrite = {}
        load(LoadType.APPEND)
        assertEquals(listOf(1, 2, 2), service.searchPages)
    }

    @Test fun loadRemainsPendingUntilCommitAndRefreshResetsSequence() = runTest {
        assertEquals(RemoteMediator.InitializeAction.LAUNCH_INITIAL_REFRESH, mediator.initialize())
        assertSuccess(true, load(LoadType.PREPEND))
        val gate = CompletableDeferred<Unit>()
        dao.beforeWrite = { gate.await() }
        val operation = async { load(LoadType.REFRESH) }
        runCurrent()
        assertFalse(operation.isCompleted)
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(mediator.remoteIds.isEmpty())
        gate.complete(Unit)
        assertSuccess(false, operation.await())
        assertEquals(listOf(1L), mediator.remoteIds)
        load(LoadType.APPEND)
        load(LoadType.APPEND)
        load(LoadType.REFRESH)
        load(LoadType.APPEND)
        assertEquals(listOf(1, 2, 3, 1, 2), service.searchPages)
    }

    @Test fun mappingPreservesLastDuplicateValuesTimestampAndOmittedCache() = runTest {
        dao.rows.value = listOf(cachedMovie(99, "Omitted"))
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(
            MovieDto(1, "Old"), null, MovieDto(1, " Title ", "", voteAverage = 0.0), MovieDto(2, "Second"),
        )) }
        load(LoadType.REFRESH)
        assertEquals(3, dao.rows.value.size)
        assertEquals("Omitted", dao.getMovie(99)!!.title)
        assertEquals(" Title ", dao.getMovie(1)!!.title)
        assertEquals("", dao.getMovie(1)!!.overview)
        assertEquals(0.0, dao.getMovie(1)!!.voteAverage!!, 0.0)
        assertEquals(setOf(123L), dao.rows.value.filter { it.id != 99L }.map { it.updatedAtEpochMillis }.toSet())
    }

    @Test fun emptyRawResultsAndReportedLastPageEndWithoutDeletingCache() = runTest {
        for (response in listOf(
            MovieSearchResponseDto(1, emptyList()),
            MovieSearchResponseDto(1, listOf(MovieDto(1, "Last")), totalPages = 1),
        )) {
            service.searchResponse = { response }
            dao.rows.value = listOf(cachedMovie(99))
            assertSuccess(true, load(LoadType.REFRESH))
            val requests = service.searchPages.size
            assertSuccess(true, load(LoadType.APPEND))
            assertEquals(requests, service.searchPages.size)
            assertNotNull(dao.getMovie(99))
        }
    }

    @Test fun nullAndDuplicatePagesContinueButPage500Stops() = runTest {
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(null), totalPages = 600) }
        assertSuccess(false, load(LoadType.REFRESH))
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(MovieDto(1, "Same")), totalPages = 600) }
        for (page in 2..499) assertSuccess(false, load(LoadType.APPEND))
        assertSuccess(true, load(LoadType.APPEND))
        assertSuccess(true, load(LoadType.APPEND))
        assertEquals((1..500).toList(), service.searchPages)
        assertEquals(1, dao.rows.value.size)
    }

    @Test fun canceledCommittedAppendCannotOverwriteReplacementRefreshContinuation() = runTest {
        load(LoadType.REFRESH)
        load(LoadType.APPEND)
        val gate = CompletableDeferred<Unit>()
        dao.afterWrite = { withContext(NonCancellable) { gate.await() } }
        val obsolete = launch { load(LoadType.APPEND) }
        runCurrent()
        assertNotNull(dao.getMovie(3))
        obsolete.cancel()
        dao.afterWrite = {}
        load(LoadType.REFRESH)
        gate.complete(Unit)
        obsolete.join()
        load(LoadType.APPEND)
        assertEquals(listOf(1, 2, 3, 1, 2), service.searchPages)
        assertEquals(3, dao.rows.value.size)
    }

    @Test fun cancellationAfterCommitAllowsIdempotentRetryOfSamePage() = runTest {
        load(LoadType.REFRESH)
        dao.afterWrite = { throw CancellationException() }
        try { load(LoadType.APPEND); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        dao.afterWrite = {}
        load(LoadType.APPEND)
        assertEquals(listOf(1, 2, 2), service.searchPages)
        assertEquals(2, dao.rows.value.size)
    }

    @Test fun successfulRefreshReplacesMembershipButFailedRefreshKeepsIt() = runTest {
        load(LoadType.REFRESH)
        load(LoadType.APPEND)
        assertEquals(listOf(1L, 2L), mediator.remoteIds)
        service.searchResponse = { throw UnknownHostException() }
        load(LoadType.REFRESH)
        assertEquals(listOf(1L, 2L), mediator.remoteIds)
        service.searchResponse = { page -> MovieSearchResponseDto(page, emptyList()) }
        load(LoadType.REFRESH)
        assertTrue(mediator.remoteIds.isEmpty())
        assertEquals(2, dao.rows.value.size)
    }

    @Test fun refreshSupersedesDelayedAppendEvenIfDependencyIgnoresCancellation() = runTest {
        load(LoadType.REFRESH)
        val gate = CompletableDeferred<Unit>()
        service.searchResponse = { page -> withContext(NonCancellable) {
            gate.await(); MovieSearchResponseDto(page, listOf(MovieDto(99, "Obsolete")))
        } }
        val obsolete = async { load(LoadType.APPEND) }
        runCurrent()
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(MovieDto(3, "Current"))) }
        load(LoadType.REFRESH)
        gate.complete(Unit)
        try { obsolete.await(); fail("Superseded load must be canceled") } catch (_: CancellationException) { }
        assertEquals(listOf(3L), mediator.remoteIds)
        assertNull(dao.getMovie(99))
        load(LoadType.APPEND)
        assertEquals(listOf(1, 2, 1, 2), service.searchPages)
    }

    @Test fun unexpectedDefectPropagatesWithoutAdvancing() = runTest {
        dao.beforeWrite = { error("fixture defect") }
        try { load(LoadType.REFRESH); fail("Defects must propagate") } catch (_: IllegalStateException) { }
        assertEquals(0, dao.writes)
        dao.beforeWrite = {}
        load(LoadType.REFRESH)
        assertEquals(listOf(1, 1), service.searchPages)
    }

    private fun assertError(error: DataError, result: RemoteMediator.MediatorResult) {
        assertEquals(error, ((result as RemoteMediator.MediatorResult.Error).throwable as CatalogPagingException).error)
    }
    private fun assertSuccess(ended: Boolean, result: RemoteMediator.MediatorResult) {
        assertEquals(ended, (result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
    }
}
