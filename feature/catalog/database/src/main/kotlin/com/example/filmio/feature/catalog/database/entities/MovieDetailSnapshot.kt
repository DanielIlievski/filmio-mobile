package com.example.filmio.feature.catalog.database.entities

import androidx.room.Embedded
import androidx.room.Relation

/** Null snapshot means no movie; null detail means a summary exists but details were never fetched. */
data class MovieDetailSnapshot(
    @Embedded val movie: MovieEntity,
    @Relation(parentColumn = "id", entityColumn = "movieId")
    val detail: MovieDetailEntity?,
    @Relation(parentColumn = "id", entityColumn = "movieId")
    val genres: List<MovieGenreEntity>,
    @Relation(parentColumn = "id", entityColumn = "movieId")
    val productionCompanies: List<MovieProductionCompanyEntity>,
)
