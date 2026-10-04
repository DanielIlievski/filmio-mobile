package com.example.filmio.feature.catalog.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Canonical movie summary shared by popular, search, and detail snapshots. */
@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val releaseDate: String?,
    val voteAverage: Double?,
    val voteCount: Int?,
    val originalTitle: String?,
    val originalLanguage: String?,
    val updatedAtEpochMillis: Long,
)
