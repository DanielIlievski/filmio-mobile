package com.example.filmio.feature.catalog.presentation.util

import com.example.filmio.core.domain.DataError
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.presentation.R

internal fun DataError.toUiText(): UiText = UiText.Resource(
    id = when (this) {
        DataError.Network.UNAUTHORIZED, DataError.Network.FORBIDDEN -> R.string.error_configuration
        DataError.Network.NO_INTERNET -> R.string.error_offline
        DataError.Network.REQUEST_TIMEOUT -> R.string.error_timeout
        DataError.Network.TOO_MANY_REQUESTS -> R.string.error_rate_limit
        DataError.Network.SERIALIZATION -> R.string.error_malformed
        DataError.Network.SERVER_ERROR, DataError.Network.SERVICE_UNAVAILABLE -> R.string.error_service
        DataError.Local.DISK_FULL -> R.string.error_disk_full
        DataError.Local.NOT_FOUND, DataError.Local.UNKNOWN -> R.string.error_storage
        DataError.Network.BAD_REQUEST, DataError.Network.NOT_FOUND, DataError.Network.CONFLICT,
        DataError.Network.PAYLOAD_TOO_LARGE, DataError.Network.UNKNOWN -> R.string.error_refresh
    }
)

