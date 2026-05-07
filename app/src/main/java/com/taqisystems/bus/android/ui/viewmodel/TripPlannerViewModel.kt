// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.OtpItinerary
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.data.model.SavedPlace
import com.taqisystems.bus.android.data.model.outerBoundingBox
import com.taqisystems.bus.android.ui.util.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripPlannerUiState(
    val originText: String = "",
    val destinationText: String = "",
    val origin: PlaceResult? = null,
    val destination: PlaceResult? = null,
    val suggestions: List<PlaceResult> = emptyList(),
    val itineraries: List<OtpItinerary> = emptyList(),
    val selectedItinerary: OtpItinerary? = null,
    val loading: Boolean = false,
    val error: UiText? = null,
    val activeRegion: ObaRegion? = null,
    val selectedMode: String = "TRANSIT,WALK",
    val departMode: String = "now",
    val selectedDate: String? = null,
    val selectedTime: String? = null,
)

/** Process-level holder so TripItineraryScreen always sees the last-tapped itinerary. */
object SelectedItineraryHolder {
    var itinerary: OtpItinerary? = null
}

class TripPlannerViewModel : ViewModel() {
    private val otpRepo = ServiceLocator.otpRepository
    private val geocodingRepo = ServiceLocator.geocodingRepository
    private val regionsRepo = ServiceLocator.regionsRepository
    private val prefs = ServiceLocator.preferences

    private val _uiState = MutableStateFlow(TripPlannerUiState())
    val uiState: StateFlow<TripPlannerUiState> = _uiState.asStateFlow()

    val homePlace: StateFlow<SavedPlace?> = prefs.homePlace
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val workPlace: StateFlow<SavedPlace?> = prefs.workPlace
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var searchJob: Job? = null

    init {
        // Observe region changes so planTrip() always uses the current region's OTP URL
        viewModelScope.launch {
            prefs.regionId.collectLatest { regionIdStr ->
                if (regionIdStr != null) {
                    val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
                    val region = regions.find { it.id.toString() == regionIdStr }
                    if (region != null) {
                        _uiState.value = _uiState.value.copy(activeRegion = region)
                    }
                }
            }
        }
    }

    fun setMode(mode: String) { _uiState.value = _uiState.value.copy(selectedMode = mode) }
    fun setDepartMode(mode: String) { _uiState.value = _uiState.value.copy(departMode = mode) }
    fun setDateTime(date: String, time: String) { _uiState.value = _uiState.value.copy(selectedDate = date, selectedTime = time) }

    fun setOriginText(text: String) {
        _uiState.value = _uiState.value.copy(originText = text, origin = null)
        if (text.length >= 2) searchPlaces(text)
        else _uiState.value = _uiState.value.copy(suggestions = emptyList())
    }

    fun setDestinationText(text: String) {
        _uiState.value = _uiState.value.copy(destinationText = text, destination = null)
        if (text.length >= 2) searchPlaces(text)
        else _uiState.value = _uiState.value.copy(suggestions = emptyList())
    }

    fun clearOrigin() { _uiState.value = _uiState.value.copy(origin = null, originText = "", suggestions = emptyList()) }
    fun clearDestination() { _uiState.value = _uiState.value.copy(destination = null, destinationText = "", suggestions = emptyList()) }

    fun selectOrigin(place: PlaceResult) {
        _uiState.value = _uiState.value.copy(origin = place, originText = place.label, suggestions = emptyList())
    }

    fun selectDestination(place: PlaceResult) {
        _uiState.value = _uiState.value.copy(destination = place, destinationText = place.label, suggestions = emptyList())
    }

    // legacy aliases
    fun setOrigin(place: PlaceResult) = selectOrigin(place)
    fun setDestination(place: PlaceResult) = selectDestination(place)

    fun swapOriginDestination() {
        val s = _uiState.value
        _uiState.value = s.copy(
            origin = s.destination, originText = s.destinationText,
            destination = s.origin, destinationText = s.originText,
        )
    }

    fun selectItinerary(itinerary: OtpItinerary) {
        _uiState.value = _uiState.value.copy(selectedItinerary = itinerary)
    }

    fun clearSuggestions() { _uiState.value = _uiState.value.copy(suggestions = emptyList()) }

    fun searchPlaces(query: String, focusLat: Double? = null, focusLon: Double? = null) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val region = _uiState.value.activeRegion
            val bbox = region?.outerBoundingBox()
            val results = runCatching {
                geocodingRepo.searchPlaces(
                    query,
                    focusLat = focusLat ?: region?.centerLat,
                    focusLon = focusLon ?: region?.centerLon,
                    boundMinLat = bbox?.get(0),
                    boundMaxLat = bbox?.get(1),
                    boundMinLon = bbox?.get(2),
                    boundMaxLon = bbox?.get(3),
                )
            }.getOrElse { emptyList() }
            _uiState.value = _uiState.value.copy(suggestions = results)
        }
    }

    fun planTrip() {
        val state = _uiState.value
        val origin = state.origin ?: return
        val destination = state.destination ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null, itineraries = emptyList())
            runCatching {
                // Always read OTP URL from DataStore first — it is updated atomically when the
                // user changes region in Settings, so it can never be stale.
                val storedOtpUrl = prefs.otpBaseUrl.first()
                val regions = regionsRepo.fetchRegions()
                val region = _uiState.value.activeRegion
                    ?: regionsRepo.findRegionForLocation(regions, origin.lat, origin.lon)
                    ?: regions.firstOrNull { it.otpBaseUrl != null }
                val otpUrl = storedOtpUrl?.takeIf { it.isNotBlank() }
                    ?: region?.otpBaseUrl
                    ?: throw Exception("No transit server found for this area.")
                val arriveBy = state.departMode == "arrive"
                otpRepo.planTrip(
                    fromLat = origin.lat, fromLon = origin.lon,
                    toLat = destination.lat, toLon = destination.lon,
                    otpBaseUrl = otpUrl,
                    date = if (state.departMode == "now") null else state.selectedDate,
                    time = if (state.departMode == "now") null else state.selectedTime,
                    modes = state.selectedMode,
                    arriveBy = arriveBy,
                )
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(itineraries = result, loading = false)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message?.let { UiText.Raw(it) }, loading = false)
            }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun saveHomePlace(place: SavedPlace?) {
        viewModelScope.launch { prefs.setHomePlace(place) }
    }

    fun saveWorkPlace(place: SavedPlace?) {
        viewModelScope.launch { prefs.setWorkPlace(place) }
    }

    /** Fill origin or destination with a saved place.
     *  If [preferDestination] is true, or if origin is already set, fills destination; otherwise origin. */
    fun fillWithSavedPlace(place: PlaceResult, preferDestination: Boolean = false) {
        val s = _uiState.value
        if (preferDestination || s.origin != null) selectDestination(place)
        else selectOrigin(place)
    }
}

class TripPlannerViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TripPlannerViewModel() as T
    }
}

