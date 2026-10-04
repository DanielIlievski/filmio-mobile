package com.example.filmio.feature.catalog.database

import androidx.room.Database
import androidx.room.RoomDatabase

/** Initial persistence foundation. Production queries and atomic writes are added at integration. */
@Database(
    entities = [
        MovieEntity::class,
        MovieDetailEntity::class,
        MovieGenreEntity::class,
        MovieProductionCompanyEntity::class,
        MovieFavoriteEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CatalogDatabase : RoomDatabase()
