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
import com.example.filmio.feature.catalog.domain.repository.CatalogStorageException
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.presentation.util.toUiText
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private var hasLoadedInitialData = false

    private val eventChannel = Channel<MovieListEvent>()
    val events = eventChannel.receiveAsFlow()
        .onStart { observeConnectivity() }
        .onCompletion {
            connectivityJob?.cancel()
            connectivityJob = null
        }

    private val _state = MutableStateFlow(
        run {
            val query = savedStateHandle.get<String>(QUERY_KEY) ?: ""
            val view = savedStateHandle.get<String>(VIEW_KEY)
                ?.let { name -> CatalogView.entries.find { it.name == name } }
                ?: CatalogView.ALL
            MovieListState(
                catalogView = view,
                queryTextState = TextFieldState(query),
                isSearchActive = query.isNotBlank(),
                isDebouncing = query.isNotBlank() && view == CatalogView.ALL,
            )
        }
    )
    val state: StateFlow<MovieListState> = _state
        .onStart {
            if (!hasLoadedInitialData) {
                observeFavorites()
                searchFlow.launchIn(viewModelScope)
                hasLoadedInitialData = true
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = _state.value
        )

    private var projector = MovieListLoadStateProjector()
    private var lastLoadFeedback: MovieListAction.OnLoadStatesChanged? = null

    // Feedback and commands must identify their producing Pager, including A -> B -> A.
    private var generation = 0L
    private var activeQuery = _state.value.queryTextState.text.toString().trim()
    private val homeMovies by lazy { catalogRepository.getPagedMovies().cachedIn(viewModelScope) }
    private val savedMovies by lazy { catalogRepository.getPagedSavedMovies().cachedIn(viewModelScope) }

    // cachedIn keeps collecting with no UI receiver; cancel that work on query changes.
    private var queryScope = newQueryScope()
    private var fetchRemote = MutableStateFlow(false)
    private val _movies = MutableStateFlow(createMovies(activeQuery, _state.value.catalogView))

    // Outer state selects a query's cached stream so the UI can reset its presenter.
    // Inner emissions switch local -> remote paging without resetting that presenter.
    val movies: StateFlow<Flow<PagingData<Movie>>> = _movies.asStateFlow()

    private val searchFlow = combine(
        snapshotFlow { _state.value.queryTextState.text.toString() }
            .onEach { savedStateHandle[QUERY_KEY] = it }
            .map { it.trim() }.distinctUntilChanged(),
        _state.map { it.catalogView }.distinctUntilChanged(),
    ) { query, view -> query to view }
        // Ignore a queued combination from before a synchronous selection change.
        .filter { (_, view) -> view == _state.value.catalogView }
        .onEach { (query, view) -> changeRequest(query, view) }
        .debounce(500.milliseconds)
        .onEach { (query, view) ->
            if (query.isNotEmpty() && query == activeQuery && view == _state.value.catalogView && view == CatalogView.ALL) {
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

    private fun createMovies(query: String, view: CatalogView): Flow<PagingData<Movie>> {
        val remoteMode = fetchRemote
        if (query.isEmpty()) return if (view == CatalogView.SAVED) savedMovies else homeMovies
        val pages = if (view == CatalogView.SAVED) {
            catalogRepository.getPagedSavedMovies(query)
        } else remoteMode.flatMapLatest { catalogRepository.searchMovies(query, it) }
        return pages.cachedIn(queryScope)
    }

    fun onAction(action: MovieListAction) {
        when (action) {
            is MovieListAction.OnCatalogViewChange -> {
                savedStateHandle[VIEW_KEY] = action.view.name
                changeRequest(_state.value.queryTextState.text.toString().trim(), action.view)
            }

            is MovieListAction.OnSetFavorite -> setFavorite(action.movieId, action.desired)
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
            (_state.value.catalogView == CatalogView.SAVED || !_state.value.isSearchActive || (action.loadStates.mediator == null) == _state.value.isDebouncing)
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
        if (current.catalogView == CatalogView.SAVED || action.generation != generation || current.isDebouncing || current.isRefreshing || current.isInitialLoading) return
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

    private fun changeRequest(query: String, view: CatalogView) {
        if (query == activeQuery && view == _state.value.catalogView) return
        queryScope.cancel()
        queryScope = newQueryScope()
        fetchRemote = MutableStateFlow(false)
        activeQuery = query
        generation++
        projector = MovieListLoadStateProjector()
        lastLoadFeedback = null
        _state.update { current ->
            current.copy(
                catalogView = view, isSearchActive = query.isNotEmpty(), generation = generation,
                isDebouncing = query.isNotEmpty() && view == CatalogView.ALL,
                isInitialLoading = true, isRefreshing = false, isAppending = false,
                isEmpty = false, hasNoCachedMatches = false, refreshError = null, appendError = null,
            )
        }
        _movies.value = createMovies(query, view)
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

    private fun observeFavorites() {
        viewModelScope.launch {
            catalogRepository.observeFavoriteMovieIds()
                .catch { failure ->
                    if (failure !is CatalogStorageException) throw failure
                    _state.update { it.copy(refreshError = failure.error.toUiText()) }
                }
                .collect { ids ->
                    _state.update { it.copy(favoriteIds = ids) }
                }
        }
    }

    private fun setFavorite(id: Long, isFavorite: Boolean) {
        if (id <= 0 || _state.value.favoriteIds == null) return
        viewModelScope.launch {
            catalogRepository.setMovieFavorite(movieId = id, isFavorite = isFavorite)
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
                if (previous == false && connected && _state.value.catalogView == CatalogView.ALL && !_state.value.isDebouncing && !_state.value.isRefreshing) {
                    val target = generation
                    eventChannel.send(MovieListEvent.RefreshMovies(target))
                    if (target == generation) markRefreshing()
                }
                previous = connected
            }
        }
    }

    companion object {
        const val VIEW_KEY = "catalogView"
        const val QUERY_KEY = "movieSearchQuery"
    }
}
