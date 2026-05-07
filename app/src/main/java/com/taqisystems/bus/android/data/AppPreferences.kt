// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taqisystems.bus.android.data.model.SavedRoute
import com.taqisystems.bus.android.data.model.SavedStop
import com.taqisystems.bus.android.data.model.InboxNotification
import com.taqisystems.bus.android.data.model.ActiveReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kelantanbus_prefs")

class AppPreferences(private val context: Context) {
    private val gson = Gson()

    private object Keys {
        val REGION_ID = stringPreferencesKey("region_id")
        val OBA_BASE_URL = stringPreferencesKey("oba_base_url")
        val OTP_BASE_URL = stringPreferencesKey("otp_base_url")
        val SIDECAR_BASE_URL = stringPreferencesKey("sidecar_base_url")
        val SAVED_STOPS = stringPreferencesKey("saved_stops_json")
        val SAVED_ROUTES = stringPreferencesKey("saved_routes_json")
        val AUTO_DETECT_REGION = booleanPreferencesKey("auto_detect_region")
        val REGION_CENTER_LAT = doublePreferencesKey("region_center_lat")
        val REGION_CENTER_LON = doublePreferencesKey("region_center_lon")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val NOTIFICATIONS = stringPreferencesKey("inbox_notifications_json")
        val ACTIVE_REMINDERS = stringPreferencesKey("active_reminders_json")
        val LAST_REMINDER_MINUTES = intPreferencesKey("last_reminder_minutes")
        /** Map<stopId, List<SavedRoute>> — routes known to serve each stop. */
        val ROUTES_BY_STOP = stringPreferencesKey("routes_by_stop_json")
    }

