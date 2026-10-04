package com.example.filmio.feature.catalog.presentation.movie_list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.filmio.core.domain.onFailure
import com.example.filmio.core.domain.onSuccess
import com.example.filmio.feature.catalog.domain.repository.CatalogRepository
import com.example.filmio.feature.catalog.presentation.movie_list.MovieListAction.OnRefreshClick
import com.example.filmio.feature.catalog.presentation.movie_list.MovieListAction.OnRetryClick
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MovieListViewModel(
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val eventChannel = Channel<MovieListEvent>()
    val events = eventChannel.receiveAsFlow()

    private var hasLoadedInitialData = false

    private val _state = MutableStateFlow(MovieListState())
    val state: StateFlow<MovieListState> = combine(
        _state,
        catalogRepository.getMovies()
    ) { currentState, movies ->
        currentState.copy(movies = movies)
    }
        .onStart {
            if (!hasLoadedInitialData) {
                hasLoadedInitialData = true
                loadMovies()
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = MovieListState(isLoading = true),
        )

    fun onAction(action: MovieListAction) {
        when (action) {
            OnRefreshClick, OnRetryClick -> loadMovies()
        }
    }

    private fun loadMovies() {
        if (_state.value.isLoading) return
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            catalogRepository.fetchMovies(page = 1)
                .onSuccess {
                    _state.update { it.copy(isLoading = false) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(error = error.toUiText(), isLoading = false)
                    }
                }
        }
    }
}
