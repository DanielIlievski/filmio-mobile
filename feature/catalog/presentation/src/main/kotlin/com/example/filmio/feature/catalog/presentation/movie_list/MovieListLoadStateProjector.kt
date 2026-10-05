package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState

/** Retained by the ViewModel so initial-outcome knowledge survives screen recollection. */
internal class MovieListLoadStateProjector {
    private var sawRemoteRefreshLoading = false
    private var remoteRefreshSucceeded = false

    fun recordLoadStates(loads: CombinedLoadStates) {
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
    }

    /** Read-only state projection, safe to reevaluate inside MutableStateFlow.update. */
    fun project(loads: CombinedLoadStates, hasItems: Boolean, current: MovieListState): MovieListState {
        val remote = loads.mediator
        val refreshLoading = loads.source.refresh is LoadState.Loading || remote?.refresh is LoadState.Loading
        val appendLoading = loads.source.append is LoadState.Loading || remote?.append is LoadState.Loading
        val appendFailure = (loads.source.append as? LoadState.Error) ?: (remote?.append as? LoadState.Error)
        val refreshFailure = (loads.source.refresh as? LoadState.Error)
            ?: (loads.source.prepend as? LoadState.Error)
            ?: (remote?.refresh as? LoadState.Error)
            ?: appendFailure.takeUnless { hasItems }
        val settled = loads.source.refresh is LoadState.NotLoading && remote?.refresh is LoadState.NotLoading

        val search = current.isSearchActive
        val localSettled = loads.source.refresh is LoadState.NotLoading
        val searchExhausted = remoteRefreshSucceeded && remote?.append?.endOfPaginationReached == true
        val noCachedMatches = search && current.isOffline && !hasItems && localSettled &&
            loads.source.refresh !is LoadState.Error && loads.source.prepend !is LoadState.Error && loads.source.append !is LoadState.Error
        return current.copy(
            hasNoCachedMatches = noCachedMatches,
            isInitialLoading = !hasItems && refreshFailure == null && (!noCachedMatches && (refreshLoading || !remoteRefreshSucceeded || (search && !searchExhausted))),
            isRefreshing = refreshLoading,
            isAppending = hasItems && appendLoading,
            isEmpty = !noCachedMatches && !hasItems && settled && remoteRefreshSucceeded && refreshFailure == null && !appendLoading && (!search || searchExhausted),
            refreshError = refreshFailure?.error?.toPagingUiText(),
            appendError = appendFailure?.error?.toPagingUiText().takeIf { hasItems },
        )
    }
}
