package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.onFailure
import com.example.filmio.core.domain.onSuccess
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.CatalogStorageException
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.presentation.R
import com.example.filmio.feature.catalog.presentation.util.toUiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MovieDetailViewModel(
    private val movieId: Long,
    private val catalogRepository: CatalogRepository,
    private val connectivityObserver: ConnectivityObserver,
) : ViewModel() {

    private val eventChannel = Channel<MovieDetailEvent>()
    val events = eventChannel.receiveAsFlow()
        .onStart { observeConnectivity() }
        .onCompletion {
            connectivityJob?.cancel()
            connectivityJob = null
        }

    private var hasLoadedInitialData = false
    private var connectivityJob: Job? = null
    private val fetchRequests = Channel<Unit>(Channel.CONFLATED)
    private var hasMovieReadFailed = false

    private val _state = MutableStateFlow(
        if (movieId > 0) {
            MovieDetailState()
        } else {
            MovieDetailState(isLoading = false, error = UiText.Resource(R.string.error_movie_id))
        }
    )

    val state: StateFlow<MovieDetailState> = _state
        .onStart {
            if (movieId > 0 && !hasLoadedInitialData) {
                hasLoadedInitialData = true
                viewModelScope.launch {
                    fetchRequests.receiveAsFlow()
                        .onStart { emit(Unit) }
                        .collectLatest { fetchDetails() }
                }
            }
            // These observers belong to this state collection, not the ViewModel lifetime.
            val observationScope = CoroutineScope(currentCoroutineContext())
            observationScope.launch { observeDetails() }
            observationScope.launch { observeIsFavorite() }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = _state.value
        )

    fun onAction(action: MovieDetailAction) {
        when (action) {
            is MovieDetailAction.OnSetFavorite -> setFavorite(action.desired)
            MovieDetailAction.OnBackClick -> viewModelScope.launch {
                eventChannel.send(MovieDetailEvent.NavigateBack)
            }
        }
    }

    private suspend fun fetchDetails() {
        _state.update {
            it.copy(isLoading = true, error = if (hasMovieReadFailed) it.error else null)
        }
        catalogRepository.fetchMovieDetails(movieId)
            .onSuccess {
                _state.update {
                    it.copy(isLoading = false, error = if (hasMovieReadFailed) it.error else null)
                }
            }
            .onFailure { error ->
                val errorMessage = when (error) {
                    DataError.Network.NOT_FOUND -> UiText.Resource(R.string.error_movie_not_found)
                    else -> error.toUiText()
                }
                _state.update {
                    it.copy(isLoading = false, error = if (hasMovieReadFailed) it.error else errorMessage)
                }
            }
    }

    private suspend fun observeDetails() {
        if (movieId <= 0) return
        catalogRepository.getMovieDetails(movieId)
            .catch { failure ->
                if (failure !is CatalogStorageException) throw failure
                hasMovieReadFailed = true
                _state.update { it.copy(error = failure.error.toUiText()) }
            }
            .collect { movie ->
                val recoveredRead = hasMovieReadFailed
                hasMovieReadFailed = false
                _state.update {
                    it.copy(movie = movie, error = if (recoveredRead) null else it.error)
                }
            }
    }

    private suspend fun observeIsFavorite() {
        if (movieId <= 0) return

        catalogRepository.observeIsMovieFavorite(movieId)
            .catch { failure ->
                if (failure !is CatalogStorageException) throw failure
                _state.update { it.copy(error = failure.error.toUiText()) }
            }
            .collect { isFavorite ->
                _state.update { it.copy(isFavorite = isFavorite) }
            }
    }

    private fun setFavorite(desired: Boolean) {
        if (movieId <= 0 || _state.value.movie == null) return

        viewModelScope.launch {
            catalogRepository.setMovieFavorite(movieId, desired)
                .onFailure { error ->
                    _state.update { it.copy(error = error.toUiText()) }
                }
        }
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) return
        connectivityJob = viewModelScope.launch {
            var previous: Boolean? = null
            connectivityObserver.isConnected.collect { connected ->
                _state.update { it.copy(isConnected = connected) }
                if (previous == false && connected && hasLoadedInitialData) {
                    fetchRequests.trySend(Unit)
                }
                previous = connected
            }
        }
    }
}
