package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.foundation.text.input.TextFieldState
import com.example.filmio.core.presentation.util.UiText

data class MovieListState(
    val queryTextState: TextFieldState = TextFieldState(),
    // Selects search UI/scroll even after remote loading completes or fails.
    val isSearchActive: Boolean = false,
    val generation: Long = 0,
    // Only true while waiting to enable the current query's remote Pager.
    val isDebouncing: Boolean = false,
    val isOffline: Boolean = false,
    val hasNoCachedMatches: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAppending: Boolean = false,
    val isEmpty: Boolean = false,
    val refreshError: UiText? = null,
    val appendError: UiText? = null,
)
