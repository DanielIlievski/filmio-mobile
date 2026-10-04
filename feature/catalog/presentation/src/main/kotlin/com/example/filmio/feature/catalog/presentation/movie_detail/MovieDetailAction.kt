package com.example.filmio.feature.catalog.presentation.movie_detail

sealed interface MovieDetailAction {
    data object OnBackClick : MovieDetailAction
}
