package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.presentation.R
import org.koin.androidx.compose.koinViewModel

@Composable
fun MovieListRoot(
    modifier: Modifier = Modifier,
    viewModel: MovieListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MovieListScreen(state, viewModel::onAction, modifier)
}

@Composable
fun MovieListScreen(
    state: MovieListState,
    onAction: (MovieListAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.stored_movies), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    Button(onClick = { onAction(MovieListAction.OnRefreshClick) }, enabled = !state.isLoading) {
                        Text(stringResource(R.string.refresh))
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.isLoading) item {
                        Text(stringResource(R.string.loading_movies))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    state.error?.let { error -> item {
                        ErrorFeedback(error, enabled = !state.isLoading) {
                            onAction(MovieListAction.OnRetryClick)
                        }
                    } }
                    if (state.movies.isEmpty() && !state.isLoading && state.error == null) item {
                        Text(stringResource(R.string.empty_movies))
                    }
                    items(state.movies, key = { it.id }) { movie ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(movie.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    movie.overview ?: stringResource(R.string.no_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorFeedback(error: UiText, enabled: Boolean, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.retry)) }
    }
}

@Preview(showBackground = true)
@Composable
private fun MovieListScreenPreview() {
    MaterialTheme {
        MovieListScreen(
            MovieListState(
                movies = listOf(
                    Movie(1, "The Grand Budapest Hotel", "A concierge and his young apprentice become unlikely friends during a remarkable adventure."),
                    Movie(2, "Arrival", "A linguist tries to understand visitors whose language changes the way she sees time."),
                ),
                isLoading = true,
            ),
            onAction = {},
        )
    }
}
