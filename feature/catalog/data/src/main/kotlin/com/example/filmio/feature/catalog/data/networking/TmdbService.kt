package com.example.filmio.feature.catalog.data.networking

import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import com.example.filmio.feature.catalog.data.networking.dto.MovieDetailsDto
import retrofit2.http.Path
import retrofit2.http.GET
import retrofit2.http.Query

interface TmdbService {

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int,
        @Query("language") language: String,
    ): PopularMoviesResponseDto

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Long,
        @Query("language") language: String,
    ): MovieDetailsDto
}
