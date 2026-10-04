package com.example.filmio.feature.catalog.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "movie_favorites",
    foreignKeys = [ForeignKey(
        entity = MovieEntity::class,
        parentColumns = ["id"],
        childColumns = ["movieId"],
        onDelete = ForeignKey.RESTRICT,
    )],
)
data class MovieFavoriteEntity(
    @PrimaryKey val movieId: Long,
    val addedAtEpochMillis: Long,
)
