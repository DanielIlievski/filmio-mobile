package com.example.filmio.feature.catalog.data.networking

import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

interface TmdbService {

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int,
        @Query("language") language: String,
    ): PopularMoviesResponseDto
}
