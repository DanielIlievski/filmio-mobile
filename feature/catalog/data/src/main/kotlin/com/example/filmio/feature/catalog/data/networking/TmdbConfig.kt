package com.example.filmio.feature.catalog.data.networking

class TmdbConfig(
    val readAccessToken: String,
    val baseUrl: String = UrlConstants.BASE_URL_HTTP,
) {
    override fun toString(): String = "TmdbConfig(readAccessToken=[REDACTED])"
}
