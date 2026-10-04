package com.example.filmio.feature.catalog.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.filmio.feature.catalog.database.entities.MovieDetailEntity
import com.example.filmio.feature.catalog.database.entities.MovieDetailSnapshot
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.entities.MovieGenreEntity
import com.example.filmio.feature.catalog.database.entities.MovieProductionCompanyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
abstract class MovieDetailDao {
    suspend fun getMovieDetail(movieId: Long): MovieDetailSnapshot? =
        getSnapshot(movieId)?.withOrderedChildren()

    fun observeMovieDetail(movieId: Long): Flow<MovieDetailSnapshot?> =
        observeSnapshot(movieId).map { it?.withOrderedChildren() }

    /** Replace a complete fetched snapshot, including nullable fields and empty owned lists. */
    @Transaction
    open suspend fun upsertMovieDetail(
        movie: MovieEntity,
        detail: MovieDetailEntity,
        genres: List<MovieGenreEntity>,
        productionCompanies: List<MovieProductionCompanyEntity>,
    ) {
        require(detail.movieId == movie.id) { "Detail must belong to the supplied movie" }
        require(genres.all { it.movieId == movie.id }) { "Genres must belong to the supplied movie" }
        require(productionCompanies.all { it.movieId == movie.id }) {
            "Production companies must belong to the supplied movie"
        }
        upsertMovie(movie)
        upsertDetail(detail)
        // Delete before inserting so existing unique positions can be reordered safely.
        deleteGenres(movie.id)
        deleteProductionCompanies(movie.id)
        insertGenres(genres)
        insertProductionCompanies(productionCompanies)
    }

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId")
    protected abstract suspend fun getSnapshot(movieId: Long): MovieDetailSnapshot?

    @Transaction
    @Query("SELECT * FROM movies WHERE id = :movieId")
    protected abstract fun observeSnapshot(movieId: Long): Flow<MovieDetailSnapshot?>

    @Upsert
    protected abstract suspend fun upsertMovie(movie: MovieEntity)

    @Upsert
    protected abstract suspend fun upsertDetail(detail: MovieDetailEntity)

    @Query("DELETE FROM movie_genres WHERE movieId = :movieId")
    protected abstract suspend fun deleteGenres(movieId: Long)

    @Query("DELETE FROM movie_production_companies WHERE movieId = :movieId")
    protected abstract suspend fun deleteProductionCompanies(movieId: Long)

    @Insert
    protected abstract suspend fun insertGenres(genres: List<MovieGenreEntity>)

    @Insert
    protected abstract suspend fun insertProductionCompanies(companies: List<MovieProductionCompanyEntity>)

    // @Relation does not promise SQL ordering; the database API owns ordered local projections.
    private fun MovieDetailSnapshot.withOrderedChildren() = copy(
        genres = genres.sortedBy { it.position },
        productionCompanies = productionCompanies.sortedBy { it.position },
    )
}
