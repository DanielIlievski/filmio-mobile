package com.example.filmio.feature.catalog.presentation.movie_detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.filmio.core.domain.DataError
import com.example.filmio.core.domain.onFailure
import com.example.filmio.core.domain.onSuccess
import com.example.filmio.core.presentation.util.UiText
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.domain.repository.ConnectivityObserver
import com.example.filmio.feature.catalog.presentation.R
import com.example.filmio.feature.catalog.presentation.util.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    private val _state = MutableStateFlow(
        if (movieId > 0) {
            MovieDetailState()
        } else {
            MovieDetailState(isLoading = false, error = UiText.Resource(R.string.error_movie_id))
        }
    )
    val state: StateFlow<MovieDetailState> = combine(
        _state,
        catalogRepository.getMovieDetails(movieId)
    ) { currentState, movie ->
        currentState.copy(isLoading = false, movie = movie)
    }
        .onStart {
            if (movieId > 0 && !hasLoadedInitialData) {
                hasLoadedInitialData = true
                fetchDetails()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = _state.value
        )

    fun onAction(action: MovieDetailAction) {
        when (action) {
            MovieDetailAction.OnBackClick -> viewModelScope.launch {
                eventChannel.send(MovieDetailEvent.NavigateBack)
            }
        }
    }

    private fun fetchDetails() {
        if (movieId <= 0 || _state.value.isLoading) return
        _state.update {
            it.copy(isLoading = true, error = null)
        }
        viewModelScope.launch {
            catalogRepository.fetchMovieDetails(movieId)
                .onSuccess {
                    _state.update {
                        it.copy(isLoading = false, error = null)
                    }
                }
                .onFailure { error ->
                    val errorMessage = when (error) {
                        DataError.Network.NOT_FOUND -> UiText.Resource(R.string.error_movie_not_found)
                        else -> error.toUiText()
                    }

                    _state.update {
                        it.copy(isLoading = false, error = errorMessage)
                    }
                }
        }
    }

    private fun observeConnectivity() {
        if (connectivityJob != null) return
        connectivityJob = viewModelScope.launch {
            var previous: Boolean? = null
            connectivityObserver.isConnected.collect { connected ->
                _state.update { it.copy(isConnected = connected) }
                if (previous == false && connected && hasLoadedInitialData) fetchDetails()
                previous = connected
            }
        }
    }
}
