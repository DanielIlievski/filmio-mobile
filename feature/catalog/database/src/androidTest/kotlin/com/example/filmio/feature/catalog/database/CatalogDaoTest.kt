package com.example.filmio.feature.catalog.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.filmio.feature.catalog.database.entities.MovieDetailEntity
import com.example.filmio.feature.catalog.database.entities.MovieDetailSnapshot
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.database.entities.MovieGenreEntity
import com.example.filmio.feature.catalog.database.entities.MovieProductionCompanyEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercise the production DAOs against actual Room, including generated transactions and paging. */
@RunWith(AndroidJUnit4::class)
class CatalogDaoTest {
    private lateinit var context: Context
    private lateinit var database: CatalogDatabase
    private val files = mutableListOf<String>()

    @Before
    fun openDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
        files.forEach { assertTrue(context.deleteDatabase(it)) }
    }

    @Test
    fun failedDetailReplacementRollsBackSummaryDetailAndBothChildLists() = runBlocking {
        seedDetail()
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
        val previous = database.movieDetailDao().getMovieDetail(1)

        expectConstraint {
            database.movieDetailDao().upsertMovieDetail(
                movie(1).copy(title = "Uncommitted"), detail(1).copy(tagline = "Uncommitted"),
                listOf(MovieGenreEntity(1, 99, "New genre", 0)),
                listOf(
                    MovieProductionCompanyEntity(1, 99, "New company", 0),
                    MovieProductionCompanyEntity(1, 100, "Duplicate position", 0),
                ),
            )
        }

        assertEquals(previous, database.movieDetailDao().getMovieDetail(1))
        assertTrue(database.movieFavoriteDao().observeIsFavorite(1).first())
        assertEquals(400L, database.movieFavoriteDao().pagingSource().items().single().addedAtEpochMillis)
    }

    @Test
    fun mismatchedSnapshotOwnersAreRejectedEvenWhenBothParentsExist() = runBlocking {
        seedDetail()
        database.movieDetailDao().upsertMovieDetail(movie(2), detail(2), emptyList(), emptyList())
        val first = database.movieDetailDao().getMovieDetail(1)
        val second = database.movieDetailDao().getMovieDetail(2)
        val invalidWrites: List<suspend () -> Unit> = listOf(
            { database.movieDetailDao().upsertMovieDetail(movie(1), detail(2), emptyList(), emptyList()) },
            {
                database.movieDetailDao().upsertMovieDetail(
                    movie(1), detail(1), listOf(MovieGenreEntity(2, 99, "Wrong owner", 0)), emptyList(),
                )
            },
            {
                database.movieDetailDao().upsertMovieDetail(
                    movie(1), detail(1), emptyList(), listOf(MovieProductionCompanyEntity(2, 99, "Wrong owner", 0)),
                )
            },
        )

        invalidWrites.forEach { write ->
            try {
                write()
                fail("Expected mismatched snapshot ownership to be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals(first, database.movieDetailDao().getMovieDetail(1))
                assertEquals(second, database.movieDetailDao().getMovieDetail(2))
            }
        }
    }

    @Test
    fun failedSummaryPageRollsBackEarlierRows() = runBlocking {
        seedDetail()
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
        val previous = database.movieDetailDao().getMovieDetail(1)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_movie BEFORE INSERT ON movies WHEN NEW.id = 99 " +
                "BEGIN SELECT RAISE(ABORT, 'test write failure'); END",
        )

        expectConstraint {
            database.movieDao().upsertMovies(listOf(movie(1).copy(title = "Uncommitted"), movie(99)))
        }

        assertEquals(previous, database.movieDetailDao().getMovieDetail(1))
        assertNull(database.movieDao().getMovie(99))
        assertTrue(database.movieFavoriteDao().observeIsFavorite(1).first())
    }

    @Test
    fun danglingFavoriteFailsAndRemovingMissingFavoriteIsHarmless() = runBlocking {
        expectConstraint { database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(99, 400)) }
        database.movieFavoriteDao().removeFavorite(99)

        assertFalse(database.movieFavoriteDao().observeIsFavorite(99).first())
        assertTrue(database.movieFavoriteDao().pagingSource().items().isEmpty())
    }

    @Test
    fun cancellationBeforeCommitPreservesPreviouslyCommittedSnapshot() = runBlocking {
        seedDetail()
        val previous = database.movieDetailDao().getMovieDetail(1)
        val written = CompletableDeferred<Unit>()
        val job = launch {
            // Outer transaction holds the commit so cancellation is deterministic, without DAO test hooks.
            database.withTransaction {
                database.movieDetailDao().upsertMovieDetail(
                    movie(1).copy(title = "Uncommitted"), detail(1), emptyList(), emptyList(),
                )
                written.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(5_000) { written.await() }
        } finally {
            job.cancelAndJoin()
        }

        assertEquals(previous, database.movieDetailDao().getMovieDetail(1))
    }

    @Test
    fun pageUpsertsAreAdditiveDeduplicateAndPreserveDetailsAndFavorites() = runBlocking {
        seedDetail()
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
        val previous = database.movieDetailDao().getMovieDetail(1)!!
        database.movieDao().upsertMovies(listOf(movie(2), movie(3), movie(3)))
        database.movieDao().upsertMovies(listOf(movie(1).copy(title = "Updated", updatedAtEpochMillis = 900)))
        database.movieDao().upsertMovies(emptyList())

        assertEquals(listOf(2L, 3L, 1L), database.movieDao().pagingSource().items().map { it.id })
        val updated = database.movieDetailDao().getMovieDetail(1)!!
        assertEquals("Updated", updated.movie.title)
        assertEquals(previous.detail, updated.detail)
        assertEquals(previous.genres, updated.genres)
        assertEquals(previous.productionCompanies, updated.productionCompanies)
        assertTrue(database.movieFavoriteDao().observeIsFavorite(1).first())
    }

    @Test
    fun detailReadsDistinguishMissingSummaryOnlyAndFetchedNullableDetailsAndKeepChildOrder() = runBlocking {
        assertNull(database.movieDetailDao().getMovieDetail(1))
        assertNull(database.movieDetailDao().observeMovieDetail(1).first())
        database.movieDao().upsertMovies(listOf(movie(1)))
        val summaryOnly = database.movieDetailDao().getMovieDetail(1)!!
        assertNull(summaryOnly.detail)
        assertTrue(summaryOnly.genres.isEmpty())
        seedDetail()

        val fetched = database.movieDetailDao().getMovieDetail(1)!!
        assertNotNull(fetched.detail)
        assertEquals(listOf(28L, 12L), fetched.genres.map { it.genreId })
        assertEquals(listOf(25L, 1L), fetched.productionCompanies.map { it.companyId })
        assertEquals(fetched, database.movieDetailDao().observeMovieDetail(1).first())

        val nullable = detail(1).copy(
            runtimeMinutes = null, tagline = null, status = null, budget = null, revenue = null,
            homepage = null, imdbId = null, collectionId = null, collectionName = null,
        )
        database.movieDetailDao().upsertMovieDetail(movie(1), nullable, emptyList(), emptyList())
        val replaced = database.movieDetailDao().getMovieDetail(1)!!
        assertEquals(nullable, replaced.detail)
        assertTrue(replaced.genres.isEmpty())
        assertTrue(replaced.productionCompanies.isEmpty())
    }

    @Test
    fun replacingChildrenSupportsReorderingAndClearsOmittedChildrenAndCollection() = runBlocking {
        seedDetail()
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
        database.movieDetailDao().upsertMovieDetail(
            movie(1), detail(1).copy(collectionId = null, collectionName = null),
            listOf(MovieGenreEntity(1, 12, "Adventure", 0), MovieGenreEntity(1, 28, "Action", 1)),
            listOf(MovieProductionCompanyEntity(1, 1, "Independent", 0)),
        )

        val updated = database.movieDetailDao().getMovieDetail(1)!!
        assertNull(updated.detail!!.collectionId)
        assertNull(updated.detail.collectionName)
        assertEquals(listOf(12L, 28L), updated.genres.map { it.genreId })
        assertEquals(listOf(1L), updated.productionCompanies.map { it.companyId })
        assertTrue(database.movieFavoriteDao().observeIsFavorite(1).first())
    }

    @Test
    fun searchUsesTrimmedLiteralSubstringsAsciiCaseOriginalTitleAndDeterministicOrder() = runBlocking {
        database.movieDao().upsertMovies(listOf(
            movie(2).copy(title = "alpha"), movie(1).copy(title = "Alpha"),
            movie(3).copy(title = "Other", originalTitle = "ALPHABET"),
            movie(4).copy(title = "100%_Fun"), movie(5).copy(title = "100 percent"),
            movie(6).copy(title = "ÉTÉ"), movie(7).copy(title = "Quoted ' title"),
            movie(8).copy(title = "Literal *? [a-z] \\ path"), movie(9).copy(title = "星の物語"),
        ))

        assertEquals(listOf(1L, 2L, 3L), searchIds("  ALPHA  "))
        assertEquals(listOf(4L), searchIds("%_"))
        assertEquals(listOf(4L), searchIds("_"))
        assertEquals(listOf(6L), searchIds("É"))
        assertTrue(searchIds("é").isEmpty())
        assertEquals(listOf(7L), searchIds("'"))
        assertEquals(listOf(8L), searchIds("*?"))
        assertEquals(listOf(8L), searchIds("[a-z]"))
        assertEquals(listOf(8L), searchIds("]"))
        assertEquals(listOf(8L), searchIds("\\"))
        assertEquals(listOf(9L), searchIds("星"))
        assertTrue(searchIds("' OR 1=1 --").isEmpty())
        assertTrue(searchIds("unseen").isEmpty())
        assertTrue(searchIds(" \t\n", listOf(1)).isEmpty())
    }

    @Test
    fun searchUnionsOnlyCommittedRemoteRowsDeduplicatesAndSupportsLargeSessions() = runBlocking {
        database.movieDao().upsertMovies(listOf(movie(1).copy(title = "Alpha"), movie(2).copy(title = "Beta")))
        assertEquals(listOf(1L), searchIds("alpha", listOf(3)))
        database.movieDao().upsertMovies(listOf(movie(3).copy(title = "Gamma")))

        assertEquals(listOf(1L, 2L, 3L), searchIds("alpha", listOf(1, 2, 3, 3, 99)))
        assertEquals(listOf(2L), searchIds("beta"))
        // A full TMDB search session can exceed Android SQLite's older 999-bind limit.
        assertEquals(listOf(1L, 2L, 3L), searchIds("unmatched", (1L..10_000L).toList()))
    }

    @Test
    fun favoritesAreNewestFirstStableForTiesIdempotentAndFollowUpdatedSummaries() = runBlocking {
        database.movieDao().upsertMovies(listOf(movie(1), movie(2), movie(3)))
        val dao = database.movieFavoriteDao()
        dao.addFavorite(MovieFavoriteEntity(1, 100))
        dao.addFavorite(MovieFavoriteEntity(2, 200))
        dao.addFavorite(MovieFavoriteEntity(3, 200))
        dao.addFavorite(MovieFavoriteEntity(1, 999))
        database.movieDao().upsertMovies(listOf(movie(1).copy(title = "Updated")))

        val favorites = dao.pagingSource().items()
        assertEquals(listOf(2L, 3L, 1L), favorites.map { it.movie.id })
        assertEquals(100L, favorites.last().addedAtEpochMillis)
        assertEquals("Updated", favorites.last().movie.title)
        dao.removeFavorite(2)
        dao.removeFavorite(2)
        assertFalse(dao.observeIsFavorite(2).first())
        assertNotNull(database.movieDao().getMovie(2))
        dao.addFavorite(MovieFavoriteEntity(2, 300))
        assertEquals(listOf(2L, 3L, 1L), dao.pagingSource().items().map { it.movie.id })
    }

    @Test
    fun pagingReadsMultiplePagesAndSourcesInvalidateOnRelevantWrites() = runBlocking {
        database.movieDao().upsertMovies((1L..6L).map(::movie))
        val source = database.movieDao().pagingSource()
        val first = source.load(PagingSource.LoadParams.Refresh(null, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf(1L, 2L), first.data.map { it.id })
        val second = source.load(PagingSource.LoadParams.Append(first.nextKey!!, 2, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf(3L, 4L), second.data.map { it.id })
        val search = database.movieDao().searchPagingSource("Movie")
        search.items()
        val favorite = database.movieFavoriteDao().pagingSource()
        favorite.items()
        val catalogInvalid = CompletableDeferred<Unit>()
        val searchInvalid = CompletableDeferred<Unit>()
        val favoritesInvalid = CompletableDeferred<Unit>()
        source.registerInvalidatedCallback { catalogInvalid.complete(Unit) }
        search.registerInvalidatedCallback { searchInvalid.complete(Unit) }
        favorite.registerInvalidatedCallback { favoritesInvalid.complete(Unit) }

        database.movieDao().upsertMovies(listOf(movie(7)))
        withTimeout(5_000) { catalogInvalid.await(); searchInvalid.await(); favoritesInvalid.await() }

        val newFavorites = database.movieFavoriteDao().pagingSource()
        newFavorites.items()
        val favoriteAdded = CompletableDeferred<Unit>()
        newFavorites.registerInvalidatedCallback { favoriteAdded.complete(Unit) }
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(7, 400))
        withTimeout(5_000) { favoriteAdded.await() }
        assertEquals(listOf(7L), database.movieFavoriteDao().pagingSource().items().map { it.movie.id })
    }

    @Test
    fun detailAndFavoriteObserversEmitCommittedChanges() = runBlocking {
        coroutineScope {
            val details = Channel<MovieDetailSnapshot?>(Channel.UNLIMITED)
            val favorites = Channel<Boolean>(Channel.UNLIMITED)
            val detailJob = launch(start = CoroutineStart.UNDISPATCHED) {
                database.movieDetailDao().observeMovieDetail(1).collect { details.send(it) }
            }
            val favoriteJob = launch(start = CoroutineStart.UNDISPATCHED) {
                database.movieFavoriteDao().observeIsFavorite(1).collect { favorites.send(it) }
            }
            try {
                assertNull(withTimeout(5_000) { details.receive() })
                assertFalse(withTimeout(5_000) { favorites.receive() })
                seedDetail()
                val fetched = withTimeout(5_000) {
                    var current = details.receive()
                    while (current?.detail == null) current = details.receive()
                    current
                }
                assertEquals(listOf(28L, 12L), fetched!!.genres.map { it.genreId })
                database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
                withTimeout(5_000) { while (!favorites.receive()) Unit }
                database.movieDetailDao().upsertMovieDetail(movie(1), detail(1), emptyList(), emptyList())
                withTimeout(5_000) {
                    while (details.receive()?.genres?.isNotEmpty() != false) Unit
                }
                database.movieFavoriteDao().removeFavorite(1)
                withTimeout(5_000) { while (favorites.receive()) Unit }
            } finally {
                detailJob.cancelAndJoin()
                favoriteJob.cancelAndJoin()
                details.close()
                favorites.close()
            }
        }
    }

    @Test
    fun daoWritesAndOrderedReadsSurviveFileBackedOfflineReopen() = runBlocking {
        val name = "catalog-dao-test-${UUID.randomUUID()}.db"
        files += name
        database.close()
        database = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
        seedDetail()
        database.movieFavoriteDao().addFavorite(MovieFavoriteEntity(1, 400))
        val previous = database.movieDetailDao().getMovieDetail(1)
        database.close()
        database = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()

        assertEquals(previous, database.movieDetailDao().getMovieDetail(1))
        assertEquals(listOf(1L), searchIds("Movie 1"))
        assertEquals(listOf(1L), database.movieFavoriteDao().pagingSource().items().map { it.movie.id })
        assertEquals(1, database.openHelper.writableDatabase.version)
    }

    private suspend fun seedDetail() {
        database.movieDetailDao().upsertMovieDetail(
            movie(1), detail(1),
            listOf(MovieGenreEntity(1, 12, "Adventure", 1), MovieGenreEntity(1, 28, "Action", 0)),
            listOf(MovieProductionCompanyEntity(1, 1, "Independent", 1), MovieProductionCompanyEntity(1, 25, "Studio", 0)),
        )
    }

    private suspend fun searchIds(query: String, ids: List<Long> = emptyList()) =
        database.movieDao().searchPagingSource(query, ids).items().map { it.id }

    private suspend fun <T : Any> PagingSource<Int, T>.items(): List<T> {
        val result = load(PagingSource.LoadParams.Refresh(null, 100, false))
        assertTrue("Expected a page, got $result", result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }

    private suspend fun expectConstraint(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected SQLite to reject the invalid write")
        } catch (_: SQLiteConstraintException) {
            // The local boundary propagates constraint failures; it does not turn them into network errors.
        }
    }

    private fun movie(id: Long) = MovieEntity(
        id = id, title = "Movie $id", overview = null, posterPath = null, backdropPath = null,
        releaseDate = null, voteAverage = null, voteCount = null, originalTitle = null,
        originalLanguage = null, updatedAtEpochMillis = 100,
    )

    private fun detail(id: Long) = MovieDetailEntity(
        movieId = id, runtimeMinutes = 0, tagline = "Tagline", status = "Released",
        budget = Int.MAX_VALUE.toLong() + 1, revenue = 5_000_000_000L, homepage = null, imdbId = null,
        collectionId = 10, collectionName = "Collection", fetchedAtEpochMillis = 200,
    )
}
