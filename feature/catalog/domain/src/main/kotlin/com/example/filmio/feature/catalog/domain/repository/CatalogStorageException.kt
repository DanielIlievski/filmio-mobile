package com.example.filmio.feature.catalog.domain.repository

import com.example.filmio.core.domain.DataError

/** Expected local observation failure without exposing database exceptions or their messages. */
class CatalogStorageException(val error: DataError.Local) : Exception()
