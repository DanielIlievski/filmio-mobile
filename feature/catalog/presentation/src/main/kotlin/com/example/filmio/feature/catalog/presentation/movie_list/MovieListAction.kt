package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.paging.CombinedLoadStates

sealed interface MovieListAction {
    data object OnClearQuery : MovieListAction
    data class OnMovieClick(val movieId: Long) : MovieListAction
    data class OnRefreshClick(val generation: Long = 0) : MovieListAction
    data class OnRetryClick(val generation: Long = 0) : MovieListAction
    data class OnLoadStatesChanged(
        val loadStates: CombinedLoadStates, val hasItems: Boolean, val generation: Long = 0,
    ) : MovieListAction
}
