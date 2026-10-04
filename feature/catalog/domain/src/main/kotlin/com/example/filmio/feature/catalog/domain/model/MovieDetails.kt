package com.example.filmio.feature.catalog.domain.model

/** Presence means extended details were fetched, even when all metadata is unknown. */
data class MovieDetails(
    val runtimeMinutes: Int? = null,
    val tagline: String? = null,
    val status: String? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    val homepage: String? = null,
    val imdbId: String? = null,
    val collectionId: Long? = null,
    val collectionName: String? = null,
    val genres: List<MovieGenre> = emptyList(),
    val productionCompanies: List<MovieProductionCompany> = emptyList(),
)
