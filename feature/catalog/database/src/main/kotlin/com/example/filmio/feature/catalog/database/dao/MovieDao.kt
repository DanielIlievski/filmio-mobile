package com.example.filmio.feature.catalog.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.util.literalTitleSearchPattern

@Dao
abstract class MovieDao {
    @Query("SELECT * FROM movies ORDER BY title COLLATE NOCASE ASC, id ASC")
    abstract fun pagingSource(): PagingSource<Int, MovieEntity>

    @Query("SELECT * FROM movies WHERE id = :movieId")
    abstract suspend fun getMovie(movieId: Long): MovieEntity?

    /** Room commits the entire page atomically. Empty pages keep the existing catalog intact. */
    @Upsert
    abstract suspend fun upsertMovies(movies: List<MovieEntity>)

    /**
     * Literal local matches plus committed active remote IDs, in deterministic local order.
     * Create a new source whenever the query or remote IDs change; the source captures these inputs.
     * Blank input produces no results, including when obsolete remote IDs were supplied.
     */
    fun searchPagingSource(
        query: String,
        activeRemoteIds: List<Long> = emptyList(),
    ): PagingSource<Int, MovieEntity> {
        val trimmedQuery = query.trim()
        val pattern = literalTitleSearchPattern(trimmedQuery)
        // Only typed Long values enter the SQL. Bind user text, including quotes and SQL punctuation.
        // Numeric literals avoid the older Android SQLite 999-bind ceiling for large search sessions.
        val remoteClause = if (trimmedQuery.isEmpty() || activeRemoteIds.isEmpty()) {
            "0"
        } else {
            "id IN (${activeRemoteIds.distinct().joinToString()})"
        }
        return searchPagingSource(
            SimpleSQLiteQuery(
                "SELECT * FROM movies WHERE ? <> '' AND (" +
                    "title GLOB ? OR coalesce(originalTitle, '') GLOB ? OR $remoteClause) " +
                    "ORDER BY title COLLATE NOCASE ASC, id ASC",
                arrayOf(trimmedQuery, pattern, pattern),
            ),
        )
    }

    @RawQuery(observedEntities = [MovieEntity::class])
    protected abstract fun searchPagingSource(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity>
}
