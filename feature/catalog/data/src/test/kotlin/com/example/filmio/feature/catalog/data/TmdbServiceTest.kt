package com.example.filmio.feature.catalog.data

import com.example.filmio.core.data.networking.safeCall
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.example.filmio.feature.catalog.data.networking.*
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TmdbServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var config: TmdbConfig
    private lateinit var service: TmdbService

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        config = TmdbConfig("fake-test-token", server.url("/3/").toString())
        service = createTmdbRetrofit(createTmdbClient(config), createTmdbMoshi(), config)
            .create(TmdbService::class.java)
    }
    @After fun close() { server.shutdown() }

    @Test fun searchRequestEncodesQueryOnceAndUsesExistingAuthorization() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"page":3,"total_pages":4,"results":[{"id":42,"title":"Love & War"},null]}"""))
        val response = service.searchMovies("Love & War", 3, "en-US", false)
        assertEquals(42L, response.results.first()!!.id)
        assertEquals(4, response.totalPages)
        assertNull(response.results.last())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val url = request.requestUrl!!
        assertEquals("/3/search/movie", url.encodedPath)
        assertEquals(setOf("query", "page", "language", "include_adult"), url.queryParameterNames)
        assertEquals("Love & War", url.queryParameter("query"))
        assertEquals("3", url.queryParameter("page"))
        assertEquals("en-US", url.queryParameter("language"))
        assertEquals("false", url.queryParameter("include_adult"))
        assertEquals("Bearer fake-test-token", request.getHeader("Authorization"))
    }

    @Test fun searchFailureAndMalformedRequiredFieldsUseSafeBoundary() = runBlocking {
        for ((body, status, error) in listOf(
            Triple("{}", 429, DataError.Network.TOO_MANY_REQUESTS),
            Triple("""{"page":1,"results":[{"id":42}]}""", 200, DataError.Network.SERIALIZATION),
        )) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(body))
            assertEquals(Result.Error(error), safeCall { service.searchMovies("Inter", 1, "en-US", false) })
        }
    }

    @Test fun blankTokenStillMakesRequestAndHttpResponseDeterminesAuthorizationFailure() = runBlocking {
        val blankConfig = TmdbConfig("", server.url("/3/").toString())
        val blankService = createTmdbRetrofit(createTmdbClient(blankConfig), createTmdbMoshi(), blankConfig)
            .create(TmdbService::class.java)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(Result.Error(DataError.Network.UNAUTHORIZED), safeCall { blankService.getPopularMovies(1, "en-US") })
        assertEquals("Bearer", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidAuthorizationHeaderDoesNotExposeCredentialInException() {
        val invalidConfig = TmdbConfig("fake-private\nvalue", server.url("/3/").toString())
        val request = Request.Builder().url(server.url("/3/movie/popular")).build()
        try {
            createTmdbClient(invalidConfig).newCall(request).execute().close()
            fail("Expected header validation failure")
        } catch (failure: IOException) {
            assertFalse(failure.message.orEmpty().contains(invalidConfig.readAccessToken))
            assertNull(failure.cause)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun invalidAuthorizationHeaderIsTypedFailureWithoutDispatcherCrash() = runBlocking {
        val uncaughtFailure = AtomicReference<Throwable>()
        val dispatcherThreads = CopyOnWriteArrayList<Thread>()
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "tmdb-invalid-header-test").apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
                    uncaughtFailure.set(failure)
                }
            }.also { dispatcherThreads.add(it) }
        }
        val invalidConfig = TmdbConfig("fake-private\nvalue", server.url("/3/").toString())
        val client = createTmdbClient(invalidConfig).newBuilder()
            .dispatcher(Dispatcher(executor))
            .build()
        val invalidService = createTmdbRetrofit(client, createTmdbMoshi(), invalidConfig)
            .create(TmdbService::class.java)
        try {
            assertEquals(
                Result.Error(DataError.Network.UNKNOWN),
                safeCall { invalidService.getPopularMovies(1, "en-US") },
            )
        } finally {
            executor.shutdown()
            assertTrue("Dispatcher did not finish", executor.awaitTermination(2, TimeUnit.SECONDS))
            // Executor termination can precede the thread's uncaught-exception handler.
            dispatcherThreads.forEach { it.join(2_000) }
        }
        assertNull("Authorization validation escaped the async request", uncaughtFailure.get())
        assertEquals(0, server.requestCount)
    }

    @Test fun missingRequiredResponseOrMovieFieldsReturnSerializationFailure() = runBlocking {
        for (body in listOf("{}", """{"page":1,"results":[{"title":"Missing identity"}]}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(Result.Error(DataError.Network.SERIALIZATION), safeCall { service.getPopularMovies(1, "en-US") })
        }
    }

    @Test fun successfulRequestDecodesAndForwardsPageWithBearerAuthentication() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"page":3,"results":[{"id":7,"title":"Filmio","vote_average":0,"vote_count":0},null]}"""))
        val response = service.getPopularMovies(3, "en-US")
        assertEquals(7L, response.results.first()!!.id)
        assertEquals(0.0, response.results.first()!!.voteAverage!!, 0.0)
        assertNull(response.results.last())
        assertNull(response.totalPages)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/3/movie/popular", request.requestUrl!!.encodedPath)
        assertEquals(setOf("page", "language"), request.requestUrl!!.queryParameterNames)
        assertEquals("3", request.requestUrl!!.queryParameter("page"))
        assertEquals("en-US", request.requestUrl!!.queryParameter("language"))
        assertEquals("Bearer fake-test-token", request.getHeader("Authorization"))
        assertFalse(config.toString().contains("fake-test-token"))
        assertFalse(request.path!!.contains("fake-test-token"))
        assertEquals(1, server.requestCount)
    }

    @Test fun decodesSuppliedTotalPages() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"page":1,"total_pages":12,"results":[]}"""))
        val response = service.getPopularMovies(1, "en-US")
        assertEquals(1, response.page)
        assertEquals(12, response.totalPages)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("1", request.requestUrl!!.queryParameter("page"))
        assertEquals("en-US", request.requestUrl!!.queryParameter("language"))
    }
    @Test fun missingRequiredDetailAndChildFieldsFailWithoutPartialDecoding() = runBlocking {
        for (body in listOf(
            """{"title":"Missing ID"}""",
            """{"id":42}""",
            """{"id":42,"title":null}""",
            """{"id":42,"title":"Movie","genres":[{"id":1}]}""",
            """{"id":42,"title":"Movie","production_companies":[{"name":"Company"}]}""",
            """{"id":42,"title":"Movie","belongs_to_collection":{"id":2}}""",
        )) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(Result.Error(DataError.Network.SERIALIZATION), safeCall { service.getMovieDetails(42, "en-US") })
        }
    }

    @Test fun detailsDecodeSelectedFieldsAndRequestOnlyIdAndLanguage() = runBlocking {
        server.enqueue(MockResponse().setBody("""{
            "id":42,"title":"Arrival","overview":null,"poster_path":"/poster",
            "backdrop_path":"/backdrop","release_date":"2016-11-11",
            "vote_average":0,"vote_count":0,"original_title":"Arrival","original_language":"en",
            "runtime":116,"tagline":"Why are they here?","status":"Released",
            "budget":4000000000,"revenue":5000000000,"homepage":"https://example.com",
            "imdb_id":"tt2543164","belongs_to_collection":{"id":4,"name":"Collection"},
            "genres":[null,{"id":1,"name":"Science Fiction"}],
            "production_companies":[{"id":2,"name":"Company","logo_path":"ignored"},null]
        }"""))
        val dto = service.getMovieDetails(42, "en-US")
        assertEquals(42L, dto.id)
        assertEquals("Arrival", dto.title)
        assertNull(dto.overview)
        assertEquals("/poster", dto.posterPath)
        assertEquals("/backdrop", dto.backdropPath)
        assertEquals("2016-11-11", dto.releaseDate)
        assertEquals(0.0, dto.voteAverage!!, 0.0)
        assertEquals(0, dto.voteCount)
        assertEquals("Arrival", dto.originalTitle)
        assertEquals("en", dto.originalLanguage)
        assertEquals(116, dto.runtime)
        assertEquals("Why are they here?", dto.tagline)
        assertEquals("Released", dto.status)
        assertEquals(4_000_000_000L, dto.budget)
        assertEquals(5_000_000_000L, dto.revenue)
        assertEquals("https://example.com", dto.homepage)
        assertEquals("tt2543164", dto.imdbId)
        assertEquals("Collection", dto.collection!!.name)
        assertEquals(4L, dto.collection.id)
        assertNull(dto.genres!!.first())
        assertEquals("Science Fiction", dto.genres.last()!!.name)
        assertEquals("Company", dto.productionCompanies!!.first()!!.name)
        assertNull(dto.productionCompanies.last())
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/3/movie/42", request.requestUrl!!.encodedPath)
        assertEquals(setOf("language"), request.requestUrl!!.queryParameterNames)
        assertEquals("en-US", request.requestUrl!!.queryParameter("language"))
        assertEquals("Bearer fake-test-token", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains("fake-test-token"))
    }

    @Test fun minimalDetailsAllowMissingOrExplicitNullOptionalMetadata() = runBlocking {
        for (body in listOf(
            """{"id":42,"title":"Movie"}""",
            """{"id":42,"title":"Movie","genres":null,"production_companies":null,"belongs_to_collection":null}""",
        )) {
            server.enqueue(MockResponse().setBody(body))
            val dto = service.getMovieDetails(42, "en-US")
            assertNull(dto.runtime)
            assertNull(dto.budget)
            assertNull(dto.genres)
            assertNull(dto.productionCompanies)
            assertNull(dto.collection)
        }
    }

}
