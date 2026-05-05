package com.taqisystems.bus.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ActiveReminder
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.data.model.SavedStop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class StopDetailsUiState(
    val arrivals: List<ObaArrival> = emptyList(),
    /** Routes known to serve this stop, populated from cached previous arrivals. Shown when arrivals list is empty. */
    val knownRoutes: List<SavedRoute> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val lastUpdated: Long? = null,
    val error: String? = null,
    // ── Reminder feature ──────────────────────────────────────────────────────
    /** True when the current region has a sidecar URL configured. */
    val sidecarEnabled: Boolean = false,
    /** Which arrival the reminder bottom sheet is currently open for. */
    val reminderSheetArrival: ObaArrival? = null,
    /** Feedback message to show in a snackbar (set reminder / cancelled / error). */
    val reminderMessage: String? = null,
    /** True while a reminder HTTP call is in flight (shows loading on the bell). */
    val reminderLoading: Boolean = false,
    /** Holds the last cancelled reminder so the snackbar Undo action can restore it. */
    val lastCancelledReminder: ActiveReminder? = null,
)

class StopDetailsViewModel : ViewModel() {
    private val obaRepo      = ServiceLocator.obaRepository
    private val regionsRepo  = ServiceLocator.regionsRepository
    private val prefs        = ServiceLocator.preferences
    private val reminderRepo = ServiceLocator.reminderRepository

    private val _uiState = MutableStateFlow(StopDetailsUiState())
    val uiState: StateFlow<StopDetailsUiState> = _uiState.asStateFlow()

    val savedStops: StateFlow<List<SavedStop>> = prefs.savedStops
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedRoutes: StateFlow<List<SavedRoute>> = prefs.savedRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Active reminders for this device — used to show bell as filled/active. */
    val activeReminders: StateFlow<List<ActiveReminder>> = prefs.activeReminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pollJob: Job? = null
    private var cacheJob: Job? = null
    private var currentStopId: String? = null
    private var currentStopName: String = ""

    init {
        // Resolve sidecarEnabled by combining the stored key with the regions cache.
        // The DataStore key may not be populated on first launch, so fall back to
        // looking up the current region by id in the cached regions list.
        viewModelScope.launch {
            combine(prefs.sidecarBaseUrl, prefs.regionId) { storedUrl, regionId ->
                storedUrl to regionId
            }.collect { (storedUrl, regionId) ->
                val sidecarUrl = when {
                    !storedUrl.isNullOrBlank() -> storedUrl
                    regionId != null -> {
                        // DataStore key missing — look up the region from the cache
                        val regions = runCatching { regionsRepo.fetchRegions() }.getOrElse { emptyList() }
                        val region = regions.find { it.id.toString() == regionId }
                        region?.sidecarBaseUrl
                    }
                    else -> null
                }
                _uiState.value = _uiState.value.copy(sidecarEnabled = !sidecarUrl.isNullOrBlank())
            }
        }
    }

    fun load(stopId: String, stopName: String = "") {
        if (stopId == currentStopId) return
        currentStopId = stopId
        if (stopName.isNotBlank()) currentStopName = stopName
        pollJob?.cancel()
        cacheJob?.cancel()
        // Load previously cached routes for this stop immediately (shown when arrivals are empty)
        cacheJob = viewModelScope.launch {
            prefs.cachedRoutesForStop(stopId).collect { routes ->
                _uiState.value = _uiState.value.copy(knownRoutes = routes)
            }
        }
        fetchArrivals(stopId, silent = false)
        startPolling(stopId)
    }

