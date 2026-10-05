package com.example.filmio.feature.catalog.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.filmio.feature.catalog.database.util.literalTitleSearchPattern
import com.example.filmio.feature.catalog.database.entities.FavoriteMovie
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MovieFavoriteDao {
    @Query(
        "SELECT movies.*, movie_favorites.addedAtEpochMillis FROM movies " +
            "INNER JOIN movie_favorites ON movie_favorites.movieId = movies.id " +
            "ORDER BY movie_favorites.addedAtEpochMillis DESC, movies.id ASC",
    )
    abstract fun pagingSource(): PagingSource<Int, FavoriteMovie>

    @Query("SELECT movieId FROM movie_favorites ORDER BY movieId ASC")
    abstract fun observeFavoriteMovieIds(): Flow<List<Long>>

    @Query("SELECT movies.*, movie_favorites.addedAtEpochMillis FROM movies " +
        "INNER JOIN movie_favorites ON movie_favorites.movieId = movies.id " +
        "WHERE movies.title GLOB :pattern OR movies.originalTitle GLOB :pattern " +
        "ORDER BY movie_favorites.addedAtEpochMillis DESC, movies.id ASC")
    protected abstract fun searchPagingSource(pattern: String): PagingSource<Int, FavoriteMovie>

    fun pagingSource(query: String): PagingSource<Int, FavoriteMovie> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) pagingSource() else searchPagingSource(literalTitleSearchPattern(trimmed))
    }

    @Query("SELECT EXISTS(SELECT 1 FROM movies WHERE id = :movieId)")
    protected abstract suspend fun movieExists(movieId: Long): Boolean

    /** Validation and desired-state mutation share the same transaction. */
    @Transaction
    open suspend fun setFavorite(movieId: Long, isFavorite: Boolean, addedAtEpochMillis: Long): Boolean {
        require(movieId > 0)
        if (isFavorite) {
            if (!movieExists(movieId)) return false
            addFavorite(MovieFavoriteEntity(movieId, addedAtEpochMillis))
        } else removeFavorite(movieId)
        return true
    }

    @Query("SELECT EXISTS(SELECT 1 FROM movie_favorites WHERE movieId = :movieId)")
    abstract fun observeIsFavorite(movieId: Long): Flow<Boolean>

    /** Repeated adds preserve the original time; the canonical movie must already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun addFavorite(favorite: MovieFavoriteEntity)

    /** Removing an absent favorite is harmless and never removes cached content. */
    @Query("DELETE FROM movie_favorites WHERE movieId = :movieId")
    protected abstract suspend fun removeFavorite(movieId: Long)
}
