package com.example.filmio.feature.catalog.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import com.example.filmio.core.data.networking.safeCall
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.core.domain.Result
import com.example.filmio.core.domain.asEmptyResult
import com.example.filmio.core.domain.onSuccess
import com.example.filmio.feature.catalog.data.database.safeDatabaseUpdate
import com.example.filmio.feature.catalog.data.mapping.toDomain
import com.example.filmio.feature.catalog.data.mapping.toSnapshot
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.paging.AnchoredMoviePagingSource
import com.example.filmio.feature.catalog.data.paging.MovieSearchRemoteMediator
import com.example.filmio.feature.catalog.data.paging.MoviesRemoteMediator
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.dao.MovieDetailDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock

class OfflineFirstCatalogRepository(
    private val service: TmdbService,
    private val movieDao: MovieDao,
    private val movieDetailDao: MovieDetailDao,
    private val clock: Clock,
) : CatalogRepository {

    @OptIn(ExperimentalPagingApi::class)
    override fun getPagedMovies(): Flow<PagingData<Movie>> {
        var activeSource: PagingSource<Int, MovieEntity>? = null
        val mediator = MoviesRemoteMediator(service, movieDao, clock) { activeSource?.invalidate() }
        return Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false, prefetchDistance = 1),
            remoteMediator = mediator,
            pagingSourceFactory = { AnchoredMoviePagingSource(movieDao.pagingSource()).also { activeSource = it } },
        ).flow.map { data ->
            data.map { it.toDomain() }
        }
    }

    @OptIn(ExperimentalPagingApi::class)
    override fun searchMovies(query: String, fetchRemote: Boolean): Flow<PagingData<Movie>> {
        val trimmedQuery = query.trim()
        var activeSource: PagingSource<Int, MovieEntity>? = null
        val mediator = if (fetchRemote && trimmedQuery.isNotEmpty()) {
            MovieSearchRemoteMediator(trimmedQuery, service, movieDao, clock) { activeSource?.invalidate() }
        } else null
        return Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false, prefetchDistance = 1),
            remoteMediator = mediator,
            pagingSourceFactory = {
                AnchoredMoviePagingSource(movieDao.searchPagingSource(trimmedQuery, mediator?.remoteIds.orEmpty()))
                    .also { activeSource = it }
            },
        ).flow.map { data ->
            data.map { it.toDomain() }
        }
    }

    override suspend fun fetchMovieDetails(movieId: Long): EmptyResult<DataError> {
        require(movieId > 0) { "Movie ID must be positive" }

        return safeCall { service.getMovieDetails(movieId, "en-US") }
            .onSuccess { movieDetailDto ->
                val movieDetailSnapshot = movieDetailDto.toSnapshot(movieId, clock.millis())
                    ?: return Result.Error(DataError.Network.SERIALIZATION)
                currentCoroutineContext().ensureActive()

                return safeDatabaseUpdate {
                    movieDetailDao.upsertMovieDetail(
                        movie = movieDetailSnapshot.movie,
                        detail = requireNotNull(movieDetailSnapshot.detail),
                        genres = movieDetailSnapshot.genres,
                        productionCompanies = movieDetailSnapshot.productionCompanies
                    )
                }
            }
            .asEmptyResult()
    }

    override fun getMovieDetails(movieId: Long): Flow<Movie?> {
        require(movieId > 0) { "Movie ID must be positive" }

        return movieDetailDao.observeMovieDetail(movieId)
            .map { movieDetailSnapshot ->
                movieDetailSnapshot.toDomain()
            }
            .distinctUntilChanged()
    }
}
