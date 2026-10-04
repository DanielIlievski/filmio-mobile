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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.filmio.core.presentation.util.ObserveAsEvents
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel

@Composable
fun MovieListRoot(
    onNavigateToMovieDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieListViewModel = koinViewModel(),
) {
    val movies = viewModel.movies.collectAsLazyPagingItems()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events, key1 = movies, key2 = viewModel) { event ->
        when (event) {
            MovieListEvent.RefreshMovies -> movies.refresh()
            MovieListEvent.RetryMovies -> movies.retry()
            is MovieListEvent.NavigateToMovieDetail -> onNavigateToMovieDetail(event.movieId)
        }
    }
    ObservePagingLoadStates(viewModel, movies)

    MovieListScreen(state, movies, viewModel::onAction, modifier)
}

@Composable
private fun ObservePagingLoadStates(viewModel: MovieListViewModel, movies: LazyPagingItems<Movie>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel, movies) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { MovieListAction.OnLoadStatesChanged(movies.loadState, movies.itemCount > 0) }
                .collect(viewModel::onAction)
        }
    }
}

@Composable
fun MovieListScreen(
    state: MovieListState,
    movies: LazyPagingItems<Movie>,
    onAction: (MovieListAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Scaffold(modifier = modifier) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 840.dp).fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.stored_movies), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    Button(
                        onClick = { onAction(MovieListAction.OnRefreshClick) },
                        enabled = !state.isRefreshing && !state.isInitialLoading,
                    ) { Text(stringResource(R.string.refresh)) }
                }
                if (state.isInitialLoading || state.isRefreshing) {
                    LoadingFeedback(if (movies.itemCount == 0) R.string.loading_movies else R.string.refreshing_movies)
                }
                state.refreshError?.let { error ->
                    ErrorFeedback(error, enabled = !state.isRefreshing && !state.isAppending, onAction = onAction)
                }
                if (state.isEmpty) Text(stringResource(R.string.empty_movies))
                LazyColumn(
                    Modifier.weight(1f), state = listState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
                        movies[index]?.let { movie -> MovieRow(movie) { onAction(MovieListAction.OnMovieClick(movie.id)) } }
                    }
                    if (state.isAppending) item(key = "append_loading") { LoadingFeedback(R.string.loading_more_movies) }
                    state.appendError?.let { error ->
                        item(key = "append_error") {
                            ErrorFeedback(error, enabled = !state.isRefreshing && !state.isAppending, onAction = onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieRow(movie: Movie, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun LoadingFeedback(label: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorFeedback(error: UiText, enabled: Boolean, onAction: (MovieListAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { onAction(MovieListAction.OnRetryClick) }, enabled = enabled) { Text(stringResource(R.string.retry)) }
    }
}

private val previewMovies = listOf(
    Movie(2, "Arrival", "A linguist tries to understand visitors whose language changes the way she sees time."),
    Movie(1, "The Grand Budapest Hotel", "A concierge and his young apprentice become unlikely friends during a remarkable adventure."),
    Movie(3, "Zodiac", null),
)

@Preview(showBackground = true)
@Composable
private fun MovieListContentPreview() {
    MaterialTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, isRefreshing = true),
            flowOf(PagingData.from(previewMovies)).collectAsLazyPagingItems(), onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MovieListAppendErrorPreview() {
    MaterialTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, appendError = UiText.Resource(R.string.error_offline)),
            flowOf(PagingData.from(previewMovies)).collectAsLazyPagingItems(), onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MovieListEmptyPreview() {
    MaterialTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, isEmpty = true),
            flowOf(PagingData.empty<Movie>()).collectAsLazyPagingItems(), onAction = {},
        )
    }
}
