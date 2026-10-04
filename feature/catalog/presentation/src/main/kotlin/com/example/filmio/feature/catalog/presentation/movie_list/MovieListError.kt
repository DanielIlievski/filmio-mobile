package com.example.filmio.feature.catalog.presentation.movie_list

import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.presentation.util.toUiText
import com.example.filmio.feature.catalog.presentation.R
import com.example.filmio.feature.catalog.domain.repository.CatalogPagingException

internal fun Throwable.toPagingUiText(): UiText =
    (this as? CatalogPagingException)?.error?.toUiText() ?: UiText.Resource(R.string.error_storage)
