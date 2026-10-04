package com.example.filmio.feature.catalog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.dao.MovieDetailDao
import com.example.filmio.feature.catalog.database.dao.MovieFavoriteDao
import com.example.filmio.feature.catalog.database.entities.MovieDetailEntity
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.database.entities.MovieFavoriteEntity
import com.example.filmio.feature.catalog.database.entities.MovieGenreEntity
import com.example.filmio.feature.catalog.database.entities.MovieProductionCompanyEntity

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
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao
    abstract fun movieDetailDao(): MovieDetailDao
    abstract fun movieFavoriteDao(): MovieFavoriteDao
}
