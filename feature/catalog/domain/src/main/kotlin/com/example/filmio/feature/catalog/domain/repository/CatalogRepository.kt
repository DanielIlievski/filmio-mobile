package com.example.filmio.feature.catalog.domain.repository

import androidx.paging.PagingData
import com.example.filmio.feature.catalog.domain.model.Movie
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {

    fun getPagedMovies(): Flow<PagingData<Movie>>
}
