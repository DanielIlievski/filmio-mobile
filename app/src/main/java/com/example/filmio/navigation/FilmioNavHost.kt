package com.example.filmio.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.filmio.feature.catalog.presentation.movie_detail.MovieDetailRoot
import com.example.filmio.feature.catalog.presentation.movie_list.MovieListRoot

@Composable
fun FilmioNavHost(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(FilmioRoute.MovieList)
    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<FilmioRoute.MovieList> {
                MovieListRoot(
                    onNavigateToMovieDetail = { movieId ->
                        val destination = FilmioRoute.MovieDetail(movieId)
                        if (movieId > 0 && backStack.lastOrNull() == FilmioRoute.MovieList) backStack.add(destination)
                    }
                )
            }
            entry<FilmioRoute.MovieDetail> { route ->
                MovieDetailRoot(
                    movieId = route.movieId,
                    onNavigateBack = {
                        if (backStack.lastOrNull() == route && backStack.size > 1) backStack.removeLastOrNull()
                    }
                )
            }
        },
    )
}
