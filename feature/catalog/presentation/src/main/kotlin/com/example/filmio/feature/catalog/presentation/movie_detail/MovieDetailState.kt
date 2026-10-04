package com.example.filmio.feature.catalog.presentation.movie_detail

import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie

data class MovieDetailState(
    val movie: Movie? = null,
    val isLoading: Boolean = false,
    val isConnected: Boolean? = null,
    val error: UiText? = null,
)
