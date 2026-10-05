package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.example.filmio.core.presentation.theme.FilmioTheme
import com.example.filmio.core.presentation.util.ObserveAsEvents
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.presentation.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel

@Composable
fun MovieListRoot(
    onNavigateToMovieDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieListViewModel = koinViewModel(),
) {
    val movieFlow by viewModel.movies.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val homeScroll = rememberLazyListState()
    val searchScroll = rememberSaveable(movieFlow, saver = LazyListState.Saver) { LazyListState() }
    var homeIndex by rememberSaveable { mutableIntStateOf(0) }
    var homeOffset by rememberSaveable { mutableIntStateOf(0) }
    var homeAnchorId by rememberSaveable { mutableLongStateOf(0) }
    var restoredHomeFlow by remember { mutableStateOf<Flow<PagingData<Movie>>?>(null) }
    // Key only the presenter. Recreating the TextField here loses focus during typing.
    val movies = key(movieFlow) { movieFlow.collectAsLazyPagingItems() }
    val currentGeneration by rememberUpdatedState(state.generation)
    ObserveAsEvents(viewModel.events, key1 = movies, key2 = viewModel) { event ->
        when (event) {
            is MovieListEvent.RefreshMovies -> if (event.generation == currentGeneration) movies.refresh()
            is MovieListEvent.RetryMovies -> if (event.generation == currentGeneration) movies.retry()
            is MovieListEvent.NavigateToMovieDetail -> onNavigateToMovieDetail(event.movieId)
        }
    }
    ObservePagingLoadStates(viewModel, movies, state.generation)
    if (!state.isSearchActive) {
        RestoreCatalogScroll(
            movies, homeScroll, homeIndex, homeOffset, homeAnchorId,
            needed = restoredHomeFlow !== movieFlow,
            onRestored = { restoredHomeFlow = movieFlow })
        LaunchedEffect(movies, homeScroll, restoredHomeFlow) {
            if (restoredHomeFlow !== movieFlow) return@LaunchedEffect
            snapshotFlow {
                val index = homeScroll.firstVisibleItemIndex
                val anchorId = if (index < movies.itemCount) movies.peek(index)?.id else null
                Triple(index, homeScroll.firstVisibleItemScrollOffset, anchorId ?: 0L)
            }.collect { (index, offset, anchorId) ->
                homeIndex = index
                homeOffset = offset
                homeAnchorId = anchorId
            }
        }
    }
    MovieListScreen(
        state, movies, viewModel::onAction, modifier,
        listState = if (state.isSearchActive) searchScroll else homeScroll
    )
}

/** Request cached items through Paging's accessor until the saved viewport can be restored.
 * This supplies presentation access hints only; Paging still owns loads, continuation, and retry.
 */
@Composable
private fun RestoreCatalogScroll(
    movies: LazyPagingItems<Movie>, scroll: LazyListState, index: Int, offset: Int, anchorId: Long,
    needed: Boolean, onRestored: () -> Unit,
) {
    LaunchedEffect(movies) {
        if (!needed) return@LaunchedEffect
        if (anchorId == 0L && index == 0 && offset == 0) {
            onRestored(); return@LaunchedEffect
        }
        snapshotFlow { movies.itemCount }.first { it > 0 }
        fun anchorIndex() = movies.itemSnapshotList.items.indexOfFirst { it.id == anchorId }
        while (if (anchorId > 0) anchorIndex() < 0 else movies.itemCount <= index) {
            val before = movies.itemCount
            movies[before - 1]
            snapshotFlow { movies.itemCount to movies.loadState }.first { (count, loads) ->
                count > before || loads.source.append is LoadState.Error ||
                        (loads.source.append.endOfPaginationReached && loads.source.append is LoadState.NotLoading)
            }
            if (movies.itemCount == before) break
        }
        val restoredIndex = anchorIndex().takeIf { it >= 0 } ?: index.coerceAtMost(movies.itemCount - 1)
        scroll.scrollToItem(restoredIndex, offset)
        onRestored()
    }
}

