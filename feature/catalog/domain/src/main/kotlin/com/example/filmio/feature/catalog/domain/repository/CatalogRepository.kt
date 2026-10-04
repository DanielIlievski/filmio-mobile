package com.example.filmio.feature.catalog.domain.repository

import androidx.paging.PagingData
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getPagedMovies(): Flow<PagingData<Movie>>

    /**
     * Refreshes and commits details for a positive ID before returning completion only.
     * Every invocation fetches the latest remote data. Failure preserves cached content.
     */
    suspend fun fetchMovieDetails(movieId: Long): EmptyResult<DataError>

    /**
     * Cold, local-only observation for a positive ID. Null movie means absent locally;
     * null details means summary-only. Present details may still have unknown metadata.
     * Expected local read errors throw [CatalogStorageException] and terminate observation;
     * a new subscription can recover. Cancellation and unexpected defects propagate.
     */
    fun getMovieDetails(movieId: Long): Flow<Movie?>
}
