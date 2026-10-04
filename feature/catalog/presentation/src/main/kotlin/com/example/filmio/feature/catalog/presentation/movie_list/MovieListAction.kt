package com.example.filmio.feature.catalog.presentation.movie_list

sealed interface MovieListAction {
    data object OnRefreshClick : MovieListAction
    data object OnRetryClick : MovieListAction
}
