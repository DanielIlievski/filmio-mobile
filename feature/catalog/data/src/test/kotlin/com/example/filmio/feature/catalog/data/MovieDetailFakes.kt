package com.example.filmio.feature.catalog.data

import com.example.filmio.feature.catalog.data.paging.FakeMovieDao
import com.example.filmio.feature.catalog.database.dao.MovieDetailDao
import com.example.filmio.feature.catalog.database.entities.*
import kotlinx.coroutines.flow.*

/** Coordination fake only: no Room SQL, transactions, durable reopen, or actual invalidation evidence. */
internal class FakeMovieDetailDao(val summaries: FakeMovieDao = FakeMovieDao()) : MovieDetailDao() {
    val details = MutableStateFlow<Map<Long, MovieDetailSnapshot>>(emptyMap())
    val favorites = mutableMapOf<Long, MovieFavoriteEntity>()
    val readFailure = MutableStateFlow<Throwable?>(null)
    var beforeCommit: suspend () -> Unit = {}
    var afterCommit: suspend () -> Unit = {}
    var reads = 0
    var subscriptions = 0
    var writes = 0

    fun snapshot(id: Long): MovieDetailSnapshot? = summaries.rows.value.find { it.id == id }?.let { movie ->
        details.value[id]?.copy(movie = movie) ?: MovieDetailSnapshot(movie, null, emptyList(), emptyList())
    }
    suspend fun seed(snapshot: MovieDetailSnapshot) {
        details.value = details.value + (snapshot.movie.id to snapshot)
        summaries.upsertMovies(listOf(snapshot.movie))
    }
    override suspend fun getSnapshot(movieId: Long): MovieDetailSnapshot? {
        reads++
        readFailure.value?.let { throw it }
        return snapshot(movieId)
    }
    override fun observeSnapshot(movieId: Long): Flow<MovieDetailSnapshot?> = flow {
        subscriptions++
        emitAll(combine(summaries.rows, details, readFailure) { _, _, failure ->
            failure?.let { throw it }
            snapshot(movieId)
        })
    }
    override suspend fun upsertMovieDetail(movie: MovieEntity, detail: MovieDetailEntity,
        genres: List<MovieGenreEntity>, productionCompanies: List<MovieProductionCompanyEntity>) {
        beforeCommit()
        writes++
        details.value = details.value + (movie.id to MovieDetailSnapshot(movie, detail, genres, productionCompanies))
        summaries.upsertMovies(listOf(movie))
        afterCommit()
    }
    // Unused protected primitives: the fake simulates one complete commit instead of Room execution.
    override suspend fun upsertMovie(movie: MovieEntity) = error("Unused")
    override suspend fun upsertDetail(detail: MovieDetailEntity) = error("Unused")
    override suspend fun deleteGenres(movieId: Long) = error("Unused")
    override suspend fun deleteProductionCompanies(movieId: Long) = error("Unused")
    override suspend fun insertGenres(genres: List<MovieGenreEntity>) = error("Unused")
    override suspend fun insertProductionCompanies(companies: List<MovieProductionCompanyEntity>) = error("Unused")
}
