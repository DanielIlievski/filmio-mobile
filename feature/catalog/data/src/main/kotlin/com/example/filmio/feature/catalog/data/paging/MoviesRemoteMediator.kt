package com.example.filmio.feature.catalog.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import com.example.filmio.core.data.networking.safeCall
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.example.filmio.core.domain.onFailure
import com.example.filmio.feature.catalog.data.database.safeDatabaseUpdate
import com.example.filmio.feature.catalog.data.mapping.toEntity
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.repository.CatalogPagingException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock

@OptIn(ExperimentalPagingApi::class)
internal class MoviesRemoteMediator(
    private val service: TmdbService,
    private val movieDao: MovieDao,
    private val clock: Clock,
    private val invalidateEmptyBatch: () -> Unit = {},
) : RemoteMediator<Int, MovieEntity>() {
    private var nextPage: Int? = FIRST_PAGE

    override suspend fun initialize() = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MovieEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.PREPEND -> return MediatorResult.Success(endOfPaginationReached = true)
            LoadType.REFRESH -> FIRST_PAGE
            LoadType.APPEND -> nextPage ?: return MediatorResult.Success(endOfPaginationReached = true)
        }

        val result = safeCall { service.getPopularMovies(page, LANGUAGE) }

        currentCoroutineContext().ensureActive()

        val response = when (result) {
            is Result.Error -> return MediatorResult.Error(CatalogPagingException(result.error))
            is Result.Success -> result.data
        }

        if (response.page != page)
            return MediatorResult.Error(CatalogPagingException(DataError.Network.SERIALIZATION))

        val timestamp = clock.millis()
        val movies = response.results
            .mapNotNull { it?.toEntity(timestamp) }
            .associateBy { it.id }
            .values
            .toList()

        val commit = safeDatabaseUpdate { movieDao.upsertMovies(movies) }

        // A canceled write may already have committed. Retrying the same page is idempotent.
        currentCoroutineContext().ensureActive()

        commit.onFailure { return MediatorResult.Error(CatalogPagingException(it)) }

        val endOfPaginationReached = response.results.isEmpty() ||
                response.totalPages?.let { it <= page } == true ||
                page == 500

        nextPage = if (endOfPaginationReached) null else page + 1

        // An empty mapped batch doesn't invalidate Room, so explicitly trigger the next boundary load.
        if (movies.isEmpty() && !endOfPaginationReached) {
            invalidateEmptyBatch()
        }

        return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
    }

    private companion object {
        private const val FIRST_PAGE = 1
        private const val LANGUAGE = "en-US"
    }
}
