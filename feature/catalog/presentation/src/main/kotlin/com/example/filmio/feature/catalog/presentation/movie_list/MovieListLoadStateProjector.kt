package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState

/** Retained by the ViewModel so initial-outcome knowledge survives screen recollection. */
internal class MovieListLoadStateProjector {
    private var sawRemoteRefreshLoading = false
    private var remoteRefreshSucceeded = false

    fun project(loads: CombinedLoadStates, hasItems: Boolean): MovieListState {
        val remote = loads.mediator

        when (remote?.refresh) {
            is LoadState.Loading -> sawRemoteRefreshLoading = true
            is LoadState.Error -> {
                remoteRefreshSucceeded = false
                sawRemoteRefreshLoading = false
            }

            is LoadState.NotLoading -> {
                // Terminal flags also establish success if composition missed a fast Loading signal.
                if (sawRemoteRefreshLoading || remote.append.endOfPaginationReached || remote.prepend.endOfPaginationReached) {
                    remoteRefreshSucceeded = true
                }
                sawRemoteRefreshLoading = false
            }

            null -> Unit
        }

        val refreshLoading = loads.source.refresh is LoadState.Loading || remote?.refresh is LoadState.Loading
        val appendLoading = loads.source.append is LoadState.Loading || remote?.append is LoadState.Loading
        val appendFailure = (loads.source.append as? LoadState.Error) ?: (remote?.append as? LoadState.Error)
        val refreshFailure = (loads.source.refresh as? LoadState.Error)
            ?: (loads.source.prepend as? LoadState.Error)
            ?: (remote?.refresh as? LoadState.Error)
            ?: appendFailure.takeUnless { hasItems }
        val settled = loads.source.refresh is LoadState.NotLoading && remote?.refresh is LoadState.NotLoading

        return MovieListState(
            isInitialLoading = !hasItems && refreshFailure == null && (refreshLoading || !remoteRefreshSucceeded),
            isRefreshing = refreshLoading,
            isAppending = hasItems && appendLoading,
            isEmpty = !hasItems && settled && remoteRefreshSucceeded && refreshFailure == null && !appendLoading,
            refreshError = refreshFailure?.error?.toPagingUiText(),
            appendError = appendFailure?.error?.toPagingUiText().takeIf { hasItems },
        )
    }
}
