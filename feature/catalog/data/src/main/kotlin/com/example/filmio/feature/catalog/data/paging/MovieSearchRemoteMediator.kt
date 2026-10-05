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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock

@OptIn(ExperimentalPagingApi::class)
internal class MovieSearchRemoteMediator(
    private val query: String,
    private val service: TmdbService,
    private val movieDao: MovieDao,
    private val clock: Clock,
    private val invalidateMembership: () -> Unit = {},
) : RemoteMediator<Int, MovieEntity>() {
    @Volatile var remoteIds: List<Long> = emptyList()
        private set
    private var nextPage: Int? = 1
    private var refreshRevision = 0L

    override suspend fun initialize() = InitializeAction.LAUNCH_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MovieEntity>): MediatorResult {
        val page = when (loadType) {
            LoadType.PREPEND -> return MediatorResult.Success(true)
            LoadType.REFRESH -> { refreshRevision++; 1 }
            LoadType.APPEND -> nextPage ?: return MediatorResult.Success(true)
        }
        val revision = refreshRevision
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (revision != refreshRevision) throw CancellationException("Search refresh superseded this load")
        }

        val result = safeCall { service.searchMovies(query, page, "en-US", false) }
        checkCurrent()
        val response = when (result) {
            is Result.Error -> return MediatorResult.Error(CatalogPagingException(result.error))
            is Result.Success -> result.data
        }
        if (response.page != page) return MediatorResult.Error(CatalogPagingException(DataError.Network.SERIALIZATION))

        val timestamp = clock.millis()
        val movies = response.results.mapNotNull { it?.toEntity(timestamp) }
            .associateBy { it.id }.values.toList()
        checkCurrent()
        val commit = safeDatabaseUpdate { movieDao.upsertMovies(movies) }
        // A canceled write can already have committed safe canonical rows, but cannot publish membership.
        checkCurrent()
        commit.onFailure { return MediatorResult.Error(CatalogPagingException(it)) }

        val ended = response.results.isEmpty() || response.totalPages?.let { it <= page } == true || page == 500
        remoteIds = ((if (loadType == LoadType.REFRESH) emptyList() else remoteIds) + movies.map { it.id }).distinct()
        nextPage = if (ended) null else page + 1
        // Also needed for existing remote-only IDs, replacement membership, and null-only pages.
        // The new Room query must capture membership AFTER publication, not merely after table invalidation.
        invalidateMembership()
        return MediatorResult.Success(ended)
    }
}
