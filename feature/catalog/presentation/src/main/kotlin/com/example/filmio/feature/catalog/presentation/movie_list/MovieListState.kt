package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.runtime.Stable
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie

@Stable
data class MovieListState(
    val movies: List<Movie> = emptyList(),
    val error: UiText? = null,
    val isLoading: Boolean = false,
)
