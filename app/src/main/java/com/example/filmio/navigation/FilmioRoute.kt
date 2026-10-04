package com.example.filmio.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface FilmioRoute : NavKey {
    @Serializable data object MovieList : FilmioRoute
    @Serializable data class MovieDetail(val movieId: Long) : FilmioRoute
}
