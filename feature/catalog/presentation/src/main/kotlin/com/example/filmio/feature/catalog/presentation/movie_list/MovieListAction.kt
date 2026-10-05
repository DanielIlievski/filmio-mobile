package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.paging.CombinedLoadStates

sealed interface MovieListAction {
    data class OnCatalogViewChange(val view: CatalogView) : MovieListAction
    data class OnSetFavorite(val movieId: Long, val desired: Boolean) : MovieListAction
    data object OnClearQuery : MovieListAction
    data class OnMovieClick(val movieId: Long) : MovieListAction
    data class OnRefreshClick(val generation: Long = 0) : MovieListAction
    data class OnRetryClick(val generation: Long = 0) : MovieListAction
    data class OnLoadStatesChanged(
        val loadStates: CombinedLoadStates, val hasItems: Boolean, val generation: Long = 0,
    ) : MovieListAction
}
