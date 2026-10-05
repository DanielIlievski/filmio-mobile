package com.example.filmio.feature.catalog.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState

/** Keeps Room's absolute offset keys stable when the presenter omits leading placeholders. */
internal class AnchoredMoviePagingSource<T : Any>(
    private val source: PagingSource<Int, T>,
) : PagingSource<Int, T>() {
    init {
        val onInvalidated: () -> Unit = { invalidate() }
        source.registerInvalidatedCallback(onInvalidated)
        registerInvalidatedCallback {
            source.unregisterInvalidatedCallback(onInvalidated)
            source.invalidate()
        }
    }

    override val jumpingSupported: Boolean get() = source.jumpingSupported
    override val keyReuseSupported: Boolean get() = source.keyReuseSupported
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = source.load(params)

    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        val anchor = state.anchorPosition ?: return source.getRefreshKey(state)
        if (state.config.enablePlaceholders) return source.getRefreshKey(state)
        val offset = state.pages.firstOrNull()?.itemsBefore
            ?.takeUnless { it == LoadResult.Page.COUNT_UNDEFINED } ?: 0
        return source.getRefreshKey(
            PagingState(state.pages, anchor + offset, state.config, leadingPlaceholderCount = 0),
        )
    }
}
