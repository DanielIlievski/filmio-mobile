package com.example.filmio.feature.catalog.data.networking.dto

import com.squareup.moshi.Json

data class PopularMoviesResponseDto(
    val page: Int,
    val results: List<MovieDto?>,
    @param:Json(name = "total_pages") val totalPages: Int? = null,
)

data class MovieDto(
    val id: Long,
    val title: String,
    val overview: String? = null,
    @param:Json(name = "poster_path") val posterPath: String? = null,
    @param:Json(name = "backdrop_path") val backdropPath: String? = null,
    @param:Json(name = "release_date") val releaseDate: String? = null,
    @param:Json(name = "vote_average") val voteAverage: Double? = null,
    @param:Json(name = "vote_count") val voteCount: Int? = null,
    @param:Json(name = "original_title") val originalTitle: String? = null,
    @param:Json(name = "original_language") val originalLanguage: String? = null,
)
