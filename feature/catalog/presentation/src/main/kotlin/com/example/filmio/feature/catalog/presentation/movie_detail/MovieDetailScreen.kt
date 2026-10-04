package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.filmio.core.presentation.util.ObserveAsEvents
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.model.MovieDetails
import com.example.filmio.feature.catalog.domain.model.MovieGenre
import com.example.filmio.feature.catalog.domain.model.MovieProductionCompany
import com.example.filmio.feature.catalog.presentation.R
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MovieDetailRoot(
    movieId: Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieDetailViewModel = koinViewModel(parameters = { parametersOf(movieId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.events, key1 = viewModel, key2 = onNavigateBack) { event ->
        when (event) { MovieDetailEvent.NavigateBack -> onNavigateBack() }
    }
    MovieDetailScreen(state, viewModel::onAction, modifier)
}

@Composable
fun MovieDetailScreen(
    state: MovieDetailState,
    onAction: (MovieDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.movie_details), style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { onAction(MovieDetailAction.OnBackClick) }) { Text(stringResource(R.string.back)) }
                if (state.isConnected == false) {
                    Text(stringResource(if (state.movie == null) R.string.offline_movie_unavailable else R.string.offline_movie_cached))
                }
                if (state.isLoading) {
                    Text(stringResource(if (state.movie == null) R.string.loading_movie_details else R.string.refreshing_movie_details))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { error ->
                    Text(error.asString(), color = MaterialTheme.colorScheme.error)
                }
                state.movie?.let { movie ->
                    Text(movie.title, style = MaterialTheme.typography.headlineMedium)
                    Text(movie.overview?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_description),
                        style = MaterialTheme.typography.bodyLarge)
                    DetailField(R.string.release_date, movie.releaseDate)
                    DetailField(R.string.rating, movie.voteAverage?.toString())
                    DetailField(R.string.votes, movie.voteCount?.toString())
                    DetailField(R.string.original_title, movie.originalTitle)
                    DetailField(R.string.original_language, movie.originalLanguage)
                    val details = movie.details
                    if (details == null) {
                        Text(stringResource(R.string.movie_summary_only), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        DetailField(R.string.runtime_minutes, details.runtimeMinutes?.toString())
                        DetailField(R.string.tagline, details.tagline)
                        DetailField(R.string.movie_status, details.status)
                        DetailField(R.string.budget, details.budget?.toString())
                        DetailField(R.string.revenue, details.revenue?.toString())
                        DetailField(R.string.homepage, details.homepage)
                        DetailField(R.string.imdb_id, details.imdbId)
                        DetailField(R.string.collection_name, details.collectionName)
                        DetailField(R.string.collection_id, details.collectionId?.toString())
                        DetailField(R.string.genres, details.genres.joinToString { it.name })
                        DetailField(R.string.production_companies, details.productionCompanies.joinToString { it.name })
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailField(@StringRes label: Int, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        Text(value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_movie_value),
            style = MaterialTheme.typography.bodyMedium)
    }
}

private val previewMovie = Movie(42, "Arrival", "A linguist tries to understand visitors whose language changes the way she sees time.",
    "2016-11-11", 7.9, 18_000, "Arrival", "en", MovieDetails(runtimeMinutes = 116,
        tagline = "Why are they here?", status = "Released", budget = 47_000_000, revenue = 203_388_186,
        genres = listOf(MovieGenre(878, "Science Fiction")),
        productionCompanies = listOf(MovieProductionCompany(21, "21 Laps Entertainment"))))

@Preview(showBackground = true)
@Composable
private fun MovieDetailContentPreview() {
    MaterialTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie, isLoading = false), {}) }
}

@Preview(showBackground = true)
@Composable
private fun MovieDetailSummaryPreview() {
    MaterialTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie.copy(details = null),
        isLoading = true), {}) }
}

@Preview(showBackground = true)
@Composable
private fun MovieDetailCachedErrorPreview() {
    MaterialTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie, isLoading = false,
        isConnected = false, error = UiText.Resource(R.string.error_offline)), {}) }
}

@Preview(showBackground = true)
@Composable
private fun MovieDetailUncachedErrorPreview() {
    MaterialTheme { MovieDetailScreen(MovieDetailState(isLoading = false, isConnected = false,
        error = UiText.Resource(R.string.error_offline)), {}) }
}
