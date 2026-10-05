package com.example.filmio.feature.catalog.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import com.example.filmio.feature.catalog.data.networking.dto.MovieSearchResponseDto
import com.example.filmio.feature.catalog.data.networking.TmdbService
import com.example.filmio.feature.catalog.data.networking.dto.MovieDetailsDto
import com.example.filmio.feature.catalog.data.networking.dto.MovieDto
import com.example.filmio.feature.catalog.data.networking.dto.PopularMoviesResponseDto
import com.example.filmio.feature.catalog.database.dao.MovieDao
import com.example.filmio.feature.catalog.database.entities.MovieEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals

internal class FakeTmdbService : TmdbService {
    val searchPages = mutableListOf<Int>()
    val searchQueries = mutableListOf<String>()
    var searchResponse: suspend (Int) -> MovieSearchResponseDto = { page ->
        MovieSearchResponseDto(page, listOf(MovieDto(page.toLong(), "Movie %03d".format(page))))
    }
    override suspend fun searchMovies(query: String, page: Int, language: String, includeAdult: Boolean): MovieSearchResponseDto {
        assertEquals("en-US", language)
        assertEquals(false, includeAdult)
        searchQueries += query
        searchPages += page
        return searchResponse(page)
    }
    val detailIds = mutableListOf<Long>()
    var detailResponse: suspend (Long) -> MovieDetailsDto = { MovieDetailsDto(it, "Details") }
    override suspend fun getMovieDetails(movieId: Long, language: String): MovieDetailsDto {
        assertEquals("en-US", language)
        detailIds += movieId
        return detailResponse(movieId)
    }
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
    override fun pagingSource(): PagingSource<Int, MovieEntity> = source { rows.value }
    private fun source(selectedRows: () -> List<MovieEntity>): PagingSource<Int, MovieEntity> =
        object : PagingSource<Int, MovieEntity>() {
            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MovieEntity> {
                val rows = selectedRows()
                val start = when (params) {
                    is LoadParams.Prepend -> maxOf(0, params.key - params.loadSize)
                    is LoadParams.Refresh -> minOf(params.key ?: 0, maxOf(0, rows.size - params.loadSize))
                    is LoadParams.Append -> params.key
                }
                val end = if (params is LoadParams.Prepend) params.key else minOf(rows.size, start + params.loadSize)
                localLoads += start
                val data = rows.drop(start).take(end - start)
                return LoadResult.Page(
                    data = data,
                    prevKey = start.takeIf { it > 0 },
                    nextKey = end.takeIf { it < rows.size },
                    itemsBefore = start,
                    itemsAfter = maxOf(0, rows.size - end),
                )
            }
            override fun getRefreshKey(state: PagingState<Int, MovieEntity>): Int? =
                state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }
        }.also { sources += it }
    val searchSql = mutableListOf<String>()
    val searchBindings = mutableListOf<List<Any?>>()
    override fun searchPagingSource(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity> {
        val args = arrayOfNulls<Any>(query.argCount)
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { args[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { args[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { args[index - 1] = value }
            override fun bindString(index: Int, value: String) { args[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { args[index - 1] = value }
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        searchSql += query.sql
        searchBindings += args.toList()
        val text = args[0] as String
        val ids = Regex("""id IN \(([^)]*)\)""").find(query.sql)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().toLong() }.orEmpty()
        fun String.asciiFold() = map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
        return source {
            rows.value.filter { text.isNotEmpty() && (it.id in ids || it.title.asciiFold().contains(text.asciiFold()) ||
                it.originalTitle.orEmpty().asciiFold().contains(text.asciiFold())) }
        }
    }
}

internal fun cachedMovie(id: Long, title: String = "Movie %03d".format(id)) =
    MovieEntity(id, title, null, null, null, null, null, null, null, null, 0)
