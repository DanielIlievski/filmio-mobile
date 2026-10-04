package com.example.filmio.feature.catalog.domain.repository

import com.example.filmio.core.domain.DataError

/** Safe error vocabulary for Paging's Throwable slot; contains no transport payload or cause. */
class CatalogPagingException(val error: DataError) : Exception()
