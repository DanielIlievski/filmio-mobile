package com.example.filmio.feature.catalog.presentation.movie_list

sealed interface MovieListEvent {
    data class NavigateToMovieDetail(val movieId: Long) : MovieListEvent
    data object RefreshMovies : MovieListEvent
    data object RetryMovies : MovieListEvent
}
