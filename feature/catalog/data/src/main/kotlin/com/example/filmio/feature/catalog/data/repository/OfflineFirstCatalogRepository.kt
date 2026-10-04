package com.example.filmio.feature.catalog.data.repository

import com.example.filmio.core.data.networking.safeCall
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.core.domain.Result
import com.example.filmio.core.domain.asEmptyResult
import com.example.filmio.core.domain.onSuccess
import com.example.filmio.feature.catalog.data.mapping.toDomain
import com.example.filmio.feature.catalog.data.mapping.toEntity
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock

class OfflineFirstCatalogRepository(
    private val service: TmdbService,
    private val movieDao: MovieDao,
    private val clock: Clock,
) : CatalogRepository {

    override suspend fun fetchMovies(page: Int): EmptyResult<DataError.Network> {
        val requestedPage = page.coerceAtLeast(1)
        return safeCall { service.getPopularMovies(requestedPage, LANGUAGE) }
            .onSuccess { moviesDto ->
                if (moviesDto.page != requestedPage) return Result.Error(DataError.Network.SERIALIZATION)
                val updatedAtEpochMillis = clock.millis()
                val movieEntities = moviesDto.results.mapNotNull { movie ->
                    movie?.toEntity(updatedAtEpochMillis)
                }
                currentCoroutineContext().ensureActive()
                movieDao.upsertMovies(movieEntities)
            }
            .asEmptyResult()
    }

    override fun getMovies(): Flow<List<Movie>> =
        movieDao.observeMovies()
            .map { movies ->
                movies.map { it.toDomain() }
            }
            .distinctUntilChanged()
}

private const val LANGUAGE = "en-US"
