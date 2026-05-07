// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.ui.viewmodel

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.taqisystems.bus.android.ServiceLocator
import com.taqisystems.bus.android.data.model.ObaRegion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.sqrt

data class SettingsUiState(
    val regions: List<ObaRegion> = emptyList(),
    val loading: Boolean = false,
    val autoDetect: Boolean = true,
    val selectedRegionId: Int? = null,
    val detectedRegionId: Int? = null,
    val userLat: Double? = null,
    val userLon: Double? = null,
    val error: String? = null,
)

class SettingsViewModel : ViewModel() {
    private val prefs = ServiceLocator.preferences
    private val regionsRepo = ServiceLocator.regionsRepository

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val autoDetect = prefs.autoDetectRegion.first()
            val regionIdStr = prefs.regionId.first()
            _uiState.value = _uiState.value.copy(
                autoDetect = autoDetect,
                selectedRegionId = regionIdStr?.toIntOrNull(),
            )
            loadRegions()
        }
    }

    private suspend fun loadRegions(forceRefresh: Boolean = false) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        runCatching { regionsRepo.fetchRegions(forceRefresh = forceRefresh) }
            .onSuccess { regions ->
                _uiState.value = _uiState.value.copy(regions = regions, loading = false)
                fetchLocationAndDetect(regions)
            }
            .onFailure { e ->
                _uiState.value = _uiState.value.copy(loading = false, error = e.message)
            }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndDetect(regions: List<ObaRegion>) {
        val fusedClient = LocationServices.getFusedLocationProviderClient(ServiceLocator.application)
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                val detected = regionsRepo.findRegionForLocation(regions, loc.latitude, loc.longitude)
                _uiState.value = _uiState.value.copy(
                    userLat = loc.latitude,
                    userLon = loc.longitude,
                    detectedRegionId = detected?.id,
                )
                // Auto-apply detected region when auto-detect is on
                if (_uiState.value.autoDetect && detected != null) {
                    applyRegion(detected, updateAutoDetect = false)
                }
            }
        }
    }

    fun setAutoDetect(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoDetect = enabled)
        viewModelScope.launch { prefs.setAutoDetectRegion(enabled) }
        if (enabled) {
            // Re-apply detected region if known
            val detected = _uiState.value.regions.find { it.id == _uiState.value.detectedRegionId }
            if (detected != null) applyRegion(detected, updateAutoDetect = false)
        }
    }

    fun selectRegion(region: ObaRegion) {
        // Disable auto-detect when user manually selects a region
        _uiState.value = _uiState.value.copy(autoDetect = false, selectedRegionId = region.id)
        ServiceLocator.applyRegionUrls(region.obaBaseUrl)
        viewModelScope.launch {
            // Atomic write: autoDetect=false + region URLs + center in one DataStore transaction
            // so the combine flow in HomeViewModel fires exactly once with the correct values.
            prefs.selectRegionManually(
                id = region.id.toString(),
                obaUrl = region.obaBaseUrl,
                otpUrl = region.otpBaseUrl,
                sidecarUrl = region.sidecarBaseUrl,
                centerLat = region.centerLat,
                centerLon = region.centerLon,
            )
        }
    }

    private fun applyRegion(region: ObaRegion, updateAutoDetect: Boolean) {
        _uiState.value = _uiState.value.copy(selectedRegionId = region.id)
        ServiceLocator.applyRegionUrls(region.obaBaseUrl)
        viewModelScope.launch {
            prefs.setRegion(
                id = region.id.toString(),
                obaUrl = region.obaBaseUrl,
                otpUrl = region.otpBaseUrl,
                sidecarUrl = region.sidecarBaseUrl,
            )
            // Persist center so HomeMapScreen can fly to this region
            prefs.setRegionCenter(region.centerLat, region.centerLon)
        }
    }

    fun refreshRegions() {
        viewModelScope.launch { loadRegions(forceRefresh = true) }
    }

    /** Sort regions by proximity to the user; if no location known, return as-is (by name). */
    fun sortedRegions(): List<ObaRegion> {
        val state = _uiState.value
        val lat = state.userLat
        val lon = state.userLon
        return if (lat != null && lon != null) {
            state.regions.sortedBy { region ->
                val dlat = region.centerLat - lat
                val dlon = region.centerLon - lon
                sqrt(dlat * dlat + dlon * dlon)
            }
        } else {
            state.regions.sortedBy { it.regionName }
        }
    }
}

class SettingsViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel() as T
    }
}
