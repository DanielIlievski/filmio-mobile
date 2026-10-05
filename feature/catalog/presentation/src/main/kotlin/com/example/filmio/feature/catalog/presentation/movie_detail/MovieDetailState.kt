package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.compose.runtime.Stable
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie

@Stable
data class MovieDetailState(
    val movie: Movie? = null,
    val isFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val isConnected: Boolean? = null,
    val error: UiText? = null,
)
