package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.paging.CombinedLoadStates

sealed interface MovieListAction {
    data object OnRefreshClick : MovieListAction
    data object OnRetryClick : MovieListAction
    data class OnLoadStatesChanged(val loadStates: CombinedLoadStates, val hasItems: Boolean) : MovieListAction
}
