package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MovieListViewModel(
    catalogRepository: CatalogRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    val movies = catalogRepository.getPagedMovies().cachedIn(viewModelScope)

    private val eventChannel = Channel<MovieListEvent>()
    // The root's lifecycle-aware event collector owns reconnect observation.
    val events = eventChannel.receiveAsFlow()
        .onStart { observeConnectivity() }
        .onCompletion {
            connectivityJob?.cancel()
            connectivityJob = null
        }

    private val _state = MutableStateFlow(MovieListState())
    val state: StateFlow<MovieListState> = _state.asStateFlow()

    private val projector = MovieListLoadStateProjector()
    private var connectivityJob: Job? = null

    fun onAction(action: MovieListAction) {
        when (action) {
            is MovieListAction.OnLoadStatesChanged -> _state.value = projector.project(action.loadStates, action.hasItems)
            MovieListAction.OnRefreshClick -> {
                if (_state.value.isRefreshing || _state.value.isInitialLoading) return
                markRefreshing()
                viewModelScope.launch { eventChannel.send(MovieListEvent.RefreshMovies) }
            }
            MovieListAction.OnRetryClick -> {
                val current = _state.value
                if (current.isRefreshing || current.isAppending || (current.refreshError == null && current.appendError == null)) return
                _state.value = current.copy(
                    isRefreshing = current.refreshError != null,
                    isAppending = current.appendError != null,
                    refreshError = null, appendError = null, isEmpty = false,
                )
                viewModelScope.launch { eventChannel.send(MovieListEvent.RetryMovies) }
            }
        }
    }

    private fun markRefreshing() {
        _state.value = _state.value.copy(isRefreshing = true, isEmpty = false, refreshError = null, appendError = null)
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) return
        connectivityJob = viewModelScope.launch {
            var previous: Boolean? = null
            connectivityObserver.isConnected.collect { connected ->
                if (previous == false && connected) {
                    // Wait for the receiver during startup; collector cancellation cancels this send.
                    eventChannel.send(MovieListEvent.RefreshMovies)
                    markRefreshing()
                }
                previous = connected
            }
        }
    }
}
