package com.example.filmio.feature.catalog.data.database

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result

internal suspend inline fun <T> safeDatabaseUpdate(update: suspend () -> T): Result<T, DataError.Local> {
    return try {
        currentCoroutineContext().ensureActive()
        val value = update()
        currentCoroutineContext().ensureActive()
        Result.Success(value)
    } catch (_: SQLiteFullException) {
        currentCoroutineContext().ensureActive()
        Result.Error(DataError.Local.DISK_FULL)
    } catch (_: SQLiteException) {
        currentCoroutineContext().ensureActive()
        Result.Error(DataError.Local.UNKNOWN)
    }
}
