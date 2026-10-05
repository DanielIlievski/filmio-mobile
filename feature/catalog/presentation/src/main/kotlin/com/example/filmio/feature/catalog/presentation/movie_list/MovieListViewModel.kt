@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.filmio.feature.catalog.domain.model.Movie
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MovieListViewModel(
    private val catalogRepository: CatalogRepository,
    private val connectivityObserver: ConnectivityObserver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val initialQuery: String = savedStateHandle.get<String>(QUERY_KEY) ?: ""

    private var hasLoadedInitialData = false

    private val eventChannel = Channel<MovieListEvent>()
    val events = eventChannel.receiveAsFlow()
        .onStart { observeConnectivity() }
        .onCompletion {
            connectivityJob?.cancel()
            connectivityJob = null
        }

    private val _state = MutableStateFlow(
        MovieListState(
            queryTextState = TextFieldState(initialQuery),
            isSearchActive = initialQuery.isNotBlank(),
            isDebouncing = initialQuery.isNotBlank(),
        )
    )
    val state: StateFlow<MovieListState> = _state
        .onStart {
            if (!hasLoadedInitialData) {
                searchFlow.launchIn(viewModelScope)
                hasLoadedInitialData = true
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), _state.value)

    private var projector = MovieListLoadStateProjector()
    private var lastLoadFeedback: MovieListAction.OnLoadStatesChanged? = null

    // Feedback and commands must identify their producing Pager, including A -> B -> A.
    private var generation = 0L
    private var activeQuery = initialQuery.trim()

    // cachedIn keeps collecting with no UI receiver; cancel that work on query changes.
    private var queryScope = newQueryScope()
    private var fetchRemote = MutableStateFlow(false)
    private val _movies = MutableStateFlow(createMovies(activeQuery))

    // Outer state selects a query's cached stream so the UI can reset its presenter.
    // Inner emissions switch local -> remote paging without resetting that presenter.
    val movies: StateFlow<Flow<PagingData<Movie>>> = _movies.asStateFlow()

    private val searchFlow = snapshotFlow { _state.value.queryTextState.text.toString() }
        .onEach { savedStateHandle[QUERY_KEY] = it }
        .map { it.trim() }
        .distinctUntilChanged()
        .onEach { changeQuery(it) } // Local reads never wait for the online debounce.
        .debounce(500.milliseconds)
        .onEach { query ->
            if (query.isNotEmpty() && query == activeQuery) {
                generation++
                projector = MovieListLoadStateProjector()
                lastLoadFeedback = null
                _state.update { current ->
                    current.copy(
                        generation = generation, isDebouncing = false, isInitialLoading = true,
                        isRefreshing = false, isEmpty = false, hasNoCachedMatches = false,
                        refreshError = null, appendError = null,
                    )
                }
                fetchRemote.update { true }
            }
        }

    private var connectivityJob: Job? = null

    private fun newQueryScope() = CoroutineScope(
        viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])
    )

    private fun createMovies(query: String): Flow<PagingData<Movie>> {
        val remoteMode = fetchRemote
        val pages = if (query.isEmpty()) {
            catalogRepository.getPagedMovies()
        } else {
            remoteMode.flatMapLatest {
                catalogRepository.searchMovies(query, it)
            }
        }
        // One flow/presenter per query preserves rows and scroll when remote search starts.
        return pages.cachedIn(queryScope)
    }

    fun onAction(action: MovieListAction) {
        when (action) {
            MovieListAction.OnClearQuery -> _state.value.queryTextState.clearText()
            is MovieListAction.OnMovieClick -> movieClick(action)
            is MovieListAction.OnLoadStatesChanged -> loadStateChanged(action)
            is MovieListAction.OnRefreshClick -> refresh(action)
            is MovieListAction.OnRetryClick -> retry(action)
        }
    }

    private fun movieClick(action: MovieListAction.OnMovieClick) {
        if (action.movieId > 0) {
            viewModelScope.launch {
                eventChannel.send(MovieListEvent.NavigateToMovieDetail(action.movieId))
            }
        }
    }

    private fun loadStateChanged(action: MovieListAction.OnLoadStatesChanged) {
        if (action.generation == generation &&
            (!_state.value.isSearchActive || (action.loadStates.mediator == null) == _state.value.isDebouncing)
        ) {
            lastLoadFeedback = action
            // update may reevaluate its lambda; record the outcome once before projecting.
            projector.recordLoadStates(action.loadStates)
            _state.update { current ->
                projector.project(action.loadStates, action.hasItems, current)
            }
        }
    }

    private fun refresh(action: MovieListAction.OnRefreshClick) {
        val current = _state.value
        if (action.generation != generation || current.isDebouncing || current.isRefreshing || current.isInitialLoading) return
        markRefreshing()
        sendCommand(MovieListEvent.RefreshMovies(generation))
    }

    private fun retry(action: MovieListAction.OnRetryClick) {
        val current = _state.value
        if (action.generation != generation || current.isDebouncing || current.isRefreshing || current.isAppending ||
            (current.refreshError == null && current.appendError == null)
        ) return
        _state.update { state ->
            state.copy(
                isRefreshing = state.refreshError != null,
                isAppending = state.appendError != null,
                refreshError = null, appendError = null, isEmpty = false,
            )
        }
        sendCommand(MovieListEvent.RetryMovies(generation))
    }

    private fun changeQuery(query: String) {
        if (query == activeQuery) return
        queryScope.cancel()
        queryScope = newQueryScope()
        fetchRemote = MutableStateFlow(false)
        activeQuery = query
        generation++
        projector = MovieListLoadStateProjector()
        lastLoadFeedback = null
        _state.update { current ->
            MovieListState(
                queryTextState = current.queryTextState,
                isSearchActive = query.isNotEmpty(), generation = generation,
                isDebouncing = query.isNotEmpty(), isOffline = current.isOffline,
            )
        }
        _movies.update { createMovies(query) }
    }

    private fun sendCommand(event: MovieListEvent) {
        // Query-scope cancellation removes queued commands from an abandoned query.
        queryScope.launch { eventChannel.send(event) }
    }

    private fun markRefreshing() {
        _state.update { current ->
            current.copy(isRefreshing = true, isEmpty = false, refreshError = null, appendError = null)
        }
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) return
        connectivityJob = viewModelScope.launch {
            var previous: Boolean? = null
            connectivityObserver.isConnected.collect { connected ->
                _state.update { current ->
                    val offlineState = current.copy(isOffline = !connected)
                    val noCachedMatches = lastLoadFeedback?.let {
                        projector.project(it.loadStates, it.hasItems, offlineState).hasNoCachedMatches
                    } ?: offlineState.hasNoCachedMatches
                    offlineState.copy(
                        hasNoCachedMatches = noCachedMatches,
                        isEmpty = offlineState.isEmpty && !noCachedMatches,
                    )
                }
                if (previous == false && connected && !_state.value.isDebouncing && !_state.value.isRefreshing) {
                    val target = generation
                    eventChannel.send(MovieListEvent.RefreshMovies(target))
                    if (target == generation) markRefreshing()
                }
                previous = connected
            }
        }
    }

    companion object {
        const val QUERY_KEY = "movieSearchQuery"
    }
}
