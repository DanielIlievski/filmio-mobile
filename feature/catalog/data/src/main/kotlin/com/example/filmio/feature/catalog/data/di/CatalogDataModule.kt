package com.example.filmio.feature.catalog.data.di

import com.example.filmio.feature.catalog.data.connectivity.AndroidConnectivityObserver
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.networking.createTmdbClient
import com.example.filmio.feature.catalog.data.networking.createTmdbMoshi
import com.example.filmio.feature.catalog.data.networking.createTmdbRetrofit
import com.example.filmio.feature.catalog.data.repository.OfflineFirstCatalogRepository
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module
import retrofit2.Retrofit
import java.time.Clock

val catalogDataModule = module {
    singleOf(::AndroidConnectivityObserver) bind ConnectivityObserver::class
    single { createTmdbClient(get()) }
    single { createTmdbMoshi() }
    single { createTmdbRetrofit(get(), get(), get()) }
    single { get<Retrofit>().create(TmdbService::class.java) }
    single<Clock> { Clock.systemUTC() }
    singleOf(::OfflineFirstCatalogRepository) bind CatalogRepository::class
}
