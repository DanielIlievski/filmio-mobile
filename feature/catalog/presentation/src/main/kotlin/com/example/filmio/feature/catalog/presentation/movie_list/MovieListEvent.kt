package com.example.filmio.feature.catalog.presentation.movie_list

sealed interface MovieListEvent {
    data object RefreshMovies : MovieListEvent
    data object RetryMovies : MovieListEvent
}
