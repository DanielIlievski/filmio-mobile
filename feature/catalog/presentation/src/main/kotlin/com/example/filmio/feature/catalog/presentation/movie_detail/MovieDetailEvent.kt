package com.example.filmio.feature.catalog.presentation.movie_detail

sealed interface MovieDetailEvent {
    data object NavigateBack : MovieDetailEvent
}
