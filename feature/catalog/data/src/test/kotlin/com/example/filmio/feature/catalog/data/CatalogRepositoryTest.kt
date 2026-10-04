package com.example.filmio.feature.catalog.data

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(123), ZoneOffset.UTC)
    private val dao = FakeMovieDao()
    private var calls = 0
    private val pages = mutableListOf<Int>()
    private var remote: suspend (Int) -> PopularMoviesResponseDto = { page -> PopularMoviesResponseDto(page, listOf(MovieDto(1, "Remote"))) }
    private val service = object : TmdbService {
        override suspend fun getPopularMovies(page: Int, language: String): PopularMoviesResponseDto {
            assertEquals("en-US", language)
            pages += page
            calls++
            return remote(page)
        }
    }
    private fun repository() = OfflineFirstCatalogRepository(service, dao, clock)

    @Test fun networkFailureAndMismatchedPageNeverInvokeWriter() = runTest {
        dao.rows.value = listOf(entity("Cached"))
        remote = { throw UnknownHostException() }
        assertEquals(Result.Error(DataError.Network.NO_INTERNET), repository().fetchMovies(page = 1))
        remote = { PopularMoviesResponseDto(2, emptyList()) }
        assertEquals(Result.Error(DataError.Network.SERIALIZATION), repository().fetchMovies(page = 1))
        assertEquals(0, dao.writes)
        assertEquals("Cached", dao.rows.value.single().title)
    }

    @Test fun observationShowsCachedAndCommittedContentDuringRefresh() = runTest {
        val repository = repository()
        dao.rows.value = listOf(entity("Cached"))
        val stream = repository.getMovies()
        assertEquals("Cached", stream.first().single().title)
        assertEquals(0, calls)
        val networkGate = CompletableDeferred<Unit>()
        val commitGate = CompletableDeferred<Unit>()
        remote = { networkGate.await(); PopularMoviesResponseDto(1, listOf(MovieDto(1, "Remote"))) }
        dao.beforeWrite = { commitGate.await() }
        val refresh = async { repository.fetchMovies(page = 1) }
        runCurrent()
        assertEquals("Cached", stream.first().single().title)
        networkGate.complete(Unit); runCurrent()
        assertFalse(refresh.isCompleted)
        assertEquals("Cached", stream.first().single().title)
        commitGate.complete(Unit)
        assertEquals(Result.Success(Unit), refresh.await())
        assertEquals("Remote", stream.first().single().title)
        assertEquals(123L, dao.rows.value.single().updatedAtEpochMillis)
        dao.rows.value = listOf(entity("Direct local write"))
        assertEquals("Direct local write", stream.first().single().title)
    }

    @Test fun cancellationAndWriteFailureKeepPreviouslyStoredRows() = runTest {
        val repository = repository()
        dao.rows.value = listOf(entity("Cached"))
        remote = { awaitCancellation() }
        val job = launch { repository.fetchMovies(page = 1) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(0, dao.writes)
        remote = { PopularMoviesResponseDto(1, listOf(MovieDto(1, "Uncommitted"))) }
        dao.beforeWrite = { throw IllegalStateException("writer failure") }
        try {
            repository.fetchMovies(page = 1)
            fail("Expected local write failure")
        } catch (_: IllegalStateException) { }
        assertEquals("Cached", dao.rows.value.single().title)
        assertEquals(0, dao.writes)
    }

    @Test fun forwardsRequestedPagesAndCoercesNonpositivePagesToOne() = runTest {
        val repository = repository()
        for (page in listOf(2, 0)) {
            assertEquals(Result.Success(Unit), repository.fetchMovies(page))
        }
        assertEquals(listOf(2, 1), pages)
        assertEquals(2, dao.writes)
    }

    @Test fun commitsNonnullSummariesFromTheRequestedPage() = runTest {
        remote = { page -> PopularMoviesResponseDto(page, listOf(
            MovieDto(1, " Title ", "", voteAverage = 0.0), null, MovieDto(2, "Second"),
        )) }
        assertEquals(Result.Success(Unit), repository().fetchMovies(page = 2))
        assertEquals(listOf(" Title ", "Second"), dao.rows.value.map { it.title })
        assertEquals("", dao.rows.value.first().overview)
        assertEquals(0.0, dao.rows.value.first().voteAverage!!, 0.0)
        assertEquals(setOf(123L), dao.rows.value.map { it.updatedAtEpochMillis }.toSet())
        assertEquals(1, dao.writes)
    }

    private fun entity(title: String) = MovieEntity(1, title, null, null, null, null, null, null, null, null, 0)
}

private class FakeMovieDao : MovieDao() {
    val rows = MutableStateFlow<List<MovieEntity>>(emptyList())
    var writes = 0
    var beforeWrite: suspend () -> Unit = { }
    override fun observeMovies(): Flow<List<MovieEntity>> = flow {
        emitAll(rows)
    }
    override suspend fun upsertMovies(movies: List<MovieEntity>) {
        beforeWrite()
        writes++
        rows.value = (rows.value + movies).associateBy { it.id }.values.toList()
    }
    override suspend fun getMovie(movieId: Long) = rows.value.find { it.id == movieId }
    override fun pagingSource(): PagingSource<Int, MovieEntity> = error("Unused")
    override fun searchPagingSource(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity> = error("Unused")
}
