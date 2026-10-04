package com.example.filmio.feature.catalog.data.networking.dto

import com.squareup.moshi.Json

data class MovieDetailsDto(
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
    val runtime: Int? = null,
    val tagline: String? = null,
    val status: String? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    val homepage: String? = null,
    @param:Json(name = "imdb_id") val imdbId: String? = null,
    @param:Json(name = "belongs_to_collection") val collection: MovieCollectionDto? = null,
    val genres: List<MovieGenreDto?>? = null,
    @param:Json(name = "production_companies") val productionCompanies: List<MovieProductionCompanyDto?>? = null,
)

data class MovieGenreDto(val id: Long, val name: String)
data class MovieProductionCompanyDto(val id: Long, val name: String)
data class MovieCollectionDto(val id: Long, val name: String)
