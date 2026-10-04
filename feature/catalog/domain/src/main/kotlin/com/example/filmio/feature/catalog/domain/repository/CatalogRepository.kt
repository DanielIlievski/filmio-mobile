package com.example.filmio.feature.catalog.domain.repository

import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.EmptyResult
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {

    suspend fun fetchMovies(page: Int): EmptyResult<DataError.Network>

    fun getMovies(): Flow<List<Movie>>
}
