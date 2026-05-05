package com.taqisystems.bus.android.data.model

// ─── In-app notification inbox ───────────────────────────────────────────────
data class InboxNotification(
    val id: String,
    val title: String,
    val body: String,
    val receivedAt: Long,       // epoch ms
    val isRead: Boolean = false,
    /** Internal nav route to navigate to when the user taps this notification, e.g. "stop/12345" */
    val deepLink: String? = null,
    /** System notification ID passed to NotificationManagerCompat.notify(); 0 = unknown/old entry */
    val systemNotifId: Int = 0,
)

// ─── Transit Type ───────────────────────────────────────────────────────────
/**
 * Categories of transit mode inferred from GTFS route type integers.
 *
 * Each entry carries a [mapColor] used for stop markers on the map.
 * To adapt for a different country / agency brand, change only these
 * 7 hex values — all logic elsewhere is country-agnostic.
 */
enum class TransitType(val mapColor: Int) {
    /** GTFS type 3 – regular bus (also default fallback) */
    BUS          (0xFFDC2626.toInt()),  // red
    /** GTFS type 0 – light rail / LRT / tram */
    LRT_TRAM     (0xFF9B2335.toInt()),  // ruby
    /** GTFS type 1 – rapid transit / MRT / metro / subway */
    MRT_METRO    (0xFF007C3E.toInt()),  // green
    /** GTFS type 2 – commuter / intercity rail */
    COMMUTER_RAIL(0xFFE35205.toInt()),  // orange
    /** GTFS type 4 – ferry */
    FERRY        (0xFF0369A1.toInt()),  // ocean blue
    /** GTFS type 11 – monorail */
    MONORAIL     (0xFF5CB85C.toInt()),  // light green
    /** Stop served by multiple different non-bus modes */
    MIXED        (0xFF7C3AED.toInt()),  // purple
    ;

    companion object {
        /** Maps a single GTFS route [type] integer to the corresponding [TransitType]. */
        fun fromGtfsType(type: Int): TransitType = when (type) {
            0    -> LRT_TRAM
            1    -> MRT_METRO
            2    -> COMMUTER_RAIL
            4    -> FERRY
            11   -> MONORAIL
            else -> BUS
        }

        /**
         * Given the full set of GTFS types for a stop (one per route serving it),
         * returns the most prominent [TransitType].
         *
         * Rules:
         * - All bus-only → [BUS]
         * - Exactly one non-bus mode → that mode
         * - Multiple different non-bus modes → [MIXED]
         */
        fun fromGtfsTypes(types: Set<Int>): TransitType {
            val modes = types.map { fromGtfsType(it) }.toSet()
            val nonBus = modes.filter { it != BUS }.toSet()
            return when {
                nonBus.isEmpty() -> BUS
                nonBus.size == 1 -> nonBus.first()
                else             -> MIXED
            }
        }
    }
}

// ─── OBA Stop ────────────────────────────────────────────────────────────────
data class ObaStop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val code: String = "",
    val direction: String = "",
    val routeIds: List<String> = emptyList(),
    /** Transit mode inferred from the GTFS types of all routes serving this stop. */
    val transitType: TransitType = TransitType.BUS,
)

// ─── OBA Arrival ─────────────────────────────────────────────────────────────
data class ObaArrival(
    val routeId: String,
    val routeShortName: String,
    val tripId: String,
    val tripHeadsign: String,
    val predictedArrivalTime: Long,
    val scheduledArrivalTime: Long,
    val predicted: Boolean,
    val status: ArrivalStatus,
    val minutesUntilArrival: Int,
    val vehicleId: String?,
    val vehicleLat: Double?,
    val vehicleLon: Double?,
    val vehicleOrientation: Double?,
    val vehicleLastUpdateTime: Long?,
    val shapeId: String?,
    val routeLongName: String = "",
    val deviationMinutes: Int = 0,
    /** True when the trip is frequency/headway-based (frequencyType == 1) */
    val isHeadway: Boolean = false,
    /** Headway interval in seconds (seconds between vehicles), null for normal schedule trips */
    val headwaySecs: Int? = null,
    /** End time of the headway window in epoch ms (frequency.endTime), null when not available */
    val headwayEndTime: Long? = null,
    /** OBA service date (epoch ms of midnight of the operating day). Required for sidecar reminder. */
    val serviceDate: Long = 0L,
    /** Position of this stop in the trip's stop sequence. Required for sidecar reminder. */
    val stopSequence: Int = 0,
    /** Transit mode inferred from the GTFS route type of this arrival's route. */
    val transitType: TransitType = TransitType.BUS,
) {
    /**
     * Recalculates minutes-until-arrival from the stored timestamps at the moment of calling,
     * so the value stays accurate after the phone wakes from sleep without a fresh API fetch.
     */
    fun liveMinutesUntilArrival(): Int {
        val effectiveMs = if (predictedArrivalTime > 0) predictedArrivalTime else scheduledArrivalTime
        return ((effectiveMs - System.currentTimeMillis()) / 60_000).toInt()
    }
}

enum class ArrivalStatus { ON_TIME, DELAYED, EARLY, SCHEDULED, UNKNOWN }

