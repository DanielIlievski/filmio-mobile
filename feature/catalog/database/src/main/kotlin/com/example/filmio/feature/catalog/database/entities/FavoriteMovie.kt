package com.example.filmio.feature.catalog.database.entities

import androidx.room.Embedded

data class FavoriteMovie(
    @Embedded val movie: MovieEntity,
    val addedAtEpochMillis: Long,
)
