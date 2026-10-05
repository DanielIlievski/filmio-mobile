package com.example.filmio.feature.catalog.data.mapping

import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.model.Movie

fun MovieDto.toEntity(updatedAtEpochMillis: Long): MovieEntity {
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
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun MovieEntity.toDomain(): Movie {
    return Movie(
        id = id,
        title = title,
        overview = overview,
        releaseDate = releaseDate,
        voteAverage = voteAverage,
        voteCount = voteCount,
        originalTitle = originalTitle,
        originalLanguage = originalLanguage,
        posterUrl = posterPath?.trim()?.takeIf { it.startsWith("/") && it.length > 1 }
            ?.let { "https://image.tmdb.org/t/p/w500$it" },
        backdropUrl = backdropPath?.trim()?.takeIf { it.startsWith("/") && it.length > 1 }
            ?.let { "https://image.tmdb.org/t/p/w780$it" },
    )
}