    private fun fetchArrivals(stopId: String, silent: Boolean) {
        viewModelScope.launch {
            if (!silent) _uiState.value = _uiState.value.copy(loading = true, error = null)
            else _uiState.value = _uiState.value.copy(refreshing = true)
            val result = runCatching { obaRepo.getArrivalsForStop(stopId) }
            result.onSuccess { arrivals ->
                _uiState.value = _uiState.value.copy(
                    arrivals = arrivals,
                    lastUpdated = System.currentTimeMillis(),
                    error = null,
                )
                // Cache the routes serving this stop so they can be shown when there are no live arrivals
                if (arrivals.isNotEmpty()) {
                    val routes = arrivals.distinctBy { it.routeId }.map { a ->
                        SavedRoute(
                            routeId   = a.routeId,
                            tripId    = a.tripId,
                            shortName = a.routeShortName,
                            longName  = a.routeLongName,
                            headsign  = a.tripHeadsign,
                        )
                    }
                    runCatching { prefs.cacheRoutesForStop(stopId, routes) }
                }
            }.onFailure { e ->
                if (!silent) _uiState.value = _uiState.value.copy(error = e.message)
            }
            _uiState.value = _uiState.value.copy(loading = false, refreshing = false)
        }
    }

    private fun startPolling(stopId: String) {
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                fetchArrivals(stopId, silent = true)
            }
        }
    }

    fun refresh() {
        currentStopId?.let { fetchArrivals(it, silent = true) }
    }

    fun toggleSaved(stop: SavedStop) {
        viewModelScope.launch { prefs.toggleSavedStop(stop, savedStops.value) }
    }

    fun toggleSaved(stopId: String, stopName: String, stopCode: String) {
        toggleSaved(SavedStop(stopId, stopName, stopCode))
    }

    fun toggleSavedRoute(route: SavedRoute) {
        viewModelScope.launch { prefs.toggleSavedRoute(route, savedRoutes.value) }
    }

    // ── Reminder feature ──────────────────────────────────────────────────────

    /** Open the "Set Reminder" bottom sheet for an arrival. */
    fun openReminderSheet(arrival: ObaArrival) {
        _uiState.value = _uiState.value.copy(reminderSheetArrival = arrival)
    }

    /** Close the bottom sheet without making any changes. */
    fun closeReminderSheet() {
        _uiState.value = _uiState.value.copy(reminderSheetArrival = null)
    }

    /**
     * Register a new arrival reminder with the sidecar.
     *
     * @param arrival        The arrival the user wants to be reminded about
     * @param minutesBefore  How many minutes before the bus arrives to push the notification
     */
    fun setReminder(arrival: ObaArrival, minutesBefore: Int) {
        viewModelScope.launch {
            val sidecarUrl = prefs.sidecarBaseUrl.first()
            val regionId   = prefs.regionId.first()
            val stopId     = currentStopId

            if (sidecarUrl.isNullOrBlank() || regionId == null || stopId == null) {
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
                stopId         = stopId,
                stopName       = currentStopName,
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
                    stopName       = currentStopName,
                    arrivalEpochMs = if (arrival.predicted) arrival.predictedArrivalTime else arrival.scheduledArrivalTime,
                    stopId         = currentStopId ?: "",
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

    /**
     * Cancel an existing reminder for the given arrival.
     * Local state is cleared optimistically; the HTTP DELETE is best-effort.
     */
    fun cancelReminder(arrival: ObaArrival) {
        viewModelScope.launch {
            val reminder = activeReminders.value.find { it.tripId == arrival.tripId }
                ?: return@launch

            // Optimistic removal so the bell resets immediately
            prefs.removeActiveReminder(arrival.tripId)
            _uiState.value = _uiState.value.copy(
                reminderSheetArrival = null,
                reminderMessage = "Reminder cancelled.",
                lastCancelledReminder = reminder,
            )

            // Best-effort DELETE — don't surface errors to the user
            reminderRepo.cancelReminder(reminder)
        }
    }

    /** Restore the last cancelled reminder (called when user taps Undo in snackbar). */
    fun undoCancelReminder() {
        val reminder = _uiState.value.lastCancelledReminder ?: return
        viewModelScope.launch {
            prefs.addActiveReminder(reminder)
            _uiState.value = _uiState.value.copy(
                lastCancelledReminder = null,
                reminderMessage = null,
            )
        }
    }

    /** Clear the one-shot snackbar message after it has been shown. */
    fun clearReminderMessage() {
        _uiState.value = _uiState.value.copy(reminderMessage = null, lastCancelledReminder = null)
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

class StopDetailsViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return StopDetailsViewModel() as T
    }
}
