package com.example.filmio.feature.catalog.presentation.movie_list

import com.example.filmio.core.presentation.util.UiText

data class MovieListState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val isEmpty: Boolean = false,
    val refreshError: UiText? = null,
    val appendError: UiText? = null,
)
