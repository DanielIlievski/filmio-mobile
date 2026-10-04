package com.example.filmio.feature.catalog.data.mapping

import com.example.filmio.feature.catalog.data.networking.dto.MovieDetailsDto
import com.example.filmio.feature.catalog.database.entities.MovieDetailEntity
import com.example.filmio.feature.catalog.database.entities.MovieDetailSnapshot
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.entities.MovieGenreEntity
import com.example.filmio.feature.catalog.database.entities.MovieProductionCompanyEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.model.MovieDetails
import com.example.filmio.feature.catalog.domain.model.MovieGenre
import com.example.filmio.feature.catalog.domain.model.MovieProductionCompany

internal fun MovieDetailsDto.toSnapshot(movieId: Long, fetchedAtEpochMillis: Long): MovieDetailSnapshot? {
    if (id != movieId) return null
    val genres = genres.orEmpty().filterNotNull().lastOccurrences { it.id }
    val companies = productionCompanies.orEmpty().filterNotNull().lastOccurrences { it.id }
    return MovieDetailSnapshot(
        movie = this.toMovieEntity(fetchedAtEpochMillis),
        detail = this.toMovieDetailEntity(fetchedAtEpochMillis),
        genres = genres.mapIndexed { position, genre -> MovieGenreEntity(id, genre.id, genre.name, position) },
        productionCompanies = companies.mapIndexed { position, company ->
            MovieProductionCompanyEntity(id, company.id, company.name, position)
        },
    )
}

private fun MovieDetailsDto.toMovieEntity(fetchedAtEpochMillis: Long): MovieEntity {
    return MovieEntity(
        id = id,
        title = title,
        overview = overview,
        posterPath = posterPath,
        backdropPath = backdropPath,
        releaseDate = releaseDate,
        voteAverage = voteAverage,
        voteCount = voteCount,
        originalTitle = originalTitle,
        originalLanguage = originalLanguage,
        updatedAtEpochMillis = fetchedAtEpochMillis
    )
}

private fun MovieDetailsDto.toMovieDetailEntity(fetchedAtEpochMillis: Long): MovieDetailEntity {
    return MovieDetailEntity(
        movieId = id,
        runtimeMinutes = runtime,
        tagline = tagline,
        status = status,
        budget = budget,
        revenue = revenue,
        homepage = homepage,
        imdbId = imdbId,
        collectionId = collection?.id,
        collectionName = collection?.name,
        fetchedAtEpochMillis = fetchedAtEpochMillis
    )
}

// Reversing before distinctBy keeps the last occurrence; reversing back preserves its order.
private inline fun <T> List<T>.lastOccurrences(id: (T) -> Long): List<T> =
    asReversed().distinctBy(id).asReversed()

internal fun MovieDetailSnapshot?.toDomain(): Movie? {
    if (this == null) return null
    return movie.toDomain().copy(details = detail?.let { detail ->
        MovieDetails(
            runtimeMinutes = detail.runtimeMinutes,
            tagline = detail.tagline,
            status = detail.status,
            budget = detail.budget,
            revenue = detail.revenue,
            homepage = detail.homepage,
            imdbId = detail.imdbId,
            collectionId = detail.collectionId,
            collectionName = detail.collectionName,
            genres = genres.map { MovieGenre(it.genreId, it.name) },
            productionCompanies = productionCompanies.map { MovieProductionCompany(it.companyId, it.name) },
        )
    })
}
