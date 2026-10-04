package com.example.filmio.feature.catalog.domain.model

data class Movie(
    val id: Long,
    val title: String,
    val overview: String?,
)