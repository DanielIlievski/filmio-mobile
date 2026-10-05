package com.example.filmio.feature.catalog.presentation.movie_list

sealed interface MovieListEvent {
    data class NavigateToMovieDetail(val movieId: Long) : MovieListEvent
    data class RefreshMovies(val generation: Long = 0) : MovieListEvent
    data class RetryMovies(val generation: Long = 0) : MovieListEvent
}
