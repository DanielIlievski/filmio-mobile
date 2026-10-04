package com.example.filmio.feature.catalog.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "movie_genres",
    primaryKeys = ["movieId", "genreId"],
    foreignKeys = [ForeignKey(
        entity = MovieDetailEntity::class,
        parentColumns = ["movieId"],
        childColumns = ["movieId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["movieId", "position"], unique = true)],
)
data class MovieGenreEntity(
    val movieId: Long,
    val genreId: Long,
    val name: String,
    val position: Int,
)
