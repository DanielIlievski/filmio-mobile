package com.example.filmio.feature.catalog.database

import android.content.Context
import androidx.room.Room

fun createCatalogDatabase(context: Context): CatalogDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        CatalogDatabase::class.java,
        "filmio-catalog.db",
    ).build()
