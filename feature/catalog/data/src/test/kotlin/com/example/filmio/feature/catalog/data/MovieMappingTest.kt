package com.example.filmio.feature.catalog.data

import com.example.filmio.feature.catalog.data.mapping.toDomain
import com.example.filmio.feature.catalog.data.mapping.toEntity
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import com.example.filmio.feature.catalog.domain.model.Movie
import org.junit.Assert.*
import org.junit.Test

class MovieMappingTest {
    @Test fun mapsEverySelectedColumnWithoutNormalizingSourceValuesThenProjectsDomainSummary() {
        val dto = MovieDto(7, " Title ", " Overview ", " /poster ", " /backdrop ", "2024-02-29", 0.0, 0, " Original ", " en ")
        val expected = MovieEntity(7, " Title ", " Overview ", " /poster ", " /backdrop ", "2024-02-29", 0.0, 0, " Original ", " en ", 123)
        assertEquals(expected, dto.toEntity(123))
        assertEquals(Movie(7, " Title ", " Overview ", "2024-02-29", 0.0, 0, " Original ", " en ", posterUrl = "https://image.tmdb.org/t/p/w500/poster",
            backdropUrl = "https://image.tmdb.org/t/p/w780/backdrop"), expected.toDomain())
    }
    @Test fun missingOrInvalidBackdropDoesNotFallBackToPortraitArtwork() {
        for (path in listOf(null, "", " ", "/", "backdrop.jpg")) {
            val movie = MovieDto(7, "Movie", posterPath = "/poster.jpg", backdropPath = path)
                .toEntity(123).toDomain()
            assertNull(movie.backdropUrl)
            assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", movie.posterUrl)
        }
    }
}
