package com.example.filmio.feature.catalog.presentation.movie_detail

sealed interface MovieDetailAction {
    data class OnSetFavorite(val desired: Boolean) : MovieDetailAction
    data object OnBackClick : MovieDetailAction
}
