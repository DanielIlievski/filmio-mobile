package com.example.filmio.feature.catalog.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.filmio.feature.catalog.database.entities.MovieDetailEntity
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.database.entities.MovieGenreEntity
import com.example.filmio.feature.catalog.database.entities.MovieProductionCompanyEntity
import com.example.filmio.feature.catalog.database.util.literalTitleSearchPattern
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

/** Direct SQL fixtures complement production DAO tests with schema-level constraint coverage. */
@RunWith(AndroidJUnit4::class)
class CatalogDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: CatalogDatabase
    private lateinit var fixture: CatalogFixture
    private val files = mutableListOf<String>()

    @Before
    fun openDatabase() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
        fixture = CatalogFixture(database.openHelper.writableDatabase)
    }

    @After
    fun closeDatabase() {
        database.close()
        files.forEach { name ->
            context.deleteDatabase(name)
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                assertFalse(context.getDatabasePath(name + suffix).exists())
            }
        }
    }

    @Test
    fun schemaContainsExactlyTheFiveContentAndFavoriteTables() {
        assertEquals(
            listOf("movie_details", "movie_favorites", "movie_genres", "movie_production_companies", "movies"),
            fixture.userTables(),
        )
        assertEquals(1, fixture.sql.version)
    }

    @Test
    fun missingParentsAreRejectedWithoutChangingCommittedRows() {
        seedSnapshot()
        val previous = fixture.snapshot()
        val invalidWrites = listOf<() -> Unit>(
            { fixture.insertDetail(detail(99)) },
            { fixture.insertGenre(MovieGenreEntity(99, 12, "Adventure", 0)) },
            // A canonical summary alone is not a fetched detail parent.
            { fixture.insertGenre(MovieGenreEntity(2, 12, "Adventure", 0)) },
            { fixture.insertCompany(MovieProductionCompanyEntity(99, 25, "Studio", 0)) },
            { fixture.insertCompany(MovieProductionCompanyEntity(2, 25, "Studio", 0)) },
            { fixture.insertFavorite(MovieFavoriteEntity(99, 400)) },
        )
        invalidWrites.forEach { write ->
            expectConstraint(write)
            assertEquals(previous, fixture.snapshot())
        }
    }

    @Test
    fun duplicateIdentityAndDetailPositionAreRejectedWithoutLosingRows() {
        seedSnapshot()
        val previous = fixture.snapshot()
        val invalidWrites = listOf<() -> Unit>(
            { fixture.insertMovie(movie(1).copy(title = "Duplicate")) },
            { fixture.insertDetail(detail(1)) },
            { fixture.insertFavorite(MovieFavoriteEntity(1, 999)) },
            { fixture.insertGenre(MovieGenreEntity(1, 12, "Duplicate", 9)) },
            { fixture.insertGenre(MovieGenreEntity(1, 99, "Another", 0)) },
            { fixture.insertCompany(MovieProductionCompanyEntity(1, 25, "Duplicate", 9)) },
            { fixture.insertCompany(MovieProductionCompanyEntity(1, 99, "Another", 0)) },
        )
        invalidWrites.forEach { write ->
            expectConstraint(write)
            assertEquals(previous, fixture.snapshot())
        }
    }

    @Test
    fun failedCompoundTransactionRollsBackAllFiveTables() {
        seedSnapshot()
        val previous = fixture.snapshot()

        expectConstraint {
            fixture.transaction {
                fixture.writeMovie(movie(1).copy(title = "Uncommitted", updatedAtEpochMillis = 900))
                fixture.sql.execSQL("UPDATE movie_details SET tagline = 'Uncommitted', fetchedAtEpochMillis = 900 WHERE movieId = 1")
                fixture.sql.execSQL("DELETE FROM movie_genres WHERE movieId = 1")
                fixture.insertGenre(MovieGenreEntity(1, 99, "Uncommitted", 0))
                fixture.sql.execSQL("DELETE FROM movie_production_companies WHERE movieId = 1")
                fixture.insertCompany(MovieProductionCompanyEntity(1, 99, "Uncommitted", 0))
                fixture.sql.execSQL("DELETE FROM movie_favorites WHERE movieId = 1")
                fixture.insertFavorite(MovieFavoriteEntity(2, 900))
                // Fail after changing every table, before committing.
                fixture.insertGenre(MovieGenreEntity(99, 12, "Invalid parent", 0))
            }
        }

        assertEquals(previous, fixture.snapshot())
    }

    @Test
    fun popularAndSearchPageWritesShareCatalogAndPreserveEnrichment() {
        seedSnapshot() // Popular A/B; movie A has details and a favorite.
        val extended = fixture.detail(1)
        val children = fixture.detailChildren(1)
        val favorites = fixture.rows("SELECT * FROM movie_favorites")

        fixture.writePage(listOf(
            movie(2).copy(title = "Beta refreshed"),
            movie(3).copy(title = "Gamma"),
            movie(3).copy(title = "Gamma"),
        )) // Search B/C, including a repeated ID.
        fixture.writePage(listOf(movie(1).copy(title = "Alpha refreshed", updatedAtEpochMillis = 900)))

        assertEquals(3L, fixture.count("movies"))
        assertEquals(listOf(1L, 2L, 3L), fixture.catalogIds())
        assertEquals("Alpha refreshed", fixture.movie(1)?.title)
        assertEquals("Beta refreshed", fixture.movie(2)?.title)
        assertEquals(extended, fixture.detail(1))
        assertEquals(children, fixture.detailChildren(1))
        assertEquals(favorites, fixture.rows("SELECT * FROM movie_favorites"))
        assertNull(fixture.detail(2))
        assertNull(fixture.detail(3))
    }

    @Test
    fun refreshWithDifferentMoviesAndEmptyPageKeepsPriorCatalogDetailsAndFavorites() {
        seedSnapshot()
        val summary = fixture.movie(1)
        val extended = fixture.detail(1)
        val children = fixture.detailChildren(1)
        val favorites = fixture.rows("SELECT * FROM movie_favorites")

        fixture.writePage(listOf(movie(3).copy(title = "Gamma")))

        assertEquals(listOf(1L, 2L, 3L), fixture.catalogIds())
        assertEquals(summary, fixture.movie(1))
        assertEquals(extended, fixture.detail(1))
        assertEquals(children, fixture.detailChildren(1))
        assertEquals(favorites, fixture.rows("SELECT * FROM movie_favorites"))
        val afterRefresh = fixture.snapshot()

        fixture.writePage(emptyList())

        assertEquals(afterRefresh, fixture.snapshot())
    }

    @Test
    fun localSearchUsesStoredTitlesWithoutRequestHistoryAndTreatsPunctuationLiterally() {
        seedSnapshot()
        fixture.insertMovie(movie(3).copy(title = "Different", originalTitle = "The ALPHA original"))
        fixture.insertMovie(movie(4).copy(title = "100%_Movie"))
        fixture.insertMovie(movie(5).copy(title = "星の物語"))
        fixture.insertMovie(movie(6).copy(title = "ÉCLAIR"))

        assertEquals(listOf(1L, 3L), fixture.searchIds(" alpha "))
        assertEquals(listOf(3L), fixture.searchIds("original"))
        assertEquals(listOf(4L), fixture.searchIds("%"))
        assertEquals(listOf(4L), fixture.searchIds("_"))
        assertEquals(listOf(5L), fixture.searchIds("星"))
        assertEquals(listOf(6L), fixture.searchIds("É"))
        assertTrue(fixture.searchIds("é").isEmpty())
        assertTrue(fixture.searchIds("missing").isEmpty())
        assertTrue(fixture.searchIds("   ").isEmpty())
        assertEquals(5, fixture.userTables().size)
    }

    @Test
    fun searchUnionsLocalMatchesWithCommittedRemoteIdsWithoutDuplicates() {
        seedSnapshot()
        val remoteIds = listOf(1L, 2L, 3L, 3L)

        assertEquals(listOf(1L), fixture.searchIds("alpha"))
        // Even an active ID cannot expose content that has not been committed.
        assertEquals(listOf(1L), fixture.searchIds("alpha", listOf(3L)))

        fixture.writePage(listOf(movie(3).copy(title = "Gamma")))

        assertEquals(listOf(1L, 2L, 3L), fixture.searchIds("alpha", remoteIds))
        assertEquals(3L, fixture.count("movies"))
        // A new query with no remote IDs cannot inherit the old query's membership.
        assertEquals(listOf(2L), fixture.searchIds("beta"))
    }

    @Test
    fun unreferencedMovieDeletionCascadesOnlyItsDetailChildren() {
        seedSnapshot()
        val firstMovie = fixture.movie(1)
        val firstChildren = fixture.detailChildren(1)
        val favorites = fixture.rows("SELECT * FROM movie_favorites")
        fixture.insertDetail(detail(2))
        fixture.insertGenre(MovieGenreEntity(2, 12, "Adventure", 0))
        fixture.insertCompany(MovieProductionCompanyEntity(2, 25, "Studio", 0))

        fixture.sql.execSQL("DELETE FROM movies WHERE id = 2")

        assertNull(fixture.movie(2))
        assertNull(fixture.detail(2))
        assertTrue(fixture.detailChildren(2).all { it.isEmpty() })
        assertEquals(firstMovie, fixture.movie(1))
        assertEquals(firstChildren, fixture.detailChildren(1))
        assertEquals(favorites, fixture.rows("SELECT * FROM movie_favorites"))
    }

    @Test
    fun fileBackedReopenRetainsSharedCatalogDetailsOrderedChildrenFavoritesAndLocalSearch() {
        val name = "catalog-test-${UUID.randomUUID()}.db"
        files += name
        database.close()
        database = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
        fixture = CatalogFixture(database.openHelper.writableDatabase)
        seedSnapshot()
        fixture.writePage(listOf(movie(3).copy(title = "Gamma")))
        val previous = fixture.snapshot()

        database.close()
        database = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
        fixture = CatalogFixture(database.openHelper.writableDatabase)

        assertEquals(1, fixture.sql.version)
        assertEquals(5, fixture.userTables().size)
        assertEquals(previous, fixture.snapshot())
        assertEquals(listOf(1L, 2L, 3L), fixture.catalogIds())
        assertNotNull(fixture.movie(1))
        assertEquals(detail(1), fixture.detail(1))
        assertNotNull(fixture.movie(2))
        assertNull(fixture.detail(2))
        assertEquals(
            listOf(listOf(12L, "Adventure"), listOf(28L, "Action")),
            fixture.rows("SELECT genreId, name FROM movie_genres WHERE movieId = 1 ORDER BY position"),
        )
        assertEquals(
            listOf(listOf(25L, "Studio"), listOf(1L, "Independent")),
            fixture.rows("SELECT companyId, name FROM movie_production_companies WHERE movieId = 1 ORDER BY position"),
        )
        assertEquals(listOf(1L), fixture.searchIds("ALPHA"))
        assertEquals(listOf(2L), fixture.searchIds("beta"))
        assertEquals(listOf(listOf(1L, 400L)), fixture.rows("SELECT * FROM movie_favorites"))
    }

    private fun seedSnapshot() {
        fixture.transaction {
            fixture.insertMovie(movie(1).copy(title = "Alpha"))
            fixture.insertMovie(movie(2).copy(title = "Beta"))
            fixture.insertDetail(detail(1))
            fixture.insertGenre(MovieGenreEntity(1, 12, "Adventure", 0))
            fixture.insertGenre(MovieGenreEntity(1, 28, "Action", 1))
            // Company IDs intentionally do not sort in response order.
            fixture.insertCompany(MovieProductionCompanyEntity(1, 25, "Studio", 0))
            fixture.insertCompany(MovieProductionCompanyEntity(1, 1, "Independent", 1))
            fixture.insertFavorite(MovieFavoriteEntity(1, 400))
        }
    }

    @Test
    fun favoriteAloneProtectsCanonicalMovieAndItsDetailChildren() {
        fixture.insertMovie(movie(1))
        fixture.insertDetail(detail(1))
        fixture.insertGenre(MovieGenreEntity(1, 12, "Adventure", 0))
        fixture.insertCompany(MovieProductionCompanyEntity(1, 25, "Studio", 0))
        fixture.insertFavorite(MovieFavoriteEntity(1, 400))
        val previous = fixture.snapshot()

        expectConstraint { fixture.sql.execSQL("DELETE FROM movies WHERE id = 1") }

        assertEquals(previous, fixture.snapshot())
        assertEquals(listOf(listOf(1L, 400L)), fixture.rows("SELECT * FROM movie_favorites"))
    }

    @Test
    fun nullableAndKnownZeroAndLargeValuesRoundTripThroughActualSchema() {
        val summary = movie(1).copy(voteAverage = 0.0, voteCount = 0)
        val extended = detail(1).copy(
            runtimeMinutes = 0,
            budget = Int.MAX_VALUE.toLong() + 1,
            revenue = 5_000_000_000L,
            collectionId = null,
            collectionName = null,
        )
        fixture.insertMovie(summary)
        fixture.insertDetail(extended)
        fixture.insertMovie(movie(2))
        fixture.insertDetail(detail(2).copy(budget = 0, revenue = 0))
        fixture.insertMovie(movie(3))
        fixture.insertDetail(detail(3).copy(budget = null, revenue = null, runtimeMinutes = null))

        assertEquals(summary, fixture.movie(1))
        assertEquals(extended, fixture.detail(1))
        assertNull(fixture.movie(1)?.posterPath)
        assertNull(fixture.movie(1)?.backdropPath)
        assertNull(fixture.movie(1)?.releaseDate)
        assertEquals(0L, fixture.detail(2)?.budget)
        assertEquals(0L, fixture.detail(2)?.revenue)
        assertNull(fixture.detail(3)?.budget)
        assertNull(fixture.detail(3)?.revenue)
        assertNull(fixture.detail(3)?.runtimeMinutes)
        assertNull(fixture.movie(3)?.voteAverage)
        assertNull(fixture.movie(3)?.voteCount)
    }

    @Test
    fun deletingDetailCascadesOnlyOwnedChildren() {
        seedSnapshot()
        val movie = fixture.movie(1)
        val favorites = fixture.rows("SELECT * FROM movie_favorites")

        fixture.sql.execSQL("DELETE FROM movie_details WHERE movieId = 1")

        assertNull(fixture.detail(1))
        assertTrue(fixture.detailChildren(1).all { it.isEmpty() })
        assertEquals(movie, fixture.movie(1))
        assertEquals(favorites, fixture.rows("SELECT * FROM movie_favorites"))
    }

    private fun movie(id: Long) = MovieEntity(
        id = id, title = "Movie $id", overview = null, posterPath = null,
        backdropPath = null, releaseDate = null, voteAverage = null, voteCount = null,
        originalTitle = null, originalLanguage = null, updatedAtEpochMillis = 100,
    )

    private fun detail(id: Long) = MovieDetailEntity(
        movieId = id, runtimeMinutes = 121, tagline = "Tagline", status = "Released",
        budget = 11_000_000, revenue = 775_398_007, homepage = "https://example.com/movie",
        imdbId = "tt0000001", collectionId = 10, collectionName = "Collection",
        fetchedAtEpochMillis = 200,
    )

    private fun expectConstraint(block: () -> Unit) {
        try {
            block()
            fail("Expected SQLite to reject the invalid write")
        } catch (_: SQLiteConstraintException) {
            // A schema defect should fail this test, not be reclassified as a network error.
        }
    }

}

