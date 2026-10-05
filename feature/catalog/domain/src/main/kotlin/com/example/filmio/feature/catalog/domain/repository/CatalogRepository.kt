package com.example.filmio.feature.catalog.domain.repository

import androidx.paging.PagingData
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    /** Cold local-only membership. Expected reads terminate with CatalogStorageException;
     * resubscribe to recover. Cancellation and unexpected defects propagate. */
    fun observeFavoriteMovieIds(): Flow<Set<Long>>

    /** Same local observation contract; movieId must be positive. */
    fun observeIsMovieFavorite(movieId: Long): Flow<Boolean>

    /** Local canonical summaries, newest saved first then ID; optional literal local query.
     * No mediator or HTTP. Removing membership preserves canonical content. */
    fun getPagedSavedMovies(query: String = ""): Flow<PagingData<Movie>>

    /** Positive ID, explicit idempotent desired state. Success follows local commit.
     * Saving an absent summary returns NOT_FOUND; expected storage failures are typed.
     * Cancellation and defects propagate; committed membership is never rolled back by cancellation. */
    suspend fun setMovieFavorite(movieId: Long, isFavorite: Boolean): EmptyResult<DataError.Local>

    fun getPagedMovies(): Flow<PagingData<Movie>>

    /** Literal local title matches plus this session's committed remote IDs, in title order.
     * Local-only reads never wait for HTTP. Blank input produces no matches or requests.
     * Discoveries also enrich Home; remote membership/continuation are memory-only.
     */
    fun searchMovies(query: String, fetchRemote: Boolean = true): Flow<PagingData<Movie>>

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
