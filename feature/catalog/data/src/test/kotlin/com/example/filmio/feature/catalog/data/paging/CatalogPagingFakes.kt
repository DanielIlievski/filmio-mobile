package com.example.filmio.feature.catalog.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals

internal class FakeTmdbService : TmdbService {
    val pages = mutableListOf<Int>()
    var respond: suspend (Int) -> PopularMoviesResponseDto = { page ->
        PopularMoviesResponseDto(page, listOf(MovieDto(page.toLong(), "Movie %03d".format(page))))
    }
    var inFlight = 0
    var maxInFlight = 0
    override suspend fun getPopularMovies(page: Int, language: String): PopularMoviesResponseDto {
        assertEquals("en-US", language)
        pages += page
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        return try { respond(page) } finally { inFlight-- }
    }
}

/** Tests coordination only; this fake does not execute Room SQL or prove transaction behavior. */
internal class FakeMovieDao : MovieDao() {
    val rows = MutableStateFlow<List<MovieEntity>>(emptyList())
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var writes = 0
    val sources = mutableListOf<PagingSource<Int, MovieEntity>>()
    val localLoads = mutableListOf<Int>()
    override fun observeMovies() = rows
    override suspend fun getMovie(movieId: Long) = rows.value.find { it.id == movieId }
    override suspend fun upsertMovies(movies: List<MovieEntity>) {
        beforeWrite()
        writes++
        rows.value = (rows.value + movies).associateBy { it.id }.values
            .sortedWith(compareBy<MovieEntity> { it.title.lowercase() }.thenBy { it.id })
        if (movies.isNotEmpty()) sources.toList().forEach { it.invalidate() }
        afterWrite()
    }
    override fun pagingSource(): PagingSource<Int, MovieEntity> =
        object : PagingSource<Int, MovieEntity>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MovieEntity> {
                val start = when (params) {
                    is LoadParams.Prepend -> maxOf(0, params.key - params.loadSize)
                    is LoadParams.Refresh -> minOf(params.key ?: 0, maxOf(0, rows.value.size - params.loadSize))
                    is LoadParams.Append -> params.key
                }
                val end = if (params is LoadParams.Prepend) params.key else minOf(rows.value.size, start + params.loadSize)
                localLoads += start
                val data = rows.value.drop(start).take(end - start)
                return LoadResult.Page(
                    data = data,
                    prevKey = start.takeIf { it > 0 },
                    nextKey = end.takeIf { it < rows.value.size },
                    itemsBefore = start,
                    itemsAfter = maxOf(0, rows.value.size - end),
                )
            }
            override fun getRefreshKey(state: PagingState<Int, MovieEntity>): Int? =
                state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }
        }.also { sources += it }
    override fun searchPagingSource(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity> = error("Unused")
}

internal fun cachedMovie(id: Long, title: String = "Movie %03d".format(id)) =
    MovieEntity(id, title, null, null, null, null, null, null, null, null, 0)
