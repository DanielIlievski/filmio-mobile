package com.example.filmio

import android.app.Application
import com.example.filmio.di.appModule
import com.example.filmio.feature.catalog.data.di.catalogDataModule
import com.example.filmio.feature.catalog.presentation.di.catalogPresentationModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class FilmioApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FilmioApplication)
            modules(appModule, catalogDataModule, catalogPresentationModule)
        }
    }
}
