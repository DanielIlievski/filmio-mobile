package com.example.filmio.feature.catalog.data

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.asSnapshot
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.example.filmio.feature.catalog.data.paging.*
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.database.dao.MovieFavoriteDao
import com.example.filmio.feature.catalog.database.entities.FavoriteMovie
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.domain.repository.CatalogStorageException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class MovieFavoritesRepositoryTest {
    private val movies = FakeMovieDao()
    private val favorites = FakeMovieFavoriteDao(movies)
    private val service = FakeTmdbService()
    private var now = 100L
    private fun repo() = OfflineFirstCatalogRepository(service, movies, FakeMovieDetailDao(movies),
        Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), favorites)
    private fun noHttp() {
        assertTrue(service.pages.isEmpty()); assertTrue(service.searchPages.isEmpty()); assertTrue(service.detailIds.isEmpty())
    }

    @Test fun missingAndStorageFailuresPreserveMembershipAndContent() = runTest {
        movies.upsertMovies(listOf(cachedMovie(7)))
        assertEquals(Result.Error(DataError.Local.NOT_FOUND), repo().setMovieFavorite(8, true))
        for ((failure, error) in listOf(mock(SQLiteFullException::class.java) to DataError.Local.DISK_FULL,
            mock(SQLiteException::class.java) to DataError.Local.UNKNOWN)) {
            favorites.before = { throw failure }
            assertEquals(Result.Error(error), repo().setMovieFavorite(7, true))
            assertEquals(emptyList<MovieFavoriteEntity>(), favorites.rows.value)
            assertEquals(listOf(cachedMovie(7)), movies.rows.value)
        }
        noHttp()
    }

    @Test fun failedUnsavePreservesCommittedTimeAndCachedSummary() = runTest {
        movies.upsertMovies(listOf(cachedMovie(7)))
        repo().setMovieFavorite(7, true)
        favorites.before = { throw mock(SQLiteException::class.java) }
        assertEquals(Result.Error(DataError.Local.UNKNOWN), repo().setMovieFavorite(7, false))
        assertEquals(listOf(MovieFavoriteEntity(7, 100)), favorites.rows.value)
        assertEquals(cachedMovie(7), movies.getMovie(7))
        noHttp()
    }

    @Test fun cancellationBeforeAndAfterCommitDoesNotConvertOrUndo() = runTest {
        movies.upsertMovies(listOf(cachedMovie(7)))
        for (after in listOf(false, true)) {
            favorites.rows.value = emptyList()
            favorites.before = {}; favorites.after = {}
            val gate = CompletableDeferred<Unit>()
            if (after) favorites.after = { gate.await() } else favorites.before = { gate.await() }
            var returned = false
            val job = launch { repo().setMovieFavorite(7, true); returned = true }
            runCurrent(); job.cancelAndJoin()
            assertFalse(returned)
            assertEquals(after, favorites.rows.value.isNotEmpty())
        }
        noHttp()
    }

    @Test fun duplicateSaveAbsentRemoveAndReaddUseCommitTime() = runTest {
        movies.upsertMovies(listOf(cachedMovie(7)))
        assertEquals(Result.Success(Unit), repo().setMovieFavorite(7, true))
        now = 200; repo().setMovieFavorite(7, true)
        assertEquals(100L, favorites.rows.value.single().addedAtEpochMillis)
        repo().setMovieFavorite(7, false); repo().setMovieFavorite(7, false)
        repo().setMovieFavorite(7, true)
        assertEquals(200L, favorites.rows.value.single().addedAtEpochMillis)
        assertEquals(setOf(7L), repo().observeFavoriteMovieIds().first())
        assertTrue(repo().observeIsMovieFavorite(7).first())
        noHttp()
    }

    @Test fun expectedReadFailureTerminatesAndFreshSubscriptionRecovers() = runTest {
        favorites.readFailure = mock(SQLiteFullException::class.java)
        try { repo().observeFavoriteMovieIds().first(); fail() }
        catch (error: CatalogStorageException) { assertEquals(DataError.Local.DISK_FULL, error.error) }
        favorites.readFailure = null
        assertEquals(emptySet<Long>(), repo().observeFavoriteMovieIds().first())
        val defect = IllegalStateException("defect")
        favorites.readFailure = defect
        try { repo().observeIsMovieFavorite(7).first(); fail() }
        catch (actual: IllegalStateException) { assertSame(defect, actual) }
        noHttp()
    }

    @Test fun popularEmptyRefreshAndDetailCommitPreserveIndependentMembership() = runTest {
        val details = FakeMovieDetailDao(movies)
        val repository = OfflineFirstCatalogRepository(service, movies, details,
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), favorites)
        movies.upsertMovies(listOf(cachedMovie(7)))
        repository.setMovieFavorite(7, true)
        service.respond = { page -> com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto(
            page, listOf(com.example.filmio.feature.catalog.data.networking.dto.MovieDto(7, "Popular update")), 1) }
        assertEquals("Popular update", repository.getPagedMovies().asSnapshot().single().title)
        assertEquals("Popular update", repository.getPagedSavedMovies().asSnapshot().single().title)
        repository.fetchMovieDetails(7)
        assertEquals("Details", repository.getPagedSavedMovies().asSnapshot().single().title)
        assertNotNull(repository.getMovieDetails(7).first()!!.details)
        service.respond = { page -> com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto(page, emptyList(), 1) }
        repository.getPagedMovies().asSnapshot()
        assertEquals(setOf(7L), repository.observeFavoriteMovieIds().first())
        assertEquals(100L, favorites.rows.value.single().addedAtEpochMillis)
    }

    @Test fun idObservationSuppressesDuplicateValuesAndInvalidIdsArePreconditions() = runTest {
        val values = mutableListOf<Set<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo().observeFavoriteMovieIds().collect { values += it } }
        favorites.rows.value = listOf(MovieFavoriteEntity(7, 10)); runCurrent()
        favorites.rows.value = listOf(MovieFavoriteEntity(7, 20)); runCurrent()
        favorites.rows.value = emptyList(); runCurrent()
        assertEquals(listOf(emptySet<Long>(), setOf(7L), emptySet<Long>()), values)
        assertThrows(IllegalArgumentException::class.java) { repo().observeIsMovieFavorite(0) }
        try { repo().setMovieFavorite(0, true); fail() } catch (_: IllegalArgumentException) { }
        noHttp()
    }

    @Test fun savedQueryEscapesPatternsAndKeepsNonAsciiLiteral() = runTest {
        repo().getPagedSavedMovies(" É%_*?[] ").asSnapshot()
        assertEquals("*É%_[*][?][[][]]*", favorites.lastPattern)
        noHttp()
    }

    @Test fun savedPagerUsesCanonicalSummariesAndLocalQueryOnly() = runTest {
        movies.upsertMovies((1L..45L).map { cachedMovie(it, "Movie $it") })
        for (id in 1L..45L) { now = id; repo().setMovieFavorite(id, true) }
        val saved = repo().getPagedSavedMovies().asSnapshot { scrollTo(44) }
        assertEquals((45L downTo 1L).toList(), saved.map { it.id })
        assertTrue(saved.all { it.details == null })
        movies.upsertMovies(listOf(cachedMovie(45, "Updated")))
        assertEquals("Updated", repo().getPagedSavedMovies().asSnapshot().first().title)
        assertEquals(listOf(45L), repo().getPagedSavedMovies(" updated ").asSnapshot().map { it.id })
        assertEquals(45, favorites.rows.value.size)
        noHttp()
    }
}

