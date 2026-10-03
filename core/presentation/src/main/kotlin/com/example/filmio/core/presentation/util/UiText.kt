package com.example.filmio.core.presentation.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data class DynamicString(val value: String) : UiText

    class Resource(
        @param:StringRes val id: Int,
        val args: Array<Any> = arrayOf()
    ) : UiText

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is Resource -> stringResource(id = id, *args)
    }

    suspend fun asStringAsync(context: Context): String = when (this) {
        is DynamicString -> value
        is Resource -> context.getString(id, *args)
    }
}
