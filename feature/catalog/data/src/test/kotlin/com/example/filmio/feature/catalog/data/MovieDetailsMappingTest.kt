package com.example.filmio.feature.catalog.data

import com.example.filmio.feature.catalog.data.mapping.toDomain
import com.example.filmio.feature.catalog.data.mapping.toSnapshot
import com.example.filmio.feature.catalog.data.networking.dto.*
import com.example.filmio.feature.catalog.database.entities.*
import com.example.filmio.feature.catalog.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class MovieDetailsMappingTest {
    @Test fun mismatchedIdentityCannotBecomeALocalSnapshot() {
        assertNull(MovieDetailsDto(9, "Wrong").toSnapshot(7, 123))
    }

    @Test fun mapsAllSelectedValuesWithOneTimestampWithoutNormalizingNullsStringsOrZeros() {
        val dto = MovieDetailsDto(7, " Title ", " Overview ", " /poster ", " /backdrop ", "2024-02-29",
            0.0, 0, " Original ", " en ", 0, " Tagline ", " Released ", 0, 5_000_000_000,
            " https://example.com ", " tt7 ", MovieCollectionDto(8, " Collection "),
            listOf(MovieGenreDto(1, " Genre ")), listOf(MovieProductionCompanyDto(2, " Company ")))
        val snapshot = dto.toSnapshot(7, 123)!!
        assertEquals(MovieEntity(7, " Title ", " Overview ", " /poster ", " /backdrop ", "2024-02-29",
            0.0, 0, " Original ", " en ", 123), snapshot.movie)
        assertEquals(MovieDetailEntity(7, 0, " Tagline ", " Released ", 0, 5_000_000_000,
            " https://example.com ", " tt7 ", 8, " Collection ", 123), snapshot.detail)
        assertEquals(listOf(MovieGenreEntity(7, 1, " Genre ", 0)), snapshot.genres)
        assertEquals(listOf(MovieProductionCompanyEntity(7, 2, " Company ", 0)), snapshot.productionCompanies)
        assertEquals(Movie(7, " Title ", " Overview ", "2024-02-29", 0.0, 0, " Original ", " en ",
            MovieDetails(0, " Tagline ", " Released ", 0, 5_000_000_000, " https://example.com ",
                " tt7 ", 8, " Collection ", listOf(MovieGenre(1, " Genre ")),
                listOf(MovieProductionCompany(2, " Company "))),
            posterUrl = "https://image.tmdb.org/t/p/w500/poster"), snapshot.toDomain())
        assertNull(snapshot.movie.toDomain().details)
    }

    @Test fun nullEntriesAndRepeatedIdsRetainLastOccurrenceOrderWithContiguousPositions() {
        val dto = MovieDetailsDto(7, "Movie", genres = listOf(MovieGenreDto(1, "Old"), null,
            MovieGenreDto(2, "Second"), MovieGenreDto(1, "Last")), productionCompanies = listOf(
            MovieProductionCompanyDto(1, "Old"), MovieProductionCompanyDto(2, "Second"), null,
            MovieProductionCompanyDto(1, "Last")))
        val snapshot = dto.toSnapshot(7, 0)!!
        assertEquals(listOf(MovieGenreEntity(7, 2, "Second", 0), MovieGenreEntity(7, 1, "Last", 1)), snapshot.genres)
        assertEquals(listOf(MovieProductionCompanyEntity(7, 2, "Second", 0),
            MovieProductionCompanyEntity(7, 1, "Last", 1)), snapshot.productionCompanies)
    }

    @Test fun absentSummaryAndFetchedUnknownAreDifferentAvailabilityStates() {
        assertNull((null as MovieDetailSnapshot?).toDomain())
        val fetched = MovieDetailsDto(7, "Movie").toSnapshot(7, 123)!!
        assertEquals(MovieDetails(), fetched.toDomain()!!.details)
        assertEquals(Movie(7, "Movie", null), fetched.copy(detail = null).toDomain())
        assertNull(fetched.detail!!.budget)
        assertNull(fetched.movie.voteAverage)
        assertTrue(fetched.genres.isEmpty())
        assertTrue(fetched.productionCompanies.isEmpty())
    }
}
