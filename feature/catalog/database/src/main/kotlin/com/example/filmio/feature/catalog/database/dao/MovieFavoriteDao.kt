package com.example.filmio.feature.catalog.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.filmio.feature.catalog.database.entities.FavoriteMovie
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieFavoriteDao {
    @Query(
        "SELECT movies.*, movie_favorites.addedAtEpochMillis FROM movies " +
            "INNER JOIN movie_favorites ON movie_favorites.movieId = movies.id " +
            "ORDER BY movie_favorites.addedAtEpochMillis DESC, movies.id ASC",
    )
    fun pagingSource(): PagingSource<Int, FavoriteMovie>

    @Query("SELECT EXISTS(SELECT 1 FROM movie_favorites WHERE movieId = :movieId)")
    fun observeIsFavorite(movieId: Long): Flow<Boolean>

    /** Repeated adds preserve the original time; the canonical movie must already exist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(favorite: MovieFavoriteEntity)

    /** Removing an absent favorite is harmless and never removes cached content. */
    @Query("DELETE FROM movie_favorites WHERE movieId = :movieId")
    suspend fun removeFavorite(movieId: Long)
}
