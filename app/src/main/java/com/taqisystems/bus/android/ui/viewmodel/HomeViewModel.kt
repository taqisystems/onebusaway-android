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
import com.taqisystems.bus.android.data.model.ActiveReminder
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.ObaRoute
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.SavedStop
import com.taqisystems.bus.android.data.model.PlaceResult
import com.taqisystems.bus.android.data.model.RoutePoint
import com.taqisystems.bus.android.data.model.OverviewRoute
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

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
    // vehicleId of the trip pinned to top of the arrivals list — survives map taps,
    // cleared only when a new stop is opened
    val pinnedTripVehicleId: String? = null,
    // One-shot: set when deep-linking from Reminders so the camera flies to the stop
    val cameraFocusStop: ObaStop? = null,
    // One-shot message shown when the user switches regions in Settings
    val regionSwitchMessage: String? = null,
    // Location fetch state
    val isLocatingUser: Boolean = false,
    val locationError: String? = null,
    // Last known map camera position — persisted across bottom-tab navigation
    val lastCameraLat: Double = 0.0,
    val lastCameraLon: Double = 0.0,
    val lastCameraZoom: Float = 0f,
    // ── Overview route coverage layer ─────────────────────────────────────────
    /** Polylines for every route in the region, loaded lazily when zoom < 13. */
    val overviewRoutes: List<OverviewRoute> = emptyList(),
    /** True once overview shapes have been fetched for the current region. */
    val overviewRoutesLoaded: Boolean = false,
    // ── Reminder feature ──────────────────────────────────────────────────────
    /** True when the current region has a sidecar URL configured. */
    val sidecarEnabled: Boolean = false,
    /** Which arrival the reminder bottom sheet is currently open for. */
    val reminderSheetArrival: ObaArrival? = null,
    /** Feedback message to show in a snackbar. */
    val reminderMessage: String? = null,
    /** True while a reminder HTTP call is in flight. */
    val reminderLoading: Boolean = false,
    /** Holds the last cancelled reminder so Undo can restore it. */
    val lastCancelledReminder: ActiveReminder? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val obaRepo = ServiceLocator.obaRepository
    private val regionsRepo = ServiceLocator.regionsRepository
    private val geocodingRepo = ServiceLocator.geocodingRepository
    private val reminderRepo = ServiceLocator.reminderRepository
    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)
    private var searchJob: Job? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val savedStops: StateFlow<List<SavedStop>> = ServiceLocator.preferences.savedStops
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Active reminders — used to render the bell as filled/active in ArrivalRow. */
    val activeReminders: StateFlow<List<ActiveReminder>> = ServiceLocator.preferences.activeReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var arrivalsPollJob: Job? = null

    /** True after the camera has auto-centered on the user's location for the first time. */
    var hasCenteredOnLocation: Boolean = false
        private set

    /** True when the My Location FAB was explicitly tapped — bypasses autoDetect guard. */
    var forceCenterOnLocation: Boolean = false
        private set

    /** Persist camera position so it can be restored when returning to HomeMapScreen. */
    fun saveCameraPosition(lat: Double, lon: Double, zoom: Float) {
        _uiState.value = _uiState.value.copy(
            lastCameraLat = lat,
            lastCameraLon = lon,
            lastCameraZoom = zoom,
        )
    }

    fun markCameracentered() {
        hasCenteredOnLocation = true
        forceCenterOnLocation = false
    }

    init {
        // Mirror sidecarEnabled so the bell shows only when the region supports reminders
        ServiceLocator.preferences.sidecarBaseUrl
            .onEach { url -> _uiState.value = _uiState.value.copy(sidecarEnabled = !url.isNullOrBlank()) }
            .launchIn(viewModelScope)

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
                prefs.setSidecarBaseUrl(region?.sidecarBaseUrl)
                loadStopsForLocation(savedCenterLat, savedCenterLon)
                loadOverviewRoutes()
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
                .debounce(150) // collapse burst emissions from atomic DataStore writes
                .collect { arr ->
                    val autoDetect = arr[0] as Boolean
                    val regionId   = arr[1] as? String
                    val lat        = arr[2] as? Double
                    val lon        = arr[3] as? Double
                    if (!autoDetect && lat != null && lon != null) {
                        hasCenteredOnLocation = false
                        // Cancel arrival polling and clear the selected stop so the sheet
                        // hides immediately — poll job must be cancelled before the copy so
                        // it can't repopulate arrivals for the old stop after we clear it.
                        selectStop(null)
                        _uiState.value = _uiState.value.copy(
                            stops = emptyList(),
                            routeHighlight = null,
                            routeHighlightShape = emptyList(),
                            routeHighlightVehicles = emptyList(),
                            focusedVehicle = null,
                            // Reset overview so it reloads for the new region
                            overviewRoutes = emptyList(),
                            overviewRoutesLoaded = false,
                        )
                        // Refresh activeRegion so search/geocoding uses the new region's bbox
                        val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
                        val region = regions.find { it.id.toString() == regionId }
                        val resolvedRegion = region ?: _uiState.value.activeRegion
                        _uiState.value = _uiState.value.copy(
                            pendingCameraCenter = Pair(lat, lon),
                            activeRegion = resolvedRegion,
                            regionSwitchMessage = resolvedRegion?.regionName?.let { "You're now in $it" },
                        )
                        prefs.setSidecarBaseUrl(resolvedRegion?.sidecarBaseUrl)
                        loadOverviewRoutes()
                    } else if (autoDetect) {
                        // User switched back to auto-detect: clear map state and re-detect
                        // the region from the device GPS position.
                        hasCenteredOnLocation = false
                        selectStop(null)
                        _uiState.value = _uiState.value.copy(
                            stops = emptyList(),
                            routeHighlight = null,
                            routeHighlightShape = emptyList(),
                            routeHighlightVehicles = emptyList(),
                            focusedVehicle = null,
                            overviewRoutes = emptyList(),
                            overviewRoutesLoaded = false,
                        )
                        fetchUserLocation(recenterCamera = true)
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
                    loadFallbackRegion("Couldn't get your location; showing Kuala Lumpur")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLocatingUser = false,
                    locationError = "Couldn't get your location. Try again.",
                )
                loadFallbackRegion("Couldn't get your location; showing Kuala Lumpur")
            }
        }
    }

    fun clearLocationError() {
        _uiState.value = _uiState.value.copy(locationError = null)
    }

    private fun loadRegionAndStops(lat: Double, lon: Double) {
        viewModelScope.launch {
            val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
            val detectedRegion = regionsRepo.findRegionForLocation(regions, lat, lon)
            val prefs = ServiceLocator.preferences
            val savedRegionId = prefs.regionId.first()?.toIntOrNull()
            val fallbackRegion = preferredFallbackRegion(regions)
                ?: regions.firstOrNull { it.id == savedRegionId }
                ?: _uiState.value.activeRegion
                ?: regions.firstOrNull()
            val region = detectedRegion ?: fallbackRegion
            val previousRegion = _uiState.value.activeRegion
            val switchedMessage = if (
                detectedRegion == null &&
                region != null
            ) "You're outside the service area; showing ${region.regionName}" else if (
                region != null &&
                previousRegion != null &&
                region.id != previousRegion.id
            ) "You're now in ${region.regionName}" else null
            val targetLat = if (detectedRegion != null) lat else region?.centerLat ?: lat
            val targetLon = if (detectedRegion != null) lon else region?.centerLon ?: lon
            _uiState.value = _uiState.value.copy(
                activeRegion = region,
                regionSwitchMessage = switchedMessage,
                pendingCameraCenter = if (detectedRegion == null && region != null) {
                    Pair(targetLat, targetLon)
                } else {
                    _uiState.value.pendingCameraCenter
                },
            )
            // Persist sidecarBaseUrl so StopDetailsViewModel can gate the reminder feature
            if (region != null) {
                ServiceLocator.applyRegionUrls(region.obaBaseUrl)
                prefs.setRegion(region.id.toString(), region.obaBaseUrl, region.otpBaseUrl, region.sidecarBaseUrl)
                prefs.setRegionCenter(region.centerLat, region.centerLon)
            } else {
                prefs.setSidecarBaseUrl(null)
            }
            if (region?.id != previousRegion?.id) {
                _uiState.value = _uiState.value.copy(overviewRoutes = emptyList(), overviewRoutesLoaded = false)
                loadOverviewRoutes()
            }
            loadStopsForLocation(targetLat, targetLon)
        }
    }

    private fun loadFallbackRegion(message: String? = null) {
        viewModelScope.launch {
            val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
            val region = preferredFallbackRegion(regions) ?: regions.firstOrNull()
            if (region == null) {
                _uiState.value = _uiState.value.copy(loading = false)
                return@launch
            }
            val targetLat = region.centerLat.takeIf { it != 0.0 } ?: KUALA_LUMPUR_LAT
            val targetLon = region.centerLon.takeIf { it != 0.0 } ?: KUALA_LUMPUR_LON
            _uiState.value = _uiState.value.copy(
                activeRegion = region,
                regionSwitchMessage = message ?: "Showing ${region.regionName}",
                pendingCameraCenter = Pair(targetLat, targetLon),
                isLocatingUser = false,
            )
            val prefs = ServiceLocator.preferences
            ServiceLocator.applyRegionUrls(region.obaBaseUrl)
            prefs.setRegion(region.id.toString(), region.obaBaseUrl, region.otpBaseUrl, region.sidecarBaseUrl)
            prefs.setRegionCenter(region.centerLat, region.centerLon)
            loadOverviewRoutes()
            loadStopsForLocation(targetLat, targetLon)
        }
    }

    private fun preferredFallbackRegion(regions: List<ObaRegion>): ObaRegion? =
        regions.firstOrNull { region ->
            region.regionName.contains("Kuala", ignoreCase = true) &&
                region.regionName.contains("Lumpur", ignoreCase = true)
        } ?: regions.firstOrNull { region ->
            region.regionName.contains("KL", ignoreCase = true) ||
                region.regionName.contains("Klang", ignoreCase = true)
        } ?: regions.firstOrNull { !it.sidecarBaseUrl.isNullOrBlank() }

    fun loadStopsForLocation(lat: Double, lon: Double, zoom: Float = 15f) {
        // Don't bother fetching when too far zoomed out — markers are hidden anyway.
        if (zoom < 13f) {
            _uiState.value = _uiState.value.copy(stops = emptyList(), loading = false)
            return
        }
        // Adapt the search radius to how much of the map is visible.
        // Tighter zoom → smaller radius keeps marker count manageable (~30-60).
        val radius = when {
            zoom >= 16f -> 600
            zoom >= 15f -> 900
            zoom >= 14f -> 1400
            else        -> 2200   // zoom 13–14
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val stops = runCatching { obaRepo.getStopsForLocation(lat, lon, radius) }
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
            pinnedTripVehicleId = null,
        )
        if (stop != null) {
            arrivalsPollJob = viewModelScope.launch {
                // Initial load
                fetchArrivals(stop.id, silent = false)
                // Poll every 30 s — inline so there is never more than one
                // in-flight request and the poll clock resets after each response.
                while (isActive) {
                    delay(30_000)
                    fetchArrivals(stop.id, silent = true)
                }
            }
        }
    }

    /** Called when the app returns to the foreground with a stop already selected. */
    fun refreshArrivalsIfStale() {
        val stopId = _uiState.value.selectedStop?.id ?: return
        val lastUpdated = _uiState.value.arrivalsLastUpdated ?: 0L
        val staleSec = (System.currentTimeMillis() - lastUpdated) / 1000L
        // If more than one full poll interval has elapsed, refresh immediately
        // (mirrors OBA Android's ArrivalsListFragment.onStart() stale-check)
        if (staleSec >= 30) {
            arrivalsPollJob?.cancel()
            arrivalsPollJob = viewModelScope.launch {
                fetchArrivals(stopId, silent = true)
                while (isActive) {
                    delay(30_000)
                    fetchArrivals(stopId, silent = true)
                }
            }
        }
    }

    /**
     * Inline (non-coroutine-launching) arrivals fetch used by the poll loop.
     * Unlike the old [loadArrivals], this runs directly inside the caller's
     * coroutine so there is never more than one request in flight at a time.
     *
     * On error the existing arrival list is preserved (mirrors OBA Android's
     * ArrivalsListLoader mLastGoodResponse pattern) and one automatic retry
     * is attempted after a short delay.
     */
    private suspend fun fetchArrivals(stopId: String, silent: Boolean) {
        if (!silent) _uiState.value = _uiState.value.copy(arrivalsLoading = true)
        val result = runCatching { obaRepo.getArrivalsForStop(stopId) }
        if (result.isSuccess) {
            val fresh = result.getOrThrow()
            val existing = _uiState.value.arrivals
            // Mirror OBA's mLastGoodResponse: during a silent refresh never replace
            // a non-empty list with an empty one (server transient empty / brief hiccup).
            // Only accept empty on an explicit (non-silent) load, i.e. stop first selected.
            val nextArrivals = if (fresh.isEmpty() && silent && existing.isNotEmpty()) existing else fresh
            _uiState.value = _uiState.value.copy(
                arrivals = nextArrivals,
                arrivalsLoading = false,
                arrivalsLastUpdated = System.currentTimeMillis(),
            )
        } else {
            // Network/timeout error — keep the last good list visible and retry once
            _uiState.value = _uiState.value.copy(arrivalsLoading = false)
            delay(5_000)
            val retry = runCatching { obaRepo.getArrivalsForStop(stopId) }
            if (retry.isSuccess) {
                val fresh = retry.getOrThrow()
                val existing = _uiState.value.arrivals
                val nextArrivals = if (fresh.isEmpty() && existing.isNotEmpty()) existing else fresh
                _uiState.value = _uiState.value.copy(
                    arrivals = nextArrivals,
                    arrivalsLastUpdated = System.currentTimeMillis(),
                )
            }
            // If retry also fails, existing arrivals remain visible unchanged.
        }
    }

    fun clearSelectedStop() = selectStop(null)

    /**
     * Deep-link entry point: fetch the stop's lat/lon from the OBA API, then
     * select it so the bottom sheet opens with live arrivals.
     */
    fun focusStopById(stopId: String, stopName: String, stopLat: Double = 0.0, stopLon: Double = 0.0) {
        if (stopId.isBlank()) return
        // Optimistically select a stub stop immediately so the sheet starts loading
        selectStop(ObaStop(id = stopId, name = stopName, lat = stopLat, lon = stopLon))
        // If we already have valid coords, set cameraFocusStop immediately.
        if (stopLat != 0.0 || stopLon != 0.0) {
            val stop = ObaStop(id = stopId, name = stopName, lat = stopLat, lon = stopLon)
            _uiState.value = _uiState.value.copy(cameraFocusStop = stop)
            return
        }
        // Fallback: fetch real coords from network.
        viewModelScope.launch {
            val stop = obaRepo.getStopLocation(stopId)
            if (stop != null) {
                _uiState.value = _uiState.value.copy(
                    selectedStop    = stop,
                    cameraFocusStop = stop,
                )
            }
        }
    }

    fun clearCameraFocusStop() {
        _uiState.value = _uiState.value.copy(cameraFocusStop = null)
    }

    /** Still public for external one-off triggers (e.g. pull-to-refresh). */
    fun loadArrivals(stopId: String, silent: Boolean = false) {
        viewModelScope.launch { fetchArrivals(stopId, silent) }
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
        _uiState.value = _uiState.value.copy(
            focusedVehicle = arrival,
            pinnedTripVehicleId = arrival.vehicleId,
        )
        loadShapeForVehicle(arrival.tripId, arrival.status)
    }

    fun clearFocusedVehicle() {
        _uiState.value = _uiState.value.copy(focusedVehicle = null)
    }

    fun unpinTrip() {
        _uiState.value = _uiState.value.copy(
            pinnedTripVehicleId = null,
            focusedVehicle = null,
            routeShape = emptyList(),
            selectedArrivalStatus = null,
        )
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

    /**
     * Lazily loads the polyline shapes for every route in the current region.
     * Called when the user zooms out below zoom 13. Results are cached —
     * subsequent calls while [HomeUiState.overviewRoutesLoaded] is true are no-ops.
     */
    fun loadOverviewRoutes() {
        if (_uiState.value.overviewRoutesLoaded) return
        viewModelScope.launch {
            val prefs     = ServiceLocator.preferences
            val sidecarUrl = prefs.sidecarBaseUrl.first()
                ?: _uiState.value.activeRegion?.sidecarBaseUrl
            val regionId  = prefs.regionId.first()?.toString() ?: "0"

            val cacheFile = File(
                getApplication<Application>().filesDir,
                "route_shapes_$regionId.geojson"
            )

            // ── 1. Serve from cache if available ─────────────────────────────
            if (cacheFile.exists()) {
                val cached = runCatching { parseGeoJsonShapes(cacheFile.readText()) }.getOrNull()
                if (!cached.isNullOrEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        overviewRoutes = cached,
                        overviewRoutesLoaded = true,
                    )
                    // Refresh in background if the cache is older than 7 days
                    val staleMs = 7L * 24 * 3600 * 1000
                    if (!sidecarUrl.isNullOrBlank() &&
                        System.currentTimeMillis() - cacheFile.lastModified() > staleMs
                    ) {
                        refreshRouteShapesFromSidecar(sidecarUrl, regionId, cacheFile, updateUi = false)
                    }
                    return@launch
                }
            }

            // ── 2. No usable cache — download from sidecar if configured ─────
            if (!sidecarUrl.isNullOrBlank()) {
                refreshRouteShapesFromSidecar(sidecarUrl, regionId, cacheFile, updateUi = true)
            }
            // If no sidecar, silently leave overviewRoutesLoaded = false so
            // the user sees the map without overview polylines.
        }
    }

    private suspend fun refreshRouteShapesFromSidecar(
        sidecarUrl: String,
        regionId: String,
        cacheFile: File,
        updateUi: Boolean,
    ) {
        val url = sidecarUrl.trimEnd('/') +
                "/api/v1/regions/$regionId/routes.geojson"
        val json = runCatching { obaRepo.downloadText(url) }.getOrNull() ?: return
        runCatching { cacheFile.writeText(json) }
        val shapes = runCatching { parseGeoJsonShapes(json) }.getOrNull() ?: return
        if (updateUi && shapes.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                overviewRoutes = shapes,
                overviewRoutesLoaded = true,
            )
        }
    }

    /** Parses a GeoJSON FeatureCollection into a list of OverviewRoutes (polyline + short name). */
    private fun parseGeoJsonShapes(json: String): List<OverviewRoute> {
        val root     = JSONObject(json)
        val features = root.getJSONArray("features")
        val result   = mutableListOf<OverviewRoute>()
        for (i in 0 until features.length()) {
            val feat   = features.getJSONObject(i)
            val props  = feat.optJSONObject("properties")
            val shortName = props?.optString("shortName") ?: ""
            val geom   = feat.optJSONObject("geometry") ?: continue
            if (geom.optString("type") != "LineString") continue
            val coords = geom.getJSONArray("coordinates")
            val pts    = (0 until coords.length()).map { j ->
                val c = coords.getJSONArray(j)
                RoutePoint(lat = c.getDouble(1), lon = c.getDouble(0)) // GeoJSON is [lon, lat]
            }
            if (pts.size >= 2) result += OverviewRoute(shortName = shortName, points = pts)
        }
        return result
    }

    // legacy – kept for compat with old search sheet code
    fun searchDestination(query: String) = onSearchQueryChanged(query)
    fun clearDestinationSearch() = setSearchActive(false)

    // ── Reminder feature ──────────────────────────────────────────────────────

    fun openReminderSheet(arrival: ObaArrival) {
        _uiState.value = _uiState.value.copy(reminderSheetArrival = arrival)
    }

    fun closeReminderSheet() {
        _uiState.value = _uiState.value.copy(reminderSheetArrival = null)
    }

    fun setReminder(arrival: ObaArrival, minutesBefore: Int) {
        viewModelScope.launch {
            val prefs = ServiceLocator.preferences
            val sidecarUrl = prefs.sidecarBaseUrl.first()
            val regionId   = prefs.regionId.first()
            val stop       = _uiState.value.selectedStop

            if (sidecarUrl.isNullOrBlank() || regionId == null || stop == null) {
                _uiState.value = _uiState.value.copy(
                    reminderSheetArrival = null,
                    reminderMessage = "Reminder service is not available for this region.",
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                reminderLoading = true,
                reminderSheetArrival = null,
            )

            val result = reminderRepo.registerReminder(
                sidecarBaseUrl = sidecarUrl,
                regionId       = regionId,
                stopId         = stop.id,
                stopName       = stop.name,
                arrival        = arrival,
                secondsBefore  = minutesBefore * 60,
            )

            result.onSuccess { deleteUrl ->
                val reminder = ActiveReminder(
                    tripId         = arrival.tripId,
                    deleteUrl      = deleteUrl,
                    minutesBefore  = minutesBefore,
                    sidecarBaseUrl = sidecarUrl,
                    routeShortName = arrival.routeShortName,
                    headsign       = arrival.tripHeadsign.ifBlank { arrival.routeLongName },
                    stopName       = stop.name,
                    arrivalEpochMs = if (arrival.predicted) arrival.predictedArrivalTime else arrival.scheduledArrivalTime,
                    stopId         = stop.id,
                    stopLat        = stop.lat,
                    stopLon        = stop.lon,
                )
                prefs.addActiveReminder(reminder)
                _uiState.value = _uiState.value.copy(
                    reminderLoading = false,
                    reminderMessage = "You'll be notified $minutesBefore min before the bus arrives.",
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    reminderLoading = false,
                    reminderMessage = "Couldn't set reminder: ${e.message}",
                )
            }
        }
    }

    fun cancelReminder(arrival: ObaArrival) {
        viewModelScope.launch {
            val reminder = activeReminders.value.find { it.tripId == arrival.tripId }
                ?: return@launch
            ServiceLocator.preferences.removeActiveReminder(arrival.tripId)
            _uiState.value = _uiState.value.copy(
                reminderSheetArrival = null,
                reminderMessage = "Reminder cancelled.",
                lastCancelledReminder = reminder,
            )
            reminderRepo.cancelReminder(reminder)
        }
    }

    fun undoCancelReminder() {
        val reminder = _uiState.value.lastCancelledReminder ?: return
        viewModelScope.launch {
            ServiceLocator.preferences.addActiveReminder(reminder)
            _uiState.value = _uiState.value.copy(
                lastCancelledReminder = null,
                reminderMessage = null,
            )
        }
    }

    fun clearReminderMessage() {
        _uiState.value = _uiState.value.copy(reminderMessage = null, lastCancelledReminder = null)
    }

    companion object {
        private const val KUALA_LUMPUR_LAT = 3.1390
        private const val KUALA_LUMPUR_LON = 101.6869
    }

    override fun onCleared() {
        arrivalsPollJob?.cancel()
        super.onCleared()
    }
}

class HomeViewModelFactory : ViewModelProvider.AndroidViewModelFactory(
    ServiceLocator.application,
)
