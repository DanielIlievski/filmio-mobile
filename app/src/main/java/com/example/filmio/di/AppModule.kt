package com.example.filmio.di

import com.example.filmio.BuildConfig
import com.example.filmio.feature.catalog.data.networking.TmdbConfig
import com.example.filmio.feature.catalog.database.CatalogDatabase
import com.example.filmio.feature.catalog.database.createCatalogDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {
    single { createCatalogDatabase(androidContext()) }
    single { get<CatalogDatabase>().movieDao() }
    single { TmdbConfig(BuildConfig.TMDB_READ_ACCESS_TOKEN) }
}
