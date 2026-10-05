package com.example.filmio.feature.catalog.data.networking.dto

import com.squareup.moshi.Json

data class MovieSearchResponseDto(
    val page: Int,
    val results: List<MovieDto?>,
    @param:Json(name = "total_pages") val totalPages: Int? = null,
)