/** Only test code gets direct SQL access; these helpers are not a persistence API. */
private class CatalogFixture(val sql: SupportSQLiteDatabase) {
    fun writePage(movies: List<MovieEntity>) = transaction {
        movies.forEach(::writeMovie)
    }

    fun catalogIds(): List<Long> = rows(
        "SELECT id FROM movies ORDER BY title COLLATE NOCASE, id",
    ).map { it.single() as Long }

    fun userTables(): List<String> = rows(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
    ).map { it.single() as String }

    fun searchIds(rawQuery: String, remoteIds: List<Long> = emptyList()): List<Long> {
        val query = rawQuery.trim()
        val pattern = literalTitleSearchPattern(query)
        val remoteClause = if (remoteIds.isEmpty()) "0" else "id IN (${remoteIds.joinToString { "?" }})"
        return rows(
            "SELECT id FROM movies WHERE (? <> '' AND (title GLOB ? " +
                "OR coalesce(originalTitle, '') GLOB ?)) OR $remoteClause " +
                "ORDER BY title COLLATE NOCASE, id",
            (listOf<Any?>(query, pattern, pattern) + remoteIds).toTypedArray(),
        ).map { it.single() as Long }
    }

    fun transaction(block: () -> Unit) {
        sql.beginTransaction()
        try {
            block()
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    fun insertMovie(movie: MovieEntity) = insert("movies", *movie.values())

    fun writeMovie(movie: MovieEntity) {
        val values = movie.values()
        insert("movies", *values, conflict = "OR IGNORE")
        val changed = values.filter { it.first != "id" }
        sql.execSQL(
            "UPDATE movies SET ${changed.joinToString { "${it.first} = ?" }} WHERE id = ?",
            (changed.map { it.second } + movie.id).toTypedArray(),
        )
    }

    fun insertDetail(detail: MovieDetailEntity) = insert(
        "movie_details", "movieId" to detail.movieId, "runtimeMinutes" to detail.runtimeMinutes,
        "tagline" to detail.tagline, "status" to detail.status, "budget" to detail.budget,
        "revenue" to detail.revenue, "homepage" to detail.homepage, "imdbId" to detail.imdbId,
        "collectionId" to detail.collectionId, "collectionName" to detail.collectionName,
        "fetchedAtEpochMillis" to detail.fetchedAtEpochMillis,
    )

    fun insertGenre(genre: MovieGenreEntity) = insert(
        "movie_genres", "movieId" to genre.movieId, "genreId" to genre.genreId,
        "name" to genre.name, "position" to genre.position,
    )

    fun insertCompany(company: MovieProductionCompanyEntity) = insert(
        "movie_production_companies", "movieId" to company.movieId, "companyId" to company.companyId,
        "name" to company.name, "position" to company.position,
    )

    fun insertFavorite(favorite: MovieFavoriteEntity) = insert(
        "movie_favorites", "movieId" to favorite.movieId, "addedAtEpochMillis" to favorite.addedAtEpochMillis,
    )

    fun movie(id: Long): MovieEntity? = sql.query("SELECT * FROM movies WHERE id = ?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else MovieEntity(
            id = c.getLong(c.getColumnIndexOrThrow("id")), title = c.getString(c.getColumnIndexOrThrow("title")),
            overview = c.string("overview"), posterPath = c.string("posterPath"),
            backdropPath = c.string("backdropPath"), releaseDate = c.string("releaseDate"),
            voteAverage = c.double("voteAverage"), voteCount = c.long("voteCount")?.toInt(),
            originalTitle = c.string("originalTitle"), originalLanguage = c.string("originalLanguage"),
            updatedAtEpochMillis = c.getLong(c.getColumnIndexOrThrow("updatedAtEpochMillis")),
        )
    }

    fun detail(id: Long): MovieDetailEntity? = sql.query("SELECT * FROM movie_details WHERE movieId = ?", arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else MovieDetailEntity(
            movieId = c.getLong(c.getColumnIndexOrThrow("movieId")), runtimeMinutes = c.long("runtimeMinutes")?.toInt(),
            tagline = c.string("tagline"), status = c.string("status"), budget = c.long("budget"),
            revenue = c.long("revenue"), homepage = c.string("homepage"), imdbId = c.string("imdbId"),
            collectionId = c.long("collectionId"), collectionName = c.string("collectionName"),
            fetchedAtEpochMillis = c.getLong(c.getColumnIndexOrThrow("fetchedAtEpochMillis")),
        )
    }

    fun count(table: String): Long = rows("SELECT COUNT(*) FROM $table").single().single() as Long

    fun detailChildren(id: Long) = listOf(
        rows("SELECT * FROM movie_genres WHERE movieId = ? ORDER BY position", arrayOf(id)),
        rows("SELECT * FROM movie_production_companies WHERE movieId = ? ORDER BY position", arrayOf(id)),
    )

    fun contentSnapshot() = mapOf(
        "movies" to rows("SELECT * FROM movies ORDER BY id"),
        "movie_details" to rows("SELECT * FROM movie_details ORDER BY movieId"),
        "movie_genres" to rows("SELECT * FROM movie_genres ORDER BY movieId, position"),
        "movie_production_companies" to rows("SELECT * FROM movie_production_companies ORDER BY movieId, position"),
    )

    fun snapshot() = contentSnapshot() + mapOf(
        "movie_favorites" to rows("SELECT * FROM movie_favorites ORDER BY movieId"),
    )

    fun rows(query: String, args: Array<out Any?> = emptyArray()): List<List<Any?>> = sql.query(query, args).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(List(c.columnCount) { column ->
                    when (c.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(column)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(column)
                        Cursor.FIELD_TYPE_STRING -> c.getString(column)
                        else -> error("Unexpected non-scalar storage")
                    }
                })
            }
        }
    }

    private fun insert(table: String, vararg values: Pair<String, Any?>, conflict: String = "") {
        sql.execSQL(
            "INSERT $conflict INTO $table (${values.joinToString { it.first }}) VALUES (${values.joinToString { "?" }})",
            values.map { it.second }.toTypedArray(),
        )
    }

    private fun MovieEntity.values(): Array<Pair<String, Any?>> = arrayOf(
        "id" to id, "title" to title, "overview" to overview, "posterPath" to posterPath,
        "backdropPath" to backdropPath, "releaseDate" to releaseDate, "voteAverage" to voteAverage,
        "voteCount" to voteCount, "originalTitle" to originalTitle, "originalLanguage" to originalLanguage,
        "updatedAtEpochMillis" to updatedAtEpochMillis,
    )

    private fun Cursor.string(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.double(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
}
