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
}
