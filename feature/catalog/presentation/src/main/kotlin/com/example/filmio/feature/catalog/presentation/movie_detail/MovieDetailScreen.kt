package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.annotation.StringRes
import com.example.filmio.feature.catalog.presentation.components.FavoriteControl
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.example.filmio.core.presentation.theme.FilmioTheme
import com.example.filmio.core.presentation.util.ObserveAsEvents
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.model.MovieDetails
import com.example.filmio.feature.catalog.domain.model.MovieGenre
import com.example.filmio.feature.catalog.domain.model.MovieProductionCompany
import com.example.filmio.feature.catalog.presentation.R
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

@Composable
fun MovieDetailRoot(
    movieId: Long,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MovieDetailViewModel = koinViewModel(parameters = { parametersOf(movieId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.events, key1 = viewModel, key2 = onNavigateBack) { event ->
        when (event) {
            MovieDetailEvent.NavigateBack -> onNavigateBack()
        }
    }
    MovieDetailScreen(state, viewModel::onAction, modifier)
}

@Composable
fun MovieDetailScreen(
    state: MovieDetailState,
    onAction: (MovieDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    Scaffold(modifier = modifier) { insets ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            val expanded = maxWidth >= 840.dp && !largeText
            val stackMetadata = largeText || maxWidth < 360.dp
            val onBack = { onAction(MovieDetailAction.OnBackClick) }
            Column(
                Modifier
                    .widthIn(max = 1120.dp)
                    .fillMaxSize()
            ) {
                if (expanded) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.back))
                        }
                        Text(stringResource(R.string.filmio_wordmark), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        val movie = state.movie
                        when {
                            movie == null -> {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .padding(top = if (expanded) 24.dp else 88.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    SectionHeading(if (state.isLoading) R.string.movie_details else R.string.movie_details_unavailable)
                                    DetailFeedback(state)
                                    if (!state.isLoading) {
                                        Text(
                                            stringResource(R.string.movie_details_unavailable_message),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            expanded -> Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(40.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    MovieBackdrop(movie.backdropUrl, Modifier.clip(RoundedCornerShape(16.dp)))
                                    MovieTitle(movie.title, expanded = true, largeText = false)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                    DetailFeedback(state)
                                    MovieInformation(movie, expanded = true, stackMetadata = false, state, onAction)
                                }
                            }

                            else -> {
                                MovieBackdrop(movie.backdropUrl)
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    DetailFeedback(state)
                                    MovieTitle(movie.title, expanded = false, largeText = largeText)
                                    MovieInformation(movie, expanded = false, stackMetadata = stackMetadata, state, onAction)
                                }
                            }
                        }
                    }
                    if (!expanded) {
                        Surface(
                            modifier = Modifier.padding(16.dp), shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(R.string.back),
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
private fun MovieBackdrop(url: String?, modifier: Modifier = Modifier) {
    val painter = rememberAsyncImagePainter(model = url, contentScale = ContentScale.Crop)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (painter.state !is AsyncImagePainter.State.Success) {
            Text(
                stringResource(R.string.no_backdrop), Modifier.padding(24.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun MovieTitle(title: String, expanded: Boolean, largeText: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.movie_content_type),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            title,
            style = when {
                largeText -> MaterialTheme.typography.headlineSmall
                expanded -> MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp)
                else -> MaterialTheme.typography.headlineLarge.copy(lineHeight = 37.sp)
            },
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun MovieInformation(
    movie: Movie,
    expanded: Boolean,
    stackMetadata: Boolean,
    state: MovieDetailState,
    onAction: (MovieDetailAction) -> Unit
) {
    val details = movie.details
    val runtime = runtimeText(details?.runtimeMinutes)
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(if (expanded) 24.dp else 16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val score = movie.voteAverage?.takeIf { it.isFinite() && it in 0.0..10.0 && !(it == 0.0 && movie.voteCount == 0) }
                    if (score != null) {
                        Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    val rating = score?.let { stringResource(R.string.movie_score, it) } ?: stringResource(R.string.not_rated)
                    Text(
                        text = rating,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            FavoriteControl(
                title = movie.title,
                favorite = state.isFavorite,
                enabled = movie.id > 0,
                onSetFavorite = { onAction(MovieDetailAction.OnSetFavorite(it)) })
        }
        movie.voteCount?.takeIf { it >= 0 }?.let { votes ->
            Text(
                stringResource(R.string.movie_vote_count, NumberFormat.getIntegerInstance(locale).format(votes)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (details != null) {
            if (details.genres.isEmpty()) {
                Text(
                    stringResource(R.string.no_genres), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    details.genres.forEach { genre ->
                        Text(
                            genre.name, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        SectionHeading(R.string.movie_overview, expanded)
        Text(
            movie.overview?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_overview),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = if (expanded) 18.sp else 16.sp,
                lineHeight = if (expanded) 27.sp else 23.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val releaseDate = movie.releaseDate?.takeIf { it.isNotBlank() }?.let { date ->
            try {
                LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            } catch (_: DateTimeParseException) {
                null
            }
        }
        if (stackMetadata) {
            DetailField(R.string.release_date, releaseDate)
            DetailField(R.string.runtime, runtime)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DetailField(R.string.release_date, releaseDate, Modifier.weight(1f))
                DetailField(R.string.runtime, runtime, Modifier.weight(1f))
            }
        }
        if (details == null) {
            Text(
                stringResource(R.string.movie_summary_only), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailFeedback(state: MovieDetailState) {
    if (!state.isLoading && state.isConnected != false && state.error == null) return
    Column(
        Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.isConnected == false) {
            Text(
                stringResource(if (state.movie == null) R.string.offline_movie_unavailable else R.string.offline_movie_cached),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.isLoading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier
                        .size(16.dp)
                        .clearAndSetSemantics {}, strokeWidth = 2.dp
                )
                Text(
                    stringResource(if (state.movie == null) R.string.loading_movie_details else R.string.refreshing_movie_details),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SectionHeading(@StringRes label: Int, expanded: Boolean = false) {
    Text(
        stringResource(label), style = if (expanded) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
}

@Composable
private fun DetailField(@StringRes label: Int, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_movie_value),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun runtimeText(minutes: Int?): String? = when {
    minutes == null || minutes <= 0 -> null
    minutes < 60 -> stringResource(R.string.movie_runtime_minutes, minutes)
    else -> stringResource(R.string.movie_runtime_hours_minutes, minutes / 60, minutes % 60)
}

private val previewMovie = Movie(
    id = 42,
    title = "Interstellar",
    overview = "A team of explorers travels beyond this galaxy to discover whether mankind has a future among the stars. Cooper, a former pilot, must leave his family behind to lead a mission through a wormhole in search of a new home for humanity.",
    releaseDate = "2014-11-05", voteAverage = 8.7, voteCount = 18_000,
    details = MovieDetails(
        runtimeMinutes = 169, tagline = "Mankind was born on Earth. It was never meant to die here.",
        status = "Released", budget = 165_000_000, revenue = 701_729_206,
        genres = listOf(MovieGenre(12, "Adventure"), MovieGenre(18, "Drama"), MovieGenre(878, "Sci-Fi")),
        productionCompanies = listOf(MovieProductionCompany(923, "Legendary Pictures"))
    ),
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailContentPreview() {
    FilmioTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie, isFavorite = true, isLoading = false), {}) }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailDarkPreview() {
    FilmioTheme(darkTheme = true) { MovieDetailScreen(MovieDetailState(movie = previewMovie, isFavorite = true, isLoading = false), {}) }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 820)
@Composable
private fun MovieDetailWidePreview() {
    MovieDetailContentPreview()
}

@Preview(showBackground = true, widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun MovieDetailLargeTextPreview() {
    MovieDetailContentPreview()
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailSummaryPreview() {
    FilmioTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie.copy(details = null), isLoading = true), {}) }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailCachedErrorPreview() {
    FilmioTheme {
        MovieDetailScreen(
            MovieDetailState(
                movie = previewMovie, isFavorite = true, isLoading = false,
                isConnected = false, error = UiText.Resource(R.string.error_offline)
            ), {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailUncachedErrorPreview() {
    FilmioTheme {
        MovieDetailScreen(
            MovieDetailState(
                isLoading = false, isConnected = false,
                error = UiText.Resource(R.string.error_offline)
            ), {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailLoadingPreview() {
    FilmioTheme { MovieDetailScreen(MovieDetailState(isLoading = true), {}) }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun MovieDetailFavoriteUnsavedPreview() {
    FilmioTheme { MovieDetailScreen(MovieDetailState(movie = previewMovie, isFavorite = false), {}) }
}

@Preview(showBackground = true, widthDp = 1024, heightDp = 820)
@Composable
private fun MovieDetailFavoriteSavedPreview() {
    FilmioTheme(darkTheme = true) {
        MovieDetailScreen(
            MovieDetailState(
                movie = previewMovie, isFavorite = true,
            ), {})
    }
}

@Preview(showBackground = true, widthDp = 320, heightDp = 844, fontScale = 2f)
@Composable
private fun MovieDetailFavoriteLargeTextPreview() {
    FilmioTheme {
        MovieDetailScreen(
            MovieDetailState(
                movie = previewMovie,
            ), {})
    }
}
