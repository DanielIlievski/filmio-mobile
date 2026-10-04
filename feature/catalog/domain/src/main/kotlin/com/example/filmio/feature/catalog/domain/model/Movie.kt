package com.example.filmio.feature.catalog.domain.model

data class Movie(
    val id: Long,
    val title: String,
    val overview: String?,
    val releaseDate: String? = null,
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val details: MovieDetails? = null,
    val posterUrl: String? = null,
)
