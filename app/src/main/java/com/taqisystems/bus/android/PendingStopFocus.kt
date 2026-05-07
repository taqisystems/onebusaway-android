// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reactive handoff for "open this stop on the map" deep-links.
 * Set from RemindersScreen; collected by HomeMapScreen.
 * lat/lon are 0.0 when unknown (fallback to network lookup).
 */
data class StopFocusTarget(val id: String, val name: String, val lat: Double, val lon: Double)

object PendingStopFocus {
    private val _flow = MutableStateFlow<StopFocusTarget?>(null)
    val flow: StateFlow<StopFocusTarget?> = _flow

    fun set(id: String, name: String, lat: Double = 0.0, lon: Double = 0.0) {
        if (id.isNotBlank()) _flow.value = StopFocusTarget(id, name, lat, lon)
    }

    fun clear() { _flow.value = null }
}
