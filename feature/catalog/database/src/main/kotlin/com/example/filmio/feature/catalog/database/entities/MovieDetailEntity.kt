package com.example.filmio.feature.catalog.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Row presence means details were fetched; nullable metadata may still be unknown. */
@Entity(
    tableName = "movie_details",
    foreignKeys = [ForeignKey(
        entity = MovieEntity::class,
        parentColumns = ["id"],
        childColumns = ["movieId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MovieDetailEntity(
    @PrimaryKey val movieId: Long,
    val runtimeMinutes: Int?,
    val tagline: String?,
    val status: String?,
    val budget: Long?,
    val revenue: Long?,
    val homepage: String?,
    val imdbId: String?,
    val collectionId: Long?,
    val collectionName: String?,
    val fetchedAtEpochMillis: Long,
)
