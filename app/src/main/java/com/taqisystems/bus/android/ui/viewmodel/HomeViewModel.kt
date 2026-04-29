package com.taqisystems.bus.android.ui.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.ObaRoute
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.SavedStop
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.data.model.RoutePoint
import com.taqisystems.bus.android.data.model.outerBoundingBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Unified search result shown in the "Where to?" results panel. */
sealed class HomeSearchResult {
    /** A nearby transit stop whose name matches the query. */
    data class StopResult(val stop: ObaStop) : HomeSearchResult()
    /** A geocoded destination to use as the trip end-point. */
    data class DestResult(val place: PlaceResult) : HomeSearchResult()
    /** A bus route whose short/long name matches the query. */
    data class RouteResult(val route: ObaRoute) : HomeSearchResult()
}

data class HomeUiState(
    val userLocation: Location? = null,
    val userLat: Double = 0.0,
    val userLon: Double = 0.0,
    val locationPermissionGranted: Boolean = true,
    val stops: List<ObaStop> = emptyList(),
    val selectedStop: ObaStop? = null,
    val arrivals: List<ObaArrival> = emptyList(),
    val arrivalsLoading: Boolean = false,
    val arrivalsLastUpdated: Long? = null,
    val routeShape: List<RoutePoint> = emptyList(),
    val selectedArrivalStatus: ArrivalStatus? = null,
    val activeRegion: ObaRegion? = null,
    val loading: Boolean = false,
    val error: String? = null,
    // Unified "Where to?" search
    val searchQuery: String = "",
    val searchActive: Boolean = false,
    val searchResults: List<HomeSearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    // Set when user picks a region in Settings; cleared after camera animates
    val pendingCameraCenter: Pair<Double, Double>? = null,
    // Mirrors AppPreferences.autoDetectRegion — false when user manually chose a region
    val autoDetectRegion: Boolean = true,
    // Non-null while the user has tapped a route result — filters map stops
    val routeHighlight: ObaRoute? = null,
    // Polyline points for the highlighted route (populated when selectRoute is called)
    val routeHighlightShape: List<RoutePoint> = emptyList(),
    // Live vehicle positions for the highlighted route
    val routeHighlightVehicles: List<ObaArrival> = emptyList(),
    // The arrival whose live bus was last tapped in the bottom sheet
    val focusedVehicle: ObaArrival? = null,
    // One-shot message shown when the user switches regions in Settings
    val regionSwitchMessage: String? = null,
    // Location fetch state
    val isLocatingUser: Boolean = false,
    val locationError: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val obaRepo = ServiceLocator.obaRepository
    private val regionsRepo = ServiceLocator.regionsRepository
    private val geocodingRepo = ServiceLocator.geocodingRepository
    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val savedStops: StateFlow<List<SavedStop>> = ServiceLocator.preferences.savedStops
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var arrivalsPollJob: Job? = null

    /** True after the camera has auto-centered on the user's location for the first time. */
    var hasCenteredOnLocation: Boolean = false
        private set

    /** True when the My Location FAB was explicitly tapped — bypasses autoDetect guard. */
    var forceCenterOnLocation: Boolean = false
        private set

    fun markCameracentered() {
        hasCenteredOnLocation = true
        forceCenterOnLocation = false
    }

    init {
        viewModelScope.launch {
            val prefs = ServiceLocator.preferences
            val autoDetect = prefs.autoDetectRegion.first()
            val savedRegionId = prefs.regionId.first()
            val savedCenterLat = prefs.regionCenterLat.first()
            val savedCenterLon = prefs.regionCenterLon.first()

            if (!autoDetect && savedCenterLat != null && savedCenterLon != null) {
                // Manual region selected: skip GPS, center on the saved region directly
                val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
                val region = regions.find { it.id.toString() == savedRegionId }
                _uiState.value = _uiState.value.copy(
                    autoDetectRegion = false,
                    activeRegion = region,
                    userLat = savedCenterLat,
                    userLon = savedCenterLon,
                    pendingCameraCenter = Pair(savedCenterLat, savedCenterLon),
                )
                loadStopsForLocation(savedCenterLat, savedCenterLon)
            } else {
                fetchUserLocation()
            }
            observeRegionCenter()
        }
    }

    /** Watch for manual region changes in Settings and emit a one-shot camera target. */
    private fun observeRegionCenter() {
        val prefs = ServiceLocator.preferences
        // Keep autoDetectRegion mirrored in UI state so the map can gate GPS centering
        prefs.autoDetectRegion
            .onEach { auto -> _uiState.value = _uiState.value.copy(autoDetectRegion = auto) }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            combine(
                prefs.autoDetectRegion,
                prefs.regionId,
                prefs.regionCenterLat,
                prefs.regionCenterLon,
            ) { autoDetect, regionId, lat, lon -> arrayOf(autoDetect, regionId, lat, lon) }
                .drop(1) // skip initial emission — only react to active changes
                .collect { arr ->
                    val autoDetect = arr[0] as Boolean
                    val regionId   = arr[1] as? String
                    val lat        = arr[2] as? Double
                    val lon        = arr[3] as? Double
                    if (!autoDetect && lat != null && lon != null) {
                        hasCenteredOnLocation = false
                        // Refresh activeRegion so search/geocoding uses the new region's bbox
                        val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
                        val region = regions.find { it.id.toString() == regionId }
                        _uiState.value = _uiState.value.copy(
                            pendingCameraCenter = Pair(lat, lon),
                            activeRegion = region ?: _uiState.value.activeRegion,
                        )
                    }
                }
        }
    }

    fun clearPendingCameraCenter() {
        _uiState.value = _uiState.value.copy(pendingCameraCenter = null)
    }

    fun clearRegionSwitchMessage() {
        _uiState.value = _uiState.value.copy(regionSwitchMessage = null)
    }

    @SuppressLint("MissingPermission")
    fun fetchUserLocation(recenterCamera: Boolean = false) {
        if (recenterCamera) {
            hasCenteredOnLocation = false
            forceCenterOnLocation = true
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLocatingUser = true, locationError = null)
            try {
                val loc: Location? = fusedLocation
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .await()
                if (loc != null) {
                    _uiState.value = _uiState.value.copy(
                        userLocation = loc,
                        userLat = loc.latitude, userLon = loc.longitude,
                        isLocatingUser = false,
                    )
                    loadRegionAndStops(loc.latitude, loc.longitude)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLocatingUser = false,
                        locationError = "Couldn't get your location. Try again.",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLocatingUser = false,
                    locationError = "Couldn't get your location. Try again.",
                )
            }
        }
    }

    fun clearLocationError() {
        _uiState.value = _uiState.value.copy(locationError = null)
    }

    private fun loadRegionAndStops(lat: Double, lon: Double) {
        viewModelScope.launch {
            val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
            val region = regionsRepo.findRegionForLocation(regions, lat, lon)
            val previousRegion = _uiState.value.activeRegion
            val switchedMessage = if (
                region != null &&
                previousRegion != null &&
                region.id != previousRegion.id
            ) "You're now in ${region.regionName}" else null
            _uiState.value = _uiState.value.copy(
                activeRegion = region,
                regionSwitchMessage = switchedMessage,
            )
            // Persist sidecarBaseUrl so StopDetailsViewModel can gate the reminder feature
            val prefs = ServiceLocator.preferences
            prefs.setSidecarBaseUrl(region?.sidecarBaseUrl)
            loadStopsForLocation(lat, lon)
        }
    }

    fun loadStopsForLocation(lat: Double, lon: Double) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val stops = runCatching { obaRepo.getStopsForLocation(lat, lon) }
                .getOrElse { _uiState.value = _uiState.value.copy(error = it.message); emptyList() }
            _uiState.value = _uiState.value.copy(stops = stops, loading = false)
        }
    }

    fun selectStop(stop: ObaStop?) {
        arrivalsPollJob?.cancel()
        _uiState.value = _uiState.value.copy(
            selectedStop = stop,
            arrivals = emptyList(),
            arrivalsLastUpdated = null,
            routeShape = emptyList(),
            selectedArrivalStatus = null,
        )
        if (stop != null) {
            loadArrivals(stop.id, silent = false)
            arrivalsPollJob = viewModelScope.launch {
                while (isActive) {
                    delay(30_000)
                    loadArrivals(stop.id, silent = true)
                }
            }
        }
    }

    fun clearSelectedStop() = selectStop(null)
    fun loadArrivals(stopId: String, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _uiState.value = _uiState.value.copy(arrivalsLoading = true)
            val arrivals = runCatching { obaRepo.getArrivalsForStop(stopId) }
                .getOrElse { emptyList() }
            _uiState.value = _uiState.value.copy(
                arrivals = arrivals,
                arrivalsLoading = false,
                arrivalsLastUpdated = System.currentTimeMillis(),
            )
        }
    }

    fun loadRouteShape(shapeId: String) {
        viewModelScope.launch {
            val shape = obaRepo.getRouteShape(shapeId)
            _uiState.value = _uiState.value.copy(routeShape = shape)
        }
    }

    /**
     * Called when the user taps an arrival row in the bottom sheet.
     * Highlights the route shape and remembers which vehicle is focused
     * so the map can animate the camera to it.
     */
    fun focusVehicle(arrival: ObaArrival) {
        _uiState.value = _uiState.value.copy(focusedVehicle = arrival)
        loadShapeForVehicle(arrival.tripId, arrival.status)
    }

    /** Called when a bus vehicle marker is tapped on the map. */
    fun loadShapeForVehicle(tripId: String, status: ArrivalStatus) {
        _uiState.value = _uiState.value.copy(
            routeShape = emptyList(),
            selectedArrivalStatus = status,
        )
        viewModelScope.launch {
            val shape = obaRepo.getShapeForTrip(tripId)
            _uiState.value = _uiState.value.copy(routeShape = shape)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun toggleSaved(stop: ObaStop) {
        viewModelScope.launch {
            val prefs = ServiceLocator.preferences
            prefs.toggleSavedStop(
                SavedStop(stop.id, stop.name, stop.code, stop.lat, stop.lon),
                savedStops.value,
            )
        }
    }

    // ── Unified "Where to?" search ────────────────────────────────────────────

    fun setSearchActive(active: Boolean) {
        _uiState.value = _uiState.value.copy(
            searchActive = active,
            searchQuery = if (!active) "" else _uiState.value.searchQuery,
            searchResults = if (!active) emptyList() else _uiState.value.searchResults,
            searchLoading = false,
        )
        if (!active) searchJob?.cancel()
    }


    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), searchLoading = false)
            return
        }
        _uiState.value = _uiState.value.copy(searchLoading = true)
        searchJob = viewModelScope.launch {
            delay(250)
            val state = _uiState.value
            val q = query.lowercase()

            // 1. Filter nearby stops whose name matches the query
            val stopHits = state.stops
                .filter { stop -> stop.name.lowercase().contains(q) }
                .take(4)
                .map { HomeSearchResult.StopResult(it) }

            // 2. Fetch routes whose short/long name matches from the OBA API
            val focusLat = state.userLocation?.latitude ?: state.activeRegion?.centerLat ?: 0.0
            val focusLon = state.userLocation?.longitude ?: state.activeRegion?.centerLon ?: 0.0
            val routeHits = runCatching {
                obaRepo.getRoutesForLocation(query, focusLat, focusLon)
            }.getOrElse { emptyList() }
                // Also match in-memory stop routeIds in case API returns nothing
                .let { apiRoutes ->
                    if (apiRoutes.isNotEmpty()) apiRoutes
                    else {
                        // Fallback: collect unique routeIds from visible stops that match query
                        state.stops
                            .flatMap { it.routeIds }
                            .distinct()
                            .filter { it.lowercase().contains(q) }
                            .map { ObaRoute(id = it, shortName = it, longName = "", description = "", agencyId = "") }
                    }
                }
                .take(5)
                .map { HomeSearchResult.RouteResult(it) }

            // 3. Geocode places, constrained to the active region's bounding box
            val bbox = state.activeRegion?.outerBoundingBox()
            val placeHits = runCatching {
                coroutineScope {
                    val deferred = async {
                        geocodingRepo.searchPlaces(
                            query,
                            focusLat = state.userLocation?.latitude ?: state.activeRegion?.centerLat,
                            focusLon = state.userLocation?.longitude ?: state.activeRegion?.centerLon,
                            boundMinLat = bbox?.get(0),
                            boundMaxLat = bbox?.get(1),
                            boundMinLon = bbox?.get(2),
                            boundMaxLon = bbox?.get(3),
                        )
                    }
                    deferred.await()
                }
            }.getOrElse { emptyList() }
                .take(5)
                .map { HomeSearchResult.DestResult(it) }

            _uiState.value = _uiState.value.copy(
                searchResults = stopHits + routeHits + placeHits,
                searchLoading = false,
            )
        }
    }

    /** Highlight a route on the map — filters visible stop markers to those serving this route. */
    fun selectRoute(route: ObaRoute) {
        setSearchActive(false)
        _uiState.value = _uiState.value.copy(
            routeHighlight         = route,
            routeHighlightShape    = emptyList(),
            routeHighlightVehicles = emptyList(),
        )
        viewModelScope.launch {
            // Fetch shape and live vehicles concurrently
            val shapeDeferred   = async { obaRepo.getShapeForRoute(route.id) }
            val vehiclesDeferred = async { obaRepo.getVehiclesForRoute(route.id) }
            _uiState.value = _uiState.value.copy(
                routeHighlightShape    = shapeDeferred.await(),
                routeHighlightVehicles = vehiclesDeferred.await(),
            )
        }
    }

    fun clearRouteHighlight() {
        _uiState.value = _uiState.value.copy(
            routeHighlight         = null,
            routeHighlightShape    = emptyList(),
            routeHighlightVehicles = emptyList(),
        )
    }

    // legacy – kept for compat with old search sheet code
    fun searchDestination(query: String) = onSearchQueryChanged(query)
    fun clearDestinationSearch() = setSearchActive(false)

    override fun onCleared() {
        arrivalsPollJob?.cancel()
        super.onCleared()
    }
}

class HomeViewModelFactory : ViewModelProvider.AndroidViewModelFactory(
    ServiceLocator.application,
)
