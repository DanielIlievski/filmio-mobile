package com.example.filmio.feature.catalog.data.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import com.example.filmio.feature.catalog.data.mapping.toDomain
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.paging.AnchoredMoviePagingSource
import com.example.filmio.feature.catalog.data.paging.MoviesRemoteMediator
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

class OfflineFirstCatalogRepository(
    private val service: TmdbService,
    private val movieDao: MovieDao,
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
}