    val regionId: Flow<String?> = context.dataStore.data.map { it[Keys.REGION_ID] }.distinctUntilChanged()
    val obaBaseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.OBA_BASE_URL] }.distinctUntilChanged()
    val otpBaseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.OTP_BASE_URL] }.distinctUntilChanged()
    /** Base URL of the arrival-reminder sidecar. Null when the current region has no sidecar. */
    val sidecarBaseUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SIDECAR_BASE_URL] }.distinctUntilChanged()
    val autoDetectRegion: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_DETECT_REGION] ?: true }.distinctUntilChanged()
    val regionCenterLat: Flow<Double?> = context.dataStore.data.map { it[Keys.REGION_CENTER_LAT] }.distinctUntilChanged()
    val regionCenterLon: Flow<Double?> = context.dataStore.data.map { it[Keys.REGION_CENTER_LON] }.distinctUntilChanged()
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_COMPLETE] ?: false }.distinctUntilChanged()

    val notifications: Flow<List<InboxNotification>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.NOTIFICATIONS] ?: return@map emptyList()
        runCatching {
            gson.fromJson<List<InboxNotification>>(json, object : TypeToken<List<InboxNotification>>() {}.type)
        }.getOrElse { emptyList() }
            .distinctBy { it.id }   // guard against any persisted duplicates
    }

    val unreadNotificationCount: Flow<Int> = notifications.map { list -> list.count { !it.isRead } }

    /** The last reminder time (in minutes) the user selected. Defaults to 5. */
    val lastReminderMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.LAST_REMINDER_MINUTES] ?: 5 }.distinctUntilChanged()

    val activeReminders: Flow<List<ActiveReminder>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.ACTIVE_REMINDERS] ?: return@map emptyList()
        runCatching {
            gson.fromJson<List<ActiveReminder>>(json, object : TypeToken<List<ActiveReminder>>() {}.type)
        }.getOrElse { emptyList() }
    }

    val savedStops: Flow<List<SavedStop>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.SAVED_STOPS] ?: return@map emptyList()
        runCatching {
            gson.fromJson<List<SavedStop>>(json, object : TypeToken<List<SavedStop>>() {}.type)
        }.getOrElse { emptyList() }
    }

    val savedRoutes: Flow<List<SavedRoute>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.SAVED_ROUTES] ?: return@map emptyList()
        runCatching {
            gson.fromJson<List<SavedRoute>>(json, object : TypeToken<List<SavedRoute>>() {}.type)
        }.getOrElse { emptyList() }
    }

    /** Returns the routes known to serve [stopId], populated from previous arrival loads. */
    fun cachedRoutesForStop(stopId: String): Flow<List<SavedRoute>> =
        context.dataStore.data.map { prefs ->
            val json = prefs[Keys.ROUTES_BY_STOP] ?: return@map emptyList()
            val map = runCatching {
                gson.fromJson<Map<String, List<SavedRoute>>>(
                    json, object : TypeToken<Map<String, List<SavedRoute>>>() {}.type
                )
            }.getOrElse { emptyMap() }
            map[stopId] ?: emptyList()
        }

    suspend fun setRegionId(id: String) {
        context.dataStore.edit { it[Keys.REGION_ID] = id }
    }

    suspend fun setObaBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.OBA_BASE_URL] = url }
    }

    suspend fun setOtpBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.OTP_BASE_URL] = url }
    }

    suspend fun setSidecarBaseUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.SIDECAR_BASE_URL)
            else prefs[Keys.SIDECAR_BASE_URL] = url
        }
    }

    suspend fun setAutoDetectRegion(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_DETECT_REGION] = enabled }
    }

    suspend fun setRegionCenter(lat: Double, lon: Double) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REGION_CENTER_LAT] = lat
            prefs[Keys.REGION_CENTER_LON] = lon
        }
    }

    /** Atomically apply a manually-selected region (disables auto-detect, stores URLs + center). */
    suspend fun selectRegionManually(
        id: String,
        obaUrl: String,
        otpUrl: String?,
        sidecarUrl: String?,
        centerLat: Double,
        centerLon: Double,
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_DETECT_REGION] = false
            prefs[Keys.REGION_ID] = id
            prefs[Keys.OBA_BASE_URL] = obaUrl
            if (otpUrl != null) prefs[Keys.OTP_BASE_URL] = otpUrl
            if (!sidecarUrl.isNullOrBlank()) prefs[Keys.SIDECAR_BASE_URL] = sidecarUrl
            else prefs.remove(Keys.SIDECAR_BASE_URL)
            prefs[Keys.REGION_CENTER_LAT] = centerLat
            prefs[Keys.REGION_CENTER_LON] = centerLon
        }
    }

    suspend fun setRegion(id: String, obaUrl: String, otpUrl: String?, sidecarUrl: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[Keys.REGION_ID] = id
            prefs[Keys.OBA_BASE_URL] = obaUrl
            if (otpUrl != null) prefs[Keys.OTP_BASE_URL] = otpUrl
            if (!sidecarUrl.isNullOrBlank()) prefs[Keys.SIDECAR_BASE_URL] = sidecarUrl
            else prefs.remove(Keys.SIDECAR_BASE_URL)
        }
    }

    suspend fun toggleSavedStop(stop: SavedStop, currentList: List<SavedStop>) {
        val updated = if (currentList.any { it.id == stop.id }) {
            currentList.filter { it.id != stop.id }
        } else {
            currentList + stop
        }
        context.dataStore.edit { it[Keys.SAVED_STOPS] = gson.toJson(updated) }
    }

    suspend fun toggleSavedRoute(route: SavedRoute, currentList: List<SavedRoute>) {
        val updated = if (currentList.any { it.routeId == route.routeId }) {
            currentList.filter { it.routeId != route.routeId }
        } else {
            currentList + route
        }
        context.dataStore.edit { it[Keys.SAVED_ROUTES] = gson.toJson(updated) }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    /** Prepend a new notification to the inbox (most-recent first). Caps at 50 entries. */
    suspend fun addNotification(notification: InboxNotification) {
        context.dataStore.edit { prefs ->
            val current: List<InboxNotification> = runCatching {
                val json = prefs[Keys.NOTIFICATIONS] ?: "[]"
                gson.fromJson<List<InboxNotification>>(json, object : TypeToken<List<InboxNotification>>() {}.type)
            }.getOrElse { emptyList() }
            val updated = (listOf(notification) + current.filter { it.id != notification.id }).take(50)
            prefs[Keys.NOTIFICATIONS] = gson.toJson(updated)
        }
    }

    /** Mark all notifications as read. */
    suspend fun markAllNotificationsRead() {
        context.dataStore.edit { prefs ->
            val current: List<InboxNotification> = runCatching {
                val json = prefs[Keys.NOTIFICATIONS] ?: return@edit
                gson.fromJson<List<InboxNotification>>(json, object : TypeToken<List<InboxNotification>>() {}.type)
            }.getOrElse { return@edit }
            prefs[Keys.NOTIFICATIONS] = gson.toJson(current.map { it.copy(isRead = true) })
        }
    }

    /** Delete all notifications. */
    suspend fun clearAllNotifications() {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = "[]" }
    }

    /** Delete a single notification by id. */
    suspend fun deleteNotification(id: String) {
        context.dataStore.edit { prefs ->
            val current: List<InboxNotification> = runCatching {
                val json = prefs[Keys.NOTIFICATIONS] ?: return@edit
                gson.fromJson<List<InboxNotification>>(json, object : TypeToken<List<InboxNotification>>() {}.type)
            }.getOrElse { return@edit }
            prefs[Keys.NOTIFICATIONS] = gson.toJson(current.filter { it.id != id })
        }
    }

    /** Add a new active reminder (or overwrite if same tripId). */
    suspend fun addActiveReminder(reminder: ActiveReminder) {
        context.dataStore.edit { prefs ->
            val current: List<ActiveReminder> = runCatching {
                val json = prefs[Keys.ACTIVE_REMINDERS] ?: "[]"
                gson.fromJson<List<ActiveReminder>>(json, object : TypeToken<List<ActiveReminder>>() {}.type)
            }.getOrElse { emptyList() }
            val updated = current.filter { it.tripId != reminder.tripId } + reminder
            prefs[Keys.ACTIVE_REMINDERS] = gson.toJson(updated)
        }
    }

    /** Remove the active reminder for the given tripId. */
    suspend fun removeActiveReminder(tripId: String) {
        context.dataStore.edit { prefs ->
            val current: List<ActiveReminder> = runCatching {
                val json = prefs[Keys.ACTIVE_REMINDERS] ?: return@edit
                gson.fromJson<List<ActiveReminder>>(json, object : TypeToken<List<ActiveReminder>>() {}.type)
            }.getOrElse { return@edit }
            prefs[Keys.ACTIVE_REMINDERS] = gson.toJson(current.filter { it.tripId != tripId })
        }
    }

    /** Persist the last reminder time selection so it is pre-selected next time. */
    suspend fun setLastReminderMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.LAST_REMINDER_MINUTES] = minutes }
    }

    /** Persist the set of routes that serve [stopId] (extracted from arrivals). */
    suspend fun cacheRoutesForStop(stopId: String, routes: List<SavedRoute>) {
        context.dataStore.edit { prefs ->
            val json = prefs[Keys.ROUTES_BY_STOP]
            val map: MutableMap<String, List<SavedRoute>> = runCatching {
                gson.fromJson<MutableMap<String, List<SavedRoute>>>(
                    json, object : TypeToken<MutableMap<String, List<SavedRoute>>>() {}.type
                )
            }.getOrElse { null }?.toMutableMap() ?: mutableMapOf()
            map[stopId] = routes
            prefs[Keys.ROUTES_BY_STOP] = gson.toJson(map)
        }
    }

}
