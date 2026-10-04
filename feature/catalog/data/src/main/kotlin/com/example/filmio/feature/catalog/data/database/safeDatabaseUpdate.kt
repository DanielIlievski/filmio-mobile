package com.example.filmio.feature.catalog.data.database

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result

internal suspend inline fun <T> safeDatabaseUpdate(update: suspend () -> T): Result<T, DataError.Local> {
    return try {
        Result.Success(update())
    } catch (_: SQLiteFullException) {
        Result.Error(DataError.Local.DISK_FULL)
    } catch (_: SQLiteException) {
        Result.Error(DataError.Local.UNKNOWN)
    }
}
