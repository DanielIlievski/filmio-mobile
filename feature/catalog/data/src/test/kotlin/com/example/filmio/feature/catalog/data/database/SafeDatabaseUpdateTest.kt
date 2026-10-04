package com.example.filmio.feature.catalog.data.database

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mock

class SafeDatabaseUpdateTest {
    @Test
    fun diskFullHasSpecificFeedbackWhileOtherSqliteFailuresStayGeneric() = runTest {
        assertEquals(
            Result.Error(DataError.Local.DISK_FULL),
            safeDatabaseUpdate<Unit> { throw mock(SQLiteFullException::class.java) },
        )
        assertEquals(
            Result.Error(DataError.Local.UNKNOWN),
            safeDatabaseUpdate<Unit> { throw mock(SQLiteException::class.java) },
        )
    }

    @Test
    fun cancellationAndUnexpectedDefectsPropagateUnchanged() = runTest {
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("defect"))) {
            try {
                safeDatabaseUpdate<Unit> { throw failure }
                fail("Expected the original exception")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
        }
    }
}