@Composable
private fun ObservePagingLoadStates(viewModel: MovieListViewModel, movies: LazyPagingItems<Movie>, generation: Long) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel, movies, generation) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { MovieListAction.OnLoadStatesChanged(movies.loadState, movies.itemCount > 0, generation) }
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
    listState: LazyListState = rememberLazyListState(),
) {
    val isScrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val largeText = LocalDensity.current.fontScale > 1.3f
    Scaffold(modifier = modifier.imePadding()) { insets ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            val posterWidth = if (maxWidth < 360.dp) 88.dp else 104.dp
            val rowGap = if (maxWidth < 360.dp) 12.dp else 16.dp
            val compactHeader = maxHeight < 480.dp || isScrolled || largeText
            Column(Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .padding(horizontal = 24.dp)) {
                CatalogHeader(
                    compact = compactHeader,
                    refreshEnabled = !state.isDebouncing && !state.isRefreshing && !state.isInitialLoading,
                    state = state, onAction = onAction,
                    onRefresh = { onAction(MovieListAction.OnRefreshClick(state.generation)) },
                )
                val hasContent = movies.itemCount > 0
                if (state.isDebouncing) LoadingFeedback(R.string.waiting_for_search)
                if (hasContent && state.isRefreshing) {
                    LoadingFeedback(R.string.refreshing_movies)
                }
                if (hasContent) {
                    state.refreshError?.let { error ->
                        ErrorFeedback(
                            R.string.could_not_refresh_movies, error,
                            enabled = !state.isRefreshing && !state.isAppending,
                            onRetry = { onAction(MovieListAction.OnRetryClick(state.generation)) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                when {
                    !hasContent && state.hasNoCachedMatches -> ContentFeedback(
                        title = R.string.no_cached_matches_title,
                        message = stringResource(R.string.no_cached_matches),
                        actionLabel = if (state.refreshError != null && !state.isDebouncing) R.string.retry else null,
                        enabled = !state.isRefreshing,
                        onClick = if (state.refreshError != null && !state.isDebouncing) {
                            { onAction(MovieListAction.OnRetryClick(state.generation)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )

                    !hasContent && state.refreshError != null -> ContentFeedback(
                        title = R.string.could_not_load_movies,
                        message = state.refreshError.asString(),
                        actionLabel = R.string.retry,
                        enabled = !state.isRefreshing && !state.isInitialLoading,
                        onClick = { onAction(MovieListAction.OnRetryClick(state.generation)) },
                        modifier = Modifier.weight(1f),
                    )

                    state.isEmpty && !hasContent -> ContentFeedback(
                        title = if (!state.isSearchActive) R.string.empty_movies_title else R.string.no_movies_found_title,
                        message = stringResource(if (!state.isSearchActive) R.string.empty_movies else R.string.no_movies_found),
                        actionLabel = R.string.refresh,
                        enabled = !state.isRefreshing,
                        onClick = { onAction(MovieListAction.OnRefreshClick(state.generation)) },
                        modifier = Modifier.weight(1f),
                    )

                    else -> {
                        val loadingLabel = stringResource(R.string.loading_movies)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(
                                    if (!hasContent && state.isInitialLoading) {
                                        Modifier.clearAndSetSemantics {
                                            contentDescription = loadingLabel
                                            liveRegion = LiveRegionMode.Polite
                                        }
                                    } else Modifier),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 24.dp),
                        ) {
                            if (!hasContent && state.isInitialLoading) {
                                items(3, key = { "skeleton_$it" }) {
                                    MovieRowSkeleton(posterWidth, rowGap)
                                }
                            }
                            items(count = movies.itemCount, key = movies.itemKey { it.id }) { index ->
                                movies[index]?.let { movie ->
                                    MovieRow(movie, posterWidth, rowGap, largeText) {
                                        onAction(MovieListAction.OnMovieClick(movie.id))
                                    }
                                }
                            }
                            if (state.isAppending) {
                                item(key = "append_loading") { LoadingFeedback(R.string.loading_more_movies) }
                            }
                            state.appendError?.let { error ->
                                item(key = "append_error") {
                                    ErrorFeedback(
                                        R.string.could_not_load_more_movies, error,
                                        enabled = !state.isRefreshing && !state.isAppending,
                                        onRetry = { onAction(MovieListAction.OnRetryClick(state.generation)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogHeader(
    compact: Boolean, refreshEnabled: Boolean, onRefresh: () -> Unit,
    state: MovieListState, onAction: (MovieListAction) -> Unit,
) {
    Column(Modifier
        .fillMaxWidth()
        .padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.filmio_wordmark), color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        )
        if (!compact) {
            Text(
                stringResource(R.string.discover), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.discover_tagline), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val searchDescription = stringResource(R.string.search_movies)
        TextField(
            state = state.queryTextState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics { contentDescription = searchDescription },
            placeholder = { Text(stringResource(R.string.search_movies)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { keyboard?.hide() },
            trailingIcon = if (state.queryTextState.text.isNotEmpty()) {
                {
                    TextButton(onClick = { onAction(MovieListAction.OnClearQuery) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.clear_search))
                    }
                }
            } else null,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (!state.isSearchActive) R.string.stored_movies else R.string.search_results),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.refresh))
            }
        }
    }
}

@Composable
private fun MovieRow(movie: Movie, posterWidth: Dp, gap: Dp, largeText: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.open_movie_details), onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        MoviePoster(movie.posterUrl, posterWidth)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                movie.title, style = MaterialTheme.typography.titleMedium,
                maxLines = if (largeText) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            )
            MovieRating(movie.voteAverage, movie.voteCount)
            Text(
                movie.overview?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeText) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MoviePoster(url: String?, width: Dp) {
    val painter = rememberAsyncImagePainter(model = url, contentScale = ContentScale.Crop)
    Box(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (painter.state !is AsyncImagePainter.State.Success) {
            if (LocalDensity.current.fontScale > 1.3f) {
                Icon(
                    painterResource(R.drawable.ic_movie_placeholder), contentDescription = null,
                    modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.no_poster), Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }
        Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun MovieRating(score: Double?, votes: Int?) {
    if (score == null || !score.isFinite() || score !in 0.0..10.0 || (score == 0.0 && votes == 0)) {
        Text(
            stringResource(R.string.not_rated), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val description = stringResource(R.string.movie_rating_description, score)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.movie_score, score), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MovieRowSkeleton(posterWidth: Dp, gap: Dp) {
    Row(Modifier
        .fillMaxWidth()
        .padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Box(
            Modifier
                .width(posterWidth)
                .aspectRatio(2f / 3f)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)),
        )
        Column(Modifier
            .weight(1f)
            .padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonLine(Modifier
                .fillMaxWidth(0.9f)
                .height(20.dp))
            SkeletonLine(Modifier
                .width(72.dp)
                .height(24.dp))
            SkeletonLine(Modifier
                .fillMaxWidth()
                .height(12.dp))
            SkeletonLine(Modifier
                .fillMaxWidth(0.9f)
                .height(12.dp))
            SkeletonLine(Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp))
        }
    }
}

@Composable
private fun SkeletonLine(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(4.dp)))
}

@Composable
private fun LoadingFeedback(label: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier
            .size(16.dp)
            .clearAndSetSemantics {}, strokeWidth = 2.dp)
        Text(
            stringResource(label), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorFeedback(title: Int, error: UiText, enabled: Boolean, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
            Text(error.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun ContentFeedback(
    title: Int, message: String, actionLabel: Int?, enabled: Boolean,
    onClick: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    // This state occupies the same scrollable region as rows, including at large text sizes.
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onClick != null) {
                    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(actionLabel))
                    }
                }
            }
        }
    }
}

private val previewMovies = listOf(
    Movie(
        1,
        "Interstellar",
        "A team of explorers travels beyond this galaxy to find a future among the stars.",
        voteAverage = 8.7,
        voteCount = 100
    ),
    Movie(
        2,
        "Dune: Part Two",
        "Paul Atreides joins the Fremen to avenge his family and face his destiny.",
        voteAverage = 8.2,
        voteCount = 100
    ),
    Movie(3, "The Grand Budapest Hotel", null),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListContentPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false),
            remember { MutableStateFlow(PagingData.from(previewMovies)) }.collectAsLazyPagingItems(),
            onAction = {})
    }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun MovieListLargeTextPreview() {
    MovieListContentPreview()
}

@Preview(showBackground = true, widthDp = 840, heightDp = 480)
@Composable
private fun MovieListWidePreview() {
    MovieListContentPreview()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListDarkPreview() {
    FilmioTheme(darkTheme = true) {
        MovieListScreen(
            MovieListState(isInitialLoading = false),
            remember { MutableStateFlow(PagingData.from(previewMovies)) }.collectAsLazyPagingItems(),
            onAction = {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListLoadingPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(),
            remember { MutableStateFlow(PagingData.empty<Movie>()) }.collectAsLazyPagingItems(),
            onAction = {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListAppendErrorPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, appendError = UiText.Resource(R.string.error_offline)),
            remember { MutableStateFlow(PagingData.from(previewMovies)) }.collectAsLazyPagingItems(), onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListInitialErrorPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, refreshError = UiText.Resource(R.string.error_offline)),
            remember { MutableStateFlow(PagingData.empty<Movie>()) }.collectAsLazyPagingItems(), onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieListEmptyPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(isInitialLoading = false, isEmpty = true),
            remember { MutableStateFlow(PagingData.empty<Movie>()) }.collectAsLazyPagingItems(), onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieSearchPreview() {
    FilmioTheme {
        MovieListScreen(
            MovieListState(
                queryTextState = TextFieldState("Inter"),
                isSearchActive = true,
                isInitialLoading = false,
                isDebouncing = true
            ),
            remember { MutableStateFlow(PagingData.from(previewMovies.take(1))) }.collectAsLazyPagingItems(), onAction = {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieSearchOfflinePreview() {
    FilmioTheme(darkTheme = true) {
        MovieListScreen(
            MovieListState(
                queryTextState = TextFieldState("Unknown"), isSearchActive = true, isInitialLoading = false,
                isOffline = true, hasNoCachedMatches = true, refreshError = UiText.Resource(R.string.error_offline)
            ),
            remember { MutableStateFlow(PagingData.empty<Movie>()) }.collectAsLazyPagingItems(), onAction = {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieSearchCachedFailurePreview() {
    FilmioTheme(darkTheme = true) {
        MovieListScreen(
            MovieListState(
                queryTextState = TextFieldState("Inter"), isSearchActive = true, isInitialLoading = false,
                refreshError = UiText.Resource(R.string.error_service)
            ),
            remember { MutableStateFlow(PagingData.from(previewMovies.take(1))) }.collectAsLazyPagingItems(), onAction = {})
    }
}
