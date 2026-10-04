package com.example.filmio.feature.catalog.data.networking

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

fun createTmdbClient(config: TmdbConfig): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(30, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val request = try {
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${config.readAccessToken}")
                .build()
        } catch (_: IllegalArgumentException) {
            // OkHttp's validation exception can contain the complete credential value.
            throw IOException("Invalid TMDB authorization header")
        }
        chain.proceed(request)
    }
    .build()

fun createTmdbMoshi(): Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

fun createTmdbRetrofit(client: OkHttpClient, moshi: Moshi, config: TmdbConfig): Retrofit =
    Retrofit.Builder()
        .baseUrl(config.baseUrl)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