// ─── OBA Route ───────────────────────────────────────────────────────────────
data class ObaRoute(
    val id: String,
    val shortName: String,
    val longName: String,
    val description: String,
    val agencyId: String,
    val color: String? = null,
    val textColor: String? = null,
    val url: String? = null,
    val type: Int = 3,
)

// ─── Trip Details ─────────────────────────────────────────────────────────────
data class TripStop(
    val stopId: String,
    val stopName: String,
    val arrivalTime: Int,   // seconds from midnight
    val departureTime: Int,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
) {
    val name: String get() = stopName
}

data class TripDetails(
    val tripId: String,
    val headsign: String,
    val stops: List<TripStop>,
    val closestStopId: String?,
    val nextStopId: String?,
    val distanceAlongTrip: Double,
    val totalDistance: Double,
    val scheduleDeviation: Double,
    val predicted: Boolean,
    val vehicleId: String?,
)

// ─── OBA Region ─────────────────────────────────────────────────────────────
data class RegionBound(
    val lat: Double,
    val lon: Double,
    val latSpan: Double,
    val lonSpan: Double,
)

data class ObaRegion(
    val id: Int,
    val regionName: String,
    val obaBaseUrl: String,
    val otpBaseUrl: String?,
    /** Base URL of the arrival-reminder sidecar service. Null = feature disabled for this region. */
    val sidecarBaseUrl: String? = null,
    val bounds: List<RegionBound>,
    val active: Boolean,
    val centerLat: Double,
    val centerLon: Double,
    val latSpan: Double,
    val lonSpan: Double,
)

/** Returns the outer bounding box [minLat, maxLat, minLon, maxLon] that wraps all bounds. */
fun ObaRegion.outerBoundingBox(): DoubleArray? {
    if (bounds.isEmpty()) return null
    val minLat = bounds.minOf { it.lat - it.latSpan / 2 }
    val maxLat = bounds.maxOf { it.lat + it.latSpan / 2 }
    val minLon = bounds.minOf { it.lon - it.lonSpan / 2 }
    val maxLon = bounds.maxOf { it.lon + it.lonSpan / 2 }
    return doubleArrayOf(minLat, maxLat, minLon, maxLon)
}

// ─── Geocoding ───────────────────────────────────────────────────────────────
data class PlaceResult(
    val label: String,
    val name: String = label,
    val address: String = "",
    val lat: Double,
    val lon: Double,
)

// ─── OTP Trip Planning ────────────────────────────────────────────────────────
data class OtpPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val stopId: String? = null,
    val departure: Long? = null,
    val arrival: Long? = null,
)

data class OtpIntermediateStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val arrival: Long,
    val departure: Long,
    val stopId: String? = null,
)

data class OtpLeg(
    val mode: String,
    val transitLeg: Boolean,
    val route: String,
    val routeId: String? = null,
    val tripId: String? = null,
    val headsign: String? = null,
    val agencyName: String? = null,
    val from: OtpPlace,
    val to: OtpPlace,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val distance: Double,
    val legGeometry: String? = null,
    val routeShortName: String? = null,
    val routeLongName: String? = null,
    val intermediateStops: List<OtpIntermediateStop> = emptyList(),
)

data class OtpItinerary(
    val duration: Long,
    val startTime: Long,
    val endTime: Long,
    val legs: List<OtpLeg>,
    val walkDistance: Double = 0.0,
    val transfers: Int = 0,
)

// ─── Arrival Reminder ────────────────────────────────────────────────────────
/**
 * A pending arrival reminder registered with the sidecar service.
 * Stored locally in AppPreferences so the bell icon reflects active state
 * even after an app restart.
 */
data class ActiveReminder(
    /** OBA trip ID this reminder is for. Used as the lookup key. */
    val tripId: String,
    /** Relative DELETE path returned by the sidecar on creation, e.g. "/1/alarms/uuid" */
    val deleteUrl: String,
    /** How many minutes before arrival the push will fire. */
    val minutesBefore: Int,
    /** Sidecar base URL — needed to build the absolute DELETE endpoint. */
    val sidecarBaseUrl: String,
    // ── Display fields (populated when reminder is set) ──────────────────────
    val routeShortName: String = "",
    val headsign: String = "",
    val stopName: String = "",
    /** Epoch ms of the scheduled/predicted arrival this reminder is for. 0 if unknown. */
    val arrivalEpochMs: Long = 0L,
    /** OBA stop ID — used to deep-link back to the stop on the map. */
    val stopId: String = "",
    /** Stop coordinates — used to fly the camera without a second network call. */
    val stopLat: Double = 0.0,
    val stopLon: Double = 0.0,
)

// ─── Route Shape ─────────────────────────────────────────────────────────────
data class RoutePoint(val lat: Double, val lon: Double)

/** A single bus route's polyline together with its display name. */
data class OverviewRoute(val shortName: String, val points: List<RoutePoint>)

// ─── Saved Stop ───────────────────────────────────────────────────────────────
data class SavedStop(
    val id: String,
    val name: String,
    val code: String,    val lat: Double = 0.0,
    val lon: Double = 0.0,)

// ─── Saved Route ──────────────────────────────────────────────────────────────
data class SavedRoute(
    val routeId: String,
    val tripId: String,
    val shortName: String,
    val longName: String,
    val headsign: String,
)
