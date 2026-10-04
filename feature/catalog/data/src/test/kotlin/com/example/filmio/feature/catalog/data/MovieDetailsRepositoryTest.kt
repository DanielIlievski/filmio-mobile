package com.example.filmio.feature.catalog.data

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.example.filmio.feature.catalog.data.mapping.toSnapshot
import com.example.filmio.feature.catalog.data.networking.dto.*
import com.example.filmio.feature.catalog.data.paging.FakeTmdbService
import com.example.filmio.feature.catalog.data.paging.cachedMovie
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.net.UnknownHostException
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class MovieDetailsRepositoryTest {
    private val dao = FakeMovieDetailDao()
    private val service = FakeTmdbService()
    private val now = 100_000_000L
    private fun repository() = OfflineFirstCatalogRepository(
        service, dao.summaries, dao, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))
    private fun stored(timestamp: Long = 0) = MovieDetailsDto(7, "Cached", runtime = 116,
        genres = listOf(MovieGenreDto(1, "Drama")), productionCompanies = listOf(MovieProductionCompanyDto(2, "Company")))
        .toSnapshot(7, timestamp)!!

    @Test fun failedNetworkAndWrongIdentityPreserveCompleteCachedSnapshotAndTimestamp() = runTest {
        dao.seed(stored())
        val offline: suspend (Long) -> MovieDetailsDto = { throw UnknownHostException() }
        val missing: suspend (Long) -> MovieDetailsDto = {
            throw HttpException(Response.error<Unit>(404, "".toResponseBody()))
        }
        val wrongId: suspend (Long) -> MovieDetailsDto = { MovieDetailsDto(8, "Wrong") }
        for ((respond, error) in listOf(offline to DataError.Network.NO_INTERNET,
            missing to DataError.Network.NOT_FOUND, wrongId to DataError.Network.SERIALIZATION)) {
            service.detailResponse = respond
            assertEquals(Result.Error(error), repository().fetchMovieDetails(7))
            assertEquals(stored(), dao.snapshot(7))
            assertEquals(0, dao.writes)
        }
    }

    @Test fun expectedWriteFailuresAreTypedAndDoNotPretendToCommit() = runTest {
        dao.seed(stored())
        for ((failure, error) in listOf(
            mock(SQLiteFullException::class.java) to DataError.Local.DISK_FULL,
            mock(SQLiteException::class.java) to DataError.Local.UNKNOWN,
        )) {
            dao.beforeCommit = { throw failure }
            assertEquals(Result.Error(error), repository().fetchMovieDetails(7))
            assertEquals(stored(), dao.snapshot(7))
            assertEquals(0, dao.writes)
        }
    }

    @Test fun cancellationBeforeOrAfterSimulatedCommitNeverReturnsSuccessOrArtificialError() = runTest {
        for (after in listOf(false, true)) {
            val local = FakeMovieDetailDao()
            local.seed(stored())
            val gate = CompletableDeferred<Unit>()
            if (after) local.afterCommit = { gate.await() } else local.beforeCommit = { gate.await() }
            val repo = OfflineFirstCatalogRepository(service, local.summaries, local, Clock.systemUTC())
            var returned = false
            val job = launch { repo.fetchMovieDetails(7); returned = true }
            runCurrent()
            assertEquals(if (after) 1 else 0, local.writes)
            job.cancelAndJoin()
            assertFalse(returned)
            assertEquals(if (after) "Details" else "Cached", local.snapshot(7)!!.movie.title)
        }
    }

    @Test fun cancelledCommitCompletionRetainsWritesAndUnexpectedDefectsPropagate() = runTest {
        dao.seed(stored())
        dao.afterCommit = { currentCoroutineContext().cancel() }
        val command = async { repository().fetchMovieDetails(7) }
        try { command.await(); fail("Canceled command must not return completion") }
        catch (_: CancellationException) { }
        assertEquals(1, dao.writes)
        assertEquals("Details", dao.snapshot(7)!!.movie.title)
        dao.afterCommit = {}
        val defect = IllegalStateException("defect")
        dao.beforeCommit = { throw defect }
        try { repository().fetchMovieDetails(7); fail("Expected defect") }
        catch (actual: IllegalStateException) { assertSame(defect, actual) }
    }

    @Test fun cancellationAtFailedBoundariesPreservesCachedContent() = runTest {
        dao.seed(stored())
        for (duringWrite in listOf(false, true)) {
            service.detailResponse = {
                if (!duringWrite) { currentCoroutineContext().cancel(); throw UnknownHostException() }
                MovieDetailsDto(it, "Details")
            }
            dao.beforeCommit = {
                if (duringWrite) { currentCoroutineContext().cancel(); throw mock(SQLiteException::class.java) }
            }
            val command = async { repository().fetchMovieDetails(7) }
            try { command.await(); fail("Expected cancellation, not a translated error") }
            catch (_: CancellationException) { }
            assertEquals(0, dao.writes)
            assertEquals(stored(), dao.snapshot(7))
        }
    }

    @Test fun observationIsLocalOnlyAndDistinguishesAbsentSummaryAndFetchedUnknown() = runTest {
        val seen = mutableListOf<Movie?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository().getMovieDetails(7).collect { seen += it } }
        runCurrent()
        assertEquals(null, seen.last())
        dao.summaries.upsertMovies(listOf(cachedMovie(7, "Summary")))
        runCurrent()
        assertEquals(Movie(7, "Summary", null), seen.last())
        dao.seed(MovieDetailsDto(7, "Fetched").toSnapshot(7, now)!!)
        runCurrent()
        assertNotNull(seen.last()!!.details)
        assertTrue(service.detailIds.isEmpty())
    }

    @Test fun observationIgnoresTimestampOnlyWritesAndPublishesChangedContent() = runTest {
        dao.seed(stored())
        val seen = mutableListOf<Movie?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository().getMovieDetails(7).collect { seen += it }
        }
        runCurrent()
        dao.seed(stored(now))
        runCurrent()
        assertEquals(1, seen.size)
        val changed = stored(now).let { it.copy(movie = it.movie.copy(title = "Changed")) }
        dao.seed(changed)
        runCurrent()
        assertEquals(listOf("Cached", "Changed"), seen.map { it!!.title })
        assertTrue(service.detailIds.isEmpty())
    }

    @Test fun observationFailureTerminatesAndNewSubscriptionRecoversLastLocalContent() = runTest {
        for (failure in listOf(
            mock(SQLiteFullException::class.java),
            mock(SQLiteException::class.java),
        )) {
            dao.readFailure.value = null
            dao.seed(stored())
            val seen = mutableListOf<Movie?>()
            var reported: Throwable? = null
            val observer = backgroundScope.launch {
                repository().getMovieDetails(7)
                    .catch { reported = it }
                    .collect { seen += it }
            }
            runCurrent()
            val lastContent = seen.last()
            dao.readFailure.value = failure
            runCurrent()
            assertTrue(observer.isCompleted)
            assertSame(failure, reported)
            assertEquals(listOf(lastContent), seen)
            assertEquals("Cached", lastContent!!.title)
            dao.readFailure.value = null
            assertEquals(lastContent, repository().getMovieDetails(7).first())
            assertTrue(service.detailIds.isEmpty())
        }
    }

    @Test fun observationPropagatesCancellationAndUnexpectedDefects() = runTest {
        for (failure in listOf(CancellationException(), IllegalStateException("defect"))) {
            dao.readFailure.value = failure
            try { repository().getMovieDetails(7).first(); fail("Expected original failure") }
            catch (actual: Exception) {
                assertTrue(actual.javaClass == failure.javaClass)
                assertTrue(actual === failure || actual.cause === failure)
            }
        }
    }

    @Test fun everyFetchRequestsLatestDataEvenWhenDetailsWereJustCommitted() = runTest {
        dao.seed(stored(now))
        // Fetching never needs a preliminary cache read; observation owns read failures.
        dao.readFailure.value = mock(SQLiteException::class.java)
        val repo = repository()
        service.detailResponse = { MovieDetailsDto(it, "Updated once") }
        assertEquals(Result.Success(Unit), repo.fetchMovieDetails(7))
        assertEquals("Updated once", dao.snapshot(7)!!.movie.title)
        service.detailResponse = { MovieDetailsDto(it, "Updated twice") }
        assertEquals(Result.Success(Unit), repo.fetchMovieDetails(7))
        assertEquals("Updated twice", dao.snapshot(7)!!.movie.title)
        assertEquals(listOf(7L, 7L), service.detailIds)
        assertEquals(2, dao.writes)
        assertEquals(0, dao.reads)
    }

    @Test fun successWaitsForCommitAndContentIsSuppliedOnlyByLocalObservation() = runTest {
        dao.summaries.upsertMovies(listOf(cachedMovie(7, "Summary")))
        val gate = CompletableDeferred<Unit>()
        dao.beforeCommit = { gate.await() }
        val seen = mutableListOf<Movie?>()
        backgroundScope.launch { repository().getMovieDetails(7).collect { seen += it } }
        val command = async { repository().fetchMovieDetails(7) }
        runCurrent()
        assertFalse(command.isCompleted)
        assertEquals("Summary", seen.last()!!.title)
        assertEquals(0, dao.writes)
        gate.complete(Unit)
        assertEquals(Result.Success(Unit), command.await())
        runCurrent()
        val movie = seen.last()!!
        assertEquals("Details", movie.title)
        assertNotNull(movie.details)
        assertEquals(now, dao.snapshot(7)!!.detail!!.fetchedAtEpochMillis)
        assertEquals(now, dao.snapshot(7)!!.movie.updatedAtEpochMillis)
    }

    @Test fun detailAndPopularWritesShareCanonicalSummaryPreserveOwnedMetadataAndFavorites() = runTest {
        dao.summaries.upsertMovies(listOf(cachedMovie(7), cachedMovie(9)))
        dao.favorites[7] = MovieFavoriteEntity(7, 10)
        service.detailResponse = { MovieDetailsDto(it, "Detail title", voteCount = 42,
            runtime = 116, genres = listOf(MovieGenreDto(1, "Drama"))) }
        repository().fetchMovieDetails(7)
        val detail = dao.snapshot(7)!!
        assertEquals(2, dao.summaries.rows.value.size)
        assertEquals(42, detail.movie.voteCount)
        assertEquals("Detail title", dao.summaries.getMovie(7)!!.title)
        dao.summaries.upsertMovies(listOf(cachedMovie(7, "Popular title")))
        assertEquals("Popular title", dao.snapshot(7)!!.movie.title)
        assertEquals(detail.detail, dao.snapshot(7)!!.detail)
        assertEquals(detail.genres, dao.snapshot(7)!!.genres)
        assertEquals(detail.productionCompanies, dao.snapshot(7)!!.productionCompanies)
        assertEquals(cachedMovie(9), dao.summaries.getMovie(9))
        assertEquals(MovieFavoriteEntity(7, 10), dao.favorites[7])
        repository().fetchMovieDetails(8)
        assertEquals(3, dao.summaries.rows.value.size)
        assertNotNull(dao.snapshot(8)!!.detail)
    }

    @Test fun nullAndEmptyReplacementClearsOldSelectedMetadataAndRepeatedFetchUsesSameId() = runTest {
        dao.seed(stored())
        service.detailResponse = { throw UnknownHostException() }
        assertEquals(Result.Error(DataError.Network.NO_INTERNET), repository().fetchMovieDetails(7))
        service.detailResponse = { MovieDetailsDto(it, "Replacement", voteAverage = 0.0, voteCount = 0, budget = 0) }
        assertEquals(Result.Success(Unit), repository().fetchMovieDetails(7))
        val snapshot = dao.snapshot(7)!!
        assertNull(snapshot.detail!!.runtimeMinutes)
        assertTrue(snapshot.genres.isEmpty())
        assertTrue(snapshot.productionCompanies.isEmpty())
        assertEquals(0L, snapshot.detail!!.budget)
        assertEquals(0, snapshot.movie.voteCount)
        assertEquals(listOf(7L, 7L), service.detailIds)
    }
}
