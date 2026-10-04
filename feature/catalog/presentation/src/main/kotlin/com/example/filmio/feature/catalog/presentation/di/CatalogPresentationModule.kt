package com.example.filmio.feature.catalog.presentation.di

import com.example.filmio.feature.catalog.presentation.movie_list.MovieListViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val catalogPresentationModule = module {
    viewModelOf(::MovieListViewModel)
}
