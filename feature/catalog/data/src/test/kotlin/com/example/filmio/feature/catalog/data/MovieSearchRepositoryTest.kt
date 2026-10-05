package com.example.filmio.feature.catalog.data

import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.testing.asSnapshot
import com.example.filmio.feature.catalog.data.mapping.toSnapshot
import com.example.filmio.feature.catalog.data.networking.dto.MovieDetailsDto
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.MovieSearchResponseDto
import com.example.filmio.feature.catalog.data.paging.FakeMovieDao
import com.example.filmio.feature.catalog.data.paging.FakeTmdbService
import com.example.filmio.feature.catalog.data.paging.cachedMovie
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException
import java.time.Clock

/** Coordination and query-construction evidence only; this does not run Room/SQLite. */
@OptIn(ExperimentalCoroutinesApi::class)
class MovieSearchRepositoryTest {
    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeMovieDao()
    private val service = FakeTmdbService()
    private val details = FakeMovieDetailDao(dao)
    private val repository = OfflineFirstCatalogRepository(service, dao, details, Clock.systemUTC())
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun presenter() = object : PagingDataPresenter<Movie>(dispatcher) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Movie>) = Unit
    }

    @Test fun localOnlyMatchesImmediatelyAndRemotePagerReadsBeforeSlowOrOfflineHttp() = runTest(dispatcher) {
        dao.rows.value = listOf(cachedMovie(1, "Interstellar"), cachedMovie(2, "Arrival").copy(originalTitle = "Inter"))
        assertEquals(listOf(1L, 2L), repository.searchMovies("  iNtEr  ", false).asSnapshot().map { it.id })
        assertTrue(service.searchPages.isEmpty())
        val gate = CompletableDeferred<Unit>()
        service.searchResponse = { gate.await(); throw UnknownHostException() }
        val presenter = presenter()
        backgroundScope.launch { repository.searchMovies("  Inter  ").collectLatest { presenter.collectFrom(it) } }
        runCurrent()
        assertEquals(2, presenter.size)
        assertEquals(listOf("Inter"), service.searchQueries)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, presenter.size)
    }

    @Test fun membershipInvalidatesAfterCommitIncludesRemoteOnlyIdsAndContinuesNullDuplicatePages() = runTest(dispatcher) {
        dao.rows.value = listOf(cachedMovie(1, "Interstellar"), cachedMovie(2, "Arrival"))
        service.searchResponse = { page -> when(page) {
            1 -> MovieSearchResponseDto(page, listOf(MovieDto(2, "Arrival"), MovieDto(1, "Interstellar")))
            2 -> MovieSearchResponseDto(page, listOf(null))
            3 -> MovieSearchResponseDto(page, listOf(MovieDto(2, "Arrival")))
            else -> MovieSearchResponseDto(page, listOf(MovieDto(3, "Zodiac")), 4)
        } }
        val results = repository.searchMovies("Inter").asSnapshot { scrollTo(10) }
        assertEquals(listOf(2L, 1L, 3L), results.map { it.id })
        assertEquals(listOf(1, 2, 3, 4), service.searchPages)
        assertTrue(dao.searchSql.any { "id IN (2, 1, 3)" in it })
    }

    @Test fun searchEnrichesHomeAndPreservesFakeDetailsFavoritesAndUnrelatedContent() = runTest(dispatcher) {
        details.seed(MovieDetailsDto(1, "Interstellar", runtime = 169).toSnapshot(1, 123)!!)
        details.favorites[1] = MovieFavoriteEntity(1, 123)
        val before = details.details.value
        dao.upsertMovies(listOf(cachedMovie(99, "Unrelated")))
        service.searchResponse = { page -> MovieSearchResponseDto(page, listOf(MovieDto(1, "Interstellar updated"), MovieDto(2, "Interceptor")), 1) }
        assertEquals(2, repository.searchMovies("Inter").asSnapshot().size)
        assertEquals(before, details.details.value)
        assertEquals(MovieFavoriteEntity(1, 123), details.favorites[1])
        assertEquals(1, details.favorites.size)
        assertNotNull(dao.getMovie(99))
        service.respond = { throw UnknownHostException() }
        val home = presenter()
        backgroundScope.launch { repository.getPagedMovies().collectLatest { home.collectFrom(it) } }
        runCurrent()
        assertEquals(setOf(1L, 2L, 99L), home.snapshot().items.map { it.id }.toSet())
    }

    @Test fun blankQueryNeverRequestsEvenWithRemoteEnabled() = runTest(dispatcher) {
        dao.rows.value = listOf(cachedMovie(1, "Arrival"))
        assertTrue(repository.searchMovies("   ").asSnapshot().isEmpty())
        assertTrue(service.searchPages.isEmpty())
    }

    @Test fun queryFactoryBindsLiteralPatternAndOnlyTypedDistinctIds() {
        dao.searchPagingSource("  aAéÉ%_*?[]'  ", listOf(1, 1, Long.MAX_VALUE))
        assertEquals(listOf("aAéÉ%_*?[]'", "*[Aa][Aa]éÉ%_[*][?][[][]]'*", "*[Aa][Aa]éÉ%_[*][?][[][]]'*"), dao.searchBindings.last())
        assertTrue(dao.searchSql.last().contains("coalesce(originalTitle, '') GLOB ?"))
        assertTrue(dao.searchSql.last().contains("id IN (1, 9223372036854775807)"))
        assertFalse(dao.searchSql.last().contains("aAé"))
        assertTrue(dao.searchSql.last().endsWith("ORDER BY title COLLATE NOCASE ASC, id ASC"))
        dao.searchPagingSource(" \t ", listOf(42))
        assertEquals("", dao.searchBindings.last()[0])
        assertFalse(dao.searchSql.last().contains("id IN"))
    }
}
