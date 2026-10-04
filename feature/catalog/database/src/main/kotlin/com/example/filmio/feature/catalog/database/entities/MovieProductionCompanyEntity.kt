package com.example.filmio.feature.catalog.database.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "movie_production_companies",
    primaryKeys = ["movieId", "companyId"],
    foreignKeys = [ForeignKey(
        entity = MovieDetailEntity::class,
        parentColumns = ["movieId"],
        childColumns = ["movieId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["movieId", "position"], unique = true)],
)
data class MovieProductionCompanyEntity(
    val movieId: Long,
    val companyId: Long,
    val name: String,
    val position: Int,
)
