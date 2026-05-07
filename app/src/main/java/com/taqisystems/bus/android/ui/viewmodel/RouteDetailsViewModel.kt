// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.data.model.TripDetails
import com.taqisystems.bus.android.data.model.TripStop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RouteDetailsUiState(
    val details: TripDetails? = null,
    val stops: List<TripStop> = emptyList(),
    val currentArrival: ObaArrival? = null,
    val currentStopIndex: Int = -1,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val lastUpdated: Long? = null,
    val error: String? = null,
)

class RouteDetailsViewModel : ViewModel() {
    private val obaRepo = ServiceLocator.obaRepository
    private val prefs = ServiceLocator.preferences
    private val _uiState = MutableStateFlow(RouteDetailsUiState())
    val uiState: StateFlow<RouteDetailsUiState> = _uiState.asStateFlow()

    val savedRoutes: StateFlow<List<SavedRoute>> = prefs.savedRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pollJob: Job? = null
    private var currentTripId: String = ""
    private var currentStopId: String = ""

    fun load(tripId: String, stopId: String = "") {
        currentTripId = tripId
        currentStopId = stopId
        pollJob?.cancel()
        fetchDetails(tripId, silent = false)
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                fetchDetails(tripId, silent = true)
            }
        }
    }

    fun refreshNow() {
        if (currentTripId.isNotBlank()) fetchDetails(currentTripId, silent = true)
    }

    fun toggleSavedRoute(route: SavedRoute) {
        viewModelScope.launch {
            prefs.toggleSavedRoute(route, savedRoutes.value)
        }
    }

    private fun fetchDetails(tripId: String, silent: Boolean) {
        viewModelScope.launch {
            if (!silent) _uiState.value = _uiState.value.copy(loading = true, error = null)
            else _uiState.value = _uiState.value.copy(refreshing = true)
            runCatching { obaRepo.getTripDetails(tripId) }
                .onSuccess { d ->
                    val stops = d?.stops ?: emptyList()
                    val stopIdx = if (currentStopId.isNotBlank())
                        stops.indexOfFirst { it.stopId == currentStopId }
                    else
                        stops.indexOfFirst { it.stopId == d?.closestStopId }
                    _uiState.value = _uiState.value.copy(
                        details = d,
                        stops = stops,
                        currentStopIndex = stopIdx,
                        lastUpdated = System.currentTimeMillis(),
                        error = if (d == null) {
                            Log.e("RouteDetailsVM", "getTripDetails returned null for tripId=$tripId")
                            "Trip details unavailable."
                        } else null,
                    )
                }
                .onFailure { e ->
                    Log.e("RouteDetailsVM", "fetchDetails error for tripId=$tripId", e)
                    if (!silent) _uiState.value = _uiState.value.copy(error = e.message)
                }
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

class RouteDetailsViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return RouteDetailsViewModel() as T
    }
}