/** Coordination fake only; production Room SQL/transactions are checked separately. */
internal class FakeMovieFavoriteDao(private val movies: FakeMovieDao = FakeMovieDao()) : MovieFavoriteDao() {
    val rows = MutableStateFlow<List<MovieFavoriteEntity>>(emptyList())
    var before: suspend () -> Unit = {}
    var after: suspend () -> Unit = {}
    var readFailure: Throwable? = null
    override fun observeFavoriteMovieIds() = flow { readFailure?.let { throw it }; emitAll(rows.map { rows -> rows.map { it.movieId } }) }
    override fun observeIsFavorite(movieId: Long) = flow { readFailure?.let { throw it }; emitAll(rows.map { list -> list.any { it.movieId == movieId } }) }
    override suspend fun movieExists(movieId: Long) = movies.getMovie(movieId) != null
    override suspend fun addFavorite(favorite: MovieFavoriteEntity) {
        if (rows.value.none { it.movieId == favorite.movieId }) rows.value += favorite
    }
    override suspend fun removeFavorite(movieId: Long) { rows.value = rows.value.filterNot { it.movieId == movieId } }
    override suspend fun setFavorite(movieId: Long, isFavorite: Boolean, addedAtEpochMillis: Long): Boolean {
        before()
        val valid = super.setFavorite(movieId, isFavorite, addedAtEpochMillis)
        after()
        return valid
    }
    override fun pagingSource() = source("")
    var lastPattern: String? = null
    override fun searchPagingSource(pattern: String): PagingSource<Int, FavoriteMovie> { lastPattern = pattern; return source(pattern) }
    private fun source(pattern: String): PagingSource<Int, FavoriteMovie> = object : PagingSource<Int, FavoriteMovie>() {
        override fun getRefreshKey(state: PagingState<Int, FavoriteMovie>) = state.anchorPosition
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FavoriteMovie> {
            val all = rows.value.sortedWith(compareByDescending<MovieFavoriteEntity> { it.addedAtEpochMillis }.thenBy { it.movieId })
                .mapNotNull { favorite -> movies.getMovie(favorite.movieId)?.let { FavoriteMovie(it, favorite.addedAtEpochMillis) } }
                .filter { pattern.isEmpty() || it.movie.title.lowercase().contains(Regex("\\[([A-Z])[a-z]\\]").replace(pattern.trim('*')) { it.groupValues[1].lowercase() }) }
            val start = params.key ?: 0
            val end = minOf(all.size, start + params.loadSize)
            return LoadResult.Page(all.drop(start).take(end-start), start.takeIf { it > 0 }, end.takeIf { it < all.size }, start, all.size-end)
        }
    }
}
