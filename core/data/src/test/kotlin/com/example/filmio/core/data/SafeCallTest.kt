package com.example.filmio.core.data

import com.example.filmio.core.data.networking.safeCall
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SafeCallTest {
    private lateinit var server: MockWebServer
    private lateinit var service: ItemService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ItemService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `wrapped typed service returns decoded DTO`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"title":"Filmio"}"""))

        val result: Result<ItemDto, DataError.Network> = safeCall {
            service.getItem()
        }

        assertEquals(Result.Success(ItemDto("Filmio")), result)
    }

    @Test
    fun `maps named and fallback HTTP statuses`() = runBlocking {
        val cases = mapOf(
            400 to DataError.Network.BAD_REQUEST,
            401 to DataError.Network.UNAUTHORIZED,
            403 to DataError.Network.FORBIDDEN,
            404 to DataError.Network.NOT_FOUND,
            408 to DataError.Network.REQUEST_TIMEOUT,
            409 to DataError.Network.CONFLICT,
            413 to DataError.Network.PAYLOAD_TOO_LARGE,
            429 to DataError.Network.TOO_MANY_REQUESTS,
            500 to DataError.Network.SERVER_ERROR,
            503 to DataError.Network.SERVICE_UNAVAILABLE,
            502 to DataError.Network.SERVER_ERROR,
            418 to DataError.Network.UNKNOWN,
        )

        cases.forEach { (status, expected) ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("private error body"))
            assertEquals(
                "HTTP $status",
                Result.Error(expected),
                safeCall { service.getItem() },
            )
        }
    }

    @Test
    fun `maps DNS and connection failures to no internet`() = runBlocking {
        listOf(UnknownHostException(), ConnectException()).forEach { failure ->
            assertEquals(
                Result.Error(DataError.Network.NO_INTERNET),
                safeCall<ItemDto> { throw failure },
            )
        }
    }

    @Test
    fun `maps socket and call timeouts`() = runBlocking {
        listOf(SocketTimeoutException(), InterruptedIOException()).forEach { failure ->
            assertEquals(
                Result.Error(DataError.Network.REQUEST_TIMEOUT),
                safeCall<ItemDto> { throw failure },
            )
        }
    }

    @Test
    fun `maps other IO failure to unknown`() = runBlocking {
        assertEquals(
            Result.Error(DataError.Network.UNKNOWN),
            safeCall<ItemDto> { throw IOException("transport failure") },
        )
    }

    @Test
    fun `maps malformed and truncated Moshi responses to serialization`() = runBlocking {
        listOf("""{"title":!!}""", "{\"title\":").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(
                Result.Error(DataError.Network.SERIALIZATION),
                safeCall { service.getItem() },
            )
        }
    }

    @Test
    fun `maps empty or null successful body to serialization`() = runBlocking {
        listOf("", "null").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertEquals(
                Result.Error(DataError.Network.SERIALIZATION),
                safeCall { service.getItem() },
            )
        }
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(
            Result.Error(DataError.Network.SERIALIZATION),
            safeCall { service.getItem() },
        )
    }

    @Test
    fun `propagates cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                safeCall<ItemDto> { throw CancellationException("superseded") }
            }
        }
    }

    @Test
    fun `propagates programming failure`() {
        val failure = IllegalStateException("bad mapper")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { safeCall<ItemDto> { throw failure } }
        }
        assertEquals(failure, thrown)
    }

    @Test
    fun `propagates unrelated null pointer failure`() {
        val failure = KotlinNullPointerException("bad mapper")
        val thrown = assertThrows(NullPointerException::class.java) {
            runBlocking { safeCall<ItemDto> { throw failure } }
        }
        assertEquals(failure, thrown)
    }
}

internal interface ItemService {
    @GET("item")
    suspend fun getItem(): ItemDto
}

internal data class ItemDto(val title: String)
