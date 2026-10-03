package com.example.filmio.core.data.networking

import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

/** Converts one typed Retrofit service call to Filmio's domain-facing network result. */
suspend fun <T> safeCall(
    execute: suspend () -> T,
): Result<T, DataError.Network> = try {
    val response = execute()
    Result.Success(response)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (http: HttpException) {
    Result.Error(http.code().toNetworkError())
} catch (_: UnknownHostException) {
    Result.Error(DataError.Network.NO_INTERNET)
} catch (_: ConnectException) {
    Result.Error(DataError.Network.NO_INTERNET)
} catch (_: InterruptedIOException) {
    Result.Error(DataError.Network.REQUEST_TIMEOUT)
} catch (_: JsonDataException) {
    Result.Error(DataError.Network.SERIALIZATION)
} catch (_: JsonEncodingException) {
    Result.Error(DataError.Network.SERIALIZATION)
} catch (_: EOFException) {
    Result.Error(DataError.Network.SERIALIZATION)
} catch (nullBody: KotlinNullPointerException) {
    if (nullBody.isRetrofitNullBody()) {
        Result.Error(DataError.Network.SERIALIZATION)
    } else {
        throw nullBody
    }
} catch (_: IOException) {
    Result.Error(DataError.Network.UNKNOWN)
}

// Retrofit 3 throws this from its suspend adapter when a successful body decodes to null.
private fun NullPointerException.isRetrofitNullBody(): Boolean =
    message?.endsWith(" was null but response body type was declared as non-null") == true

private fun Int.toNetworkError(): DataError.Network = when (this) {
    400 -> DataError.Network.BAD_REQUEST
    401 -> DataError.Network.UNAUTHORIZED
    403 -> DataError.Network.FORBIDDEN
    404 -> DataError.Network.NOT_FOUND
    408 -> DataError.Network.REQUEST_TIMEOUT
    409 -> DataError.Network.CONFLICT
    413 -> DataError.Network.PAYLOAD_TOO_LARGE
    429 -> DataError.Network.TOO_MANY_REQUESTS
    500 -> DataError.Network.SERVER_ERROR
    503 -> DataError.Network.SERVICE_UNAVAILABLE
    in 500..599 -> DataError.Network.SERVER_ERROR
    else -> DataError.Network.UNKNOWN
}
