package com.taqisystems.bus.android.data.repository

import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaRoute
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.RoutePoint
import com.taqisystems.bus.android.data.model.TripDetails
import com.taqisystems.bus.android.data.model.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.onebusaway.client.okhttp.OnebusawaySdkOkHttpClientAsync
import org.onebusaway.models.route.RouteRetrieveParams

/**
 * Repository wrapping the official OneBusAway **Kotlin** SDK
 * (org.onebusaway:onebusaway-sdk-kotlin).
 *
 * [OnebusawaySdkOkHttpClientAsync] exposes suspend functions so every call
 * is coroutine-native — no CompletableFuture or blocking calls.
 *
 * Per SDK docs: create only one client per process.
 */
class ObaRepository(
    baseUrl: String = DEFAULT_BASE_URL,
    apiKey: String = DEFAULT_API_KEY,
) {
    // Async (suspend-function) Kotlin SDK client
    private var client = OnebusawaySdkOkHttpClientAsync.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .build()

    // Plain OkHttp for raw endpoints (shape polyline) not covered by the SDK
    private val http = OkHttpClient()

    // Keep the current baseUrl/apiKey for raw requests
    private var baseUrl = baseUrl
    private val apiKey = apiKey

    /** Call this when the active region changes so all subsequent calls use the new URL. */
    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl
        client = OnebusawaySdkOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .baseUrl(newUrl)
            .build()
    }

    // ─── Stops ───────────────────────────────────────────────────────────────

    suspend fun getStopsForLocation(
        lat: Double,
        lon: Double,
        radius: Int = 2000,
    ): List<ObaStop> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/where/stops-for-location.json?key=$apiKey&lat=$lat&lon=$lon&radius=$radius"
            val body = http.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: return@runCatching emptyList()
            val root = JSONObject(body)
            val list = root.optJSONObject("data")?.optJSONArray("list") ?: return@runCatching emptyList()
            (0 until list.length()).map { i ->
                val s = list.getJSONObject(i)
                val routeIds = s.optJSONArray("routeIds")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                ObaStop(
                    id = s.getString("id"),
                    name = s.getString("name"),
                    lat = s.getDouble("lat"),
                    lon = s.getDouble("lon"),
                    code = s.optString("code", ""),
                    direction = s.optString("direction", ""),
                    routeIds = routeIds,
                )
            }
        }.getOrElse { emptyList() }
    }

    // ─── Arrivals ────────────────────────────────────────────────────────────

    suspend fun getArrivalsForStop(stopId: String): List<ObaArrival> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/where/arrivals-and-departures-for-stop/$stopId.json?key=$apiKey&minutesAfter=360"
            val body = http.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: return@runCatching emptyList()
            val root = JSONObject(body)
            val entry = root.optJSONObject("data")?.optJSONObject("entry") ?: return@runCatching emptyList()
            val arr = entry.optJSONArray("arrivalsAndDepartures") ?: return@runCatching emptyList()
            val now = System.currentTimeMillis()

            (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val scheduledMs = e.optLong("scheduledArrivalTime", 0L)
                val predictedMs = e.optLong("predictedArrivalTime", 0L)
                val effectiveMs = if (predictedMs > 0) predictedMs else scheduledMs
                val minutesUntil = ((effectiveMs - now) / 60_000).toInt()
                val isPredicted = predictedMs > 0

                // Headway (frequency-based) trips — frequencyType == 1
                val frequencyType = e.optInt("frequencyType", 0)
                val frequencyObj = e.optJSONObject("frequency")
                val headwaySecs = frequencyObj?.let {
                    val hw = it.optInt("headway", 0)
                    if (hw > 0) hw else null
                }
                val headwayEndTime = frequencyObj?.let {
                    val et = it.optLong("endTime", 0L)
                    if (et > 0) et else null
                }
                val isHeadway = frequencyType == 1 || headwaySecs != null

                val status = when {
                    !isPredicted -> ArrivalStatus.SCHEDULED
                    predictedMs - scheduledMs > 60_000 -> ArrivalStatus.DELAYED
                    predictedMs - scheduledMs < -60_000 -> ArrivalStatus.EARLY
                    else -> ArrivalStatus.ON_TIME
                }
                val tripStatus = e.optJSONObject("tripStatus")
                val position = tripStatus?.optJSONObject("position")
                ObaArrival(
                    routeId = e.optString("routeId", ""),
                    routeShortName = e.optString("routeShortName", "").ifEmpty { e.optString("routeId", "") },
                    routeLongName = e.optString("routeLongName", ""),
                    tripId = e.optString("tripId", ""),
                    tripHeadsign = e.optString("tripHeadsign", ""),
                    predictedArrivalTime = predictedMs,
                    scheduledArrivalTime = scheduledMs,
                    predicted = isPredicted,
                    status = status,
                    minutesUntilArrival = minutesUntil,
                    deviationMinutes = ((predictedMs - scheduledMs) / 60_000).toInt(),
                    vehicleId = e.optString("vehicleId", ""),
                    vehicleLat = position?.optDouble("lat"),
                    vehicleLon = position?.optDouble("lon"),
                    vehicleOrientation = tripStatus?.optDouble("orientation"),
                    vehicleLastUpdateTime = tripStatus?.optLong("lastLocationUpdateTime"),
                    shapeId = null,
                    isHeadway = isHeadway,
                    headwaySecs = headwaySecs,
                    headwayEndTime = headwayEndTime,
                    serviceDate = e.optLong("serviceDate", 0L),
                    stopSequence = e.optInt("stopSequence", 0),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ─── Route ───────────────────────────────────────────────────────────────

    suspend fun getRoutesForLocation(
        query: String,
        lat: Double,
        lon: Double,
        radius: Int = 50_000,
    ): List<ObaRoute> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/where/routes-for-location.json" +
                "?key=$apiKey&lat=$lat&lon=$lon&radius=$radius" +
                "&query=$encodedQuery"
            val body = http.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: return@runCatching emptyList()
            val list = JSONObject(body)
                .optJSONObject("data")?.optJSONArray("list") ?: return@runCatching emptyList()
            (0 until list.length()).map { i ->
                val r = list.getJSONObject(i)
                ObaRoute(
                    id          = r.optString("id", ""),
                    shortName   = r.optString("shortName", ""),
                    longName    = r.optString("longName", ""),
                    description = r.optString("description", ""),
                    agencyId    = r.optString("agencyId", ""),
                    color       = r.optString("color", null),
                    textColor   = r.optString("textColor", null),
                    url         = r.optString("url", null),
                    type        = r.optInt("type", 3),
                )
            }
        }.getOrElse { emptyList() }
    }

    suspend fun getRoute(routeId: String): ObaRoute? = runCatching {
        val params = RouteRetrieveParams.builder().routeId(routeId).build()
        val e = client.route().retrieve(params).data().entry()
        ObaRoute(
            id = e.id(),
            shortName = e.shortName() ?: e.nullSafeShortName() ?: "",
            longName = e.longName() ?: "",
            description = e.description() ?: "",
            agencyId = e.agencyId(),
            color = e.color(),
            textColor = e.textColor(),
            url = e.url(),
            type = e.type().toInt(),
        )
    }.getOrNull()

    // ─── Trip Details ─────────────────────────────────────────────────────────

    suspend fun getTripDetails(tripId: String): TripDetails? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/api/where/trip-details/$tripId.json" +
                "?key=$apiKey&includeSchedule=true&includeStatus=true&includeTrip=true"
            val body = http.newCall(Request.Builder().url(url).build())
                .execute().body?.string() ?: return@runCatching null
            val root = JSONObject(body)
            val entry = root.optJSONObject("data")?.optJSONObject("entry")
                ?: return@runCatching null

            // Schedule stop times
            val schedule = entry.optJSONObject("schedule")
            val stopTimesArr = schedule?.optJSONArray("stopTimes")
            val stopTimes = if (stopTimesArr != null) {
                (0 until stopTimesArr.length()).map { i ->
                    val st = stopTimesArr.getJSONObject(i)
                    TripStop(
                        stopId = st.optString("stopId", ""),
                        stopName = st.optString("stopId", ""), // resolved below from references
                        arrivalTime = st.optInt("arrivalTime", 0),
                        departureTime = st.optInt("departureTime", 0),
                    )
                }
            } else emptyList()

            // Stop name lookup from references
            val refs = root.optJSONObject("data")?.optJSONObject("references")
            val stopsRef = refs?.optJSONArray("stops")
            val stopNameMap = mutableMapOf<String, String>()
            if (stopsRef != null) {
                for (i in 0 until stopsRef.length()) {
                    val s = stopsRef.getJSONObject(i)
                    stopNameMap[s.optString("id", "")] = s.optString("name", "")
                }
            }
            val namedStopTimes = stopTimes.map { st ->
                st.copy(stopName = stopNameMap[st.stopId]?.takeIf { it.isNotEmpty() } ?: st.stopId)
            }

            // Trip headsign from references
            val tripsRef = refs?.optJSONArray("trips")
            var headsign = ""
            if (tripsRef != null) {
                for (i in 0 until tripsRef.length()) {
                    val t = tripsRef.getJSONObject(i)
                    if (t.optString("id", "") == tripId) {
                        headsign = t.optString("tripHeadsign", "")
                        break
                    }
                }
            }

            val status = entry.optJSONObject("status")
            TripDetails(
                tripId = entry.optString("tripId", tripId),
                headsign = headsign,
                stops = namedStopTimes,
                closestStopId = status?.optString("closestStop"),
                nextStopId = status?.optString("nextStop"),
                distanceAlongTrip = status?.optDouble("distanceAlongTrip") ?: 0.0,
                totalDistance = status?.optDouble("totalDistanceAlongTrip") ?: 0.0,
                scheduleDeviation = status?.optDouble("scheduleDeviation") ?: 0.0,
                predicted = status?.optBoolean("predicted") ?: false,
                vehicleId = status?.optString("vehicleId")?.takeIf { it.isNotEmpty() },
            )
        }.onFailure { e ->
            android.util.Log.e("ObaRepository", "getTripDetails failed for tripId=$tripId", e)
        }.getOrNull()
    }

    // ─── Shape (polyline) — raw HTTP, not in SDK ──────────────────────────────
    /**
     * Returns live vehicle positions for every active trip on [routeId].
     * Uses trips-for-route with includeStatus=true to get real-time positions
     * without needing to query each stop individually.
     */
    suspend fun getVehiclesForRoute(routeId: String): List<ObaArrival> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/api/where/trips-for-route/$routeId.json" +
                    "?key=$apiKey&includeSchedule=false&includeStatus=true"
                val body = http.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: return@runCatching emptyList()
                val root = JSONObject(body)
                val list = root.optJSONObject("data")?.optJSONArray("list")
                    ?: return@runCatching emptyList()

                // References for route short/long names
                val refs = root.optJSONObject("data")?.optJSONObject("references")
                val routeRefs = refs?.optJSONArray("routes")
                val routeShortNameMap = mutableMapOf<String, String>()
                val routeLongNameMap  = mutableMapOf<String, String>()
                if (routeRefs != null) {
                    for (i in 0 until routeRefs.length()) {
                        val r = routeRefs.getJSONObject(i)
                        routeShortNameMap[r.optString("id", "")] = r.optString("shortName", "")
                        routeLongNameMap [r.optString("id", "")] = r.optString("longName",  "")
                    }
                }
                val tripsRef = refs?.optJSONArray("trips")
                val headsignMap = mutableMapOf<String, String>()
                if (tripsRef != null) {
                    for (i in 0 until tripsRef.length()) {
                        val t = tripsRef.getJSONObject(i)
                        headsignMap[t.optString("id", "")] = t.optString("tripHeadsign", "")
                    }
                }

                val now = System.currentTimeMillis()
                (0 until list.length()).mapNotNull { i ->
                    val entry   = list.getJSONObject(i)
                    val tripId  = entry.optString("id", "")
                    val status  = entry.optJSONObject("status") ?: return@mapNotNull null
                    val position = status.optJSONObject("position")
                    val vLat = position?.optDouble("lat")
                    val vLon = position?.optDouble("lon")
                    // Skip trips with no live position
                    if (vLat == null || vLon == null || vLat == 0.0 && vLon == 0.0)
                        return@mapNotNull null
                    val predicted   = status.optBoolean("predicted", false)
                    val devSec      = status.optDouble("scheduleDeviation", 0.0)
                    val arrivalStatus = when {
                        !predicted       -> ArrivalStatus.SCHEDULED
                        devSec >  60.0   -> ArrivalStatus.DELAYED
                        devSec < -60.0   -> ArrivalStatus.EARLY
                        else             -> ArrivalStatus.ON_TIME
                    }
                    val rId = entry.optString("routeId", routeId)
                    ObaArrival(
                        routeId              = rId,
                        routeShortName       = routeShortNameMap[rId]?.ifEmpty { rId } ?: rId,
                        routeLongName        = routeLongNameMap[rId] ?: "",
                        tripId               = tripId,
                        tripHeadsign         = headsignMap[tripId] ?: "",
                        predictedArrivalTime = 0L,
                        scheduledArrivalTime = 0L,
                        predicted            = predicted,
                        status               = arrivalStatus,
                        minutesUntilArrival  = 0,
                        deviationMinutes     = (devSec / 60.0).toInt(),
                        vehicleId            = status.optString("vehicleId", "").ifEmpty { tripId },
                        vehicleLat           = vLat,
                        vehicleLon           = vLon,
                        vehicleOrientation   = status.optDouble("orientation"),
                        vehicleLastUpdateTime = status.optLong("lastLocationUpdateTime")
                            .takeIf { it > 0L },
                        shapeId              = null,
                    )
                }
            }.getOrElse { emptyList() }
        }

    /**
     * Fetches all trips for a route, picks the first one, and returns its decoded polyline.
     * Used to draw the full route shape when the user selects a route from search.
     */
    suspend fun getShapeForRoute(routeId: String): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/api/where/trips-for-route/$routeId.json?key=$apiKey&includeSchedule=false&includeStatus=false"
                val body = http.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: return@runCatching emptyList()
                val data = JSONObject(body).optJSONObject("data")
                    ?: return@runCatching emptyList()

                // shapeId lives in data.references.trips[0].shapeId — use it directly
                val shapeId = data
                    .optJSONObject("references")
                    ?.optJSONArray("trips")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optString("shapeId", "")
                    ?.takeIf { it.isNotEmpty() }

                if (shapeId != null) {
                    return@runCatching getRouteShape(shapeId)
                }

                // Fallback: use tripId from list[0] → trip endpoint → shapeId
                val tripId = data.optJSONArray("list")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optString("tripId", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@runCatching emptyList()
                getShapeForTrip(tripId)
            }.getOrElse { emptyList() }
        }
    /** Resolves the shapeId for a trip then returns the decoded polyline. */
    suspend fun getShapeForTrip(tripId: String): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tripUrl = "$baseUrl/api/where/trip/$tripId.json?key=$apiKey"
                val tripBody = http.newCall(Request.Builder().url(tripUrl).build())
                    .execute().body?.string() ?: return@runCatching emptyList()
                val shapeId = JSONObject(tripBody)
                    .optJSONObject("data")
                    ?.optJSONObject("entry")
                    ?.optString("shapeId", "")
                    ?.takeIf { it.isNotEmpty() } ?: return@runCatching emptyList()
                getRouteShape(shapeId)
            }.getOrElse { emptyList() }
        }

    suspend fun getRouteShape(shapeId: String): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$baseUrl/api/where/shape/${shapeId}.json?key=$apiKey"
                val body = http.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: return@runCatching emptyList()
                val points = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("entry")
                    ?.optString("points", "") ?: ""
                decodePolyline(points)
            }.getOrElse { emptyList() }
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.kelantanbus.com"
        const val DEFAULT_API_KEY = "v1_BktoDJ2gJlu6nLM6LsT9H8IUbWc=cGF1bGN3YXR0c0BnbWFpbC5jb20="

        /** Google encoded-polyline decoder. */
        fun decodePolyline(encoded: String): List<RoutePoint> {
            val result = mutableListOf<RoutePoint>()
            var index = 0; var lat = 0; var lng = 0
            while (index < encoded.length) {
                var b: Int; var shift = 0; var value = 0
                do { b = encoded[index++].code - 63; value = value or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lat += if ((value and 1) != 0) (value shr 1).inv() else (value shr 1)
                shift = 0; value = 0
                do { b = encoded[index++].code - 63; value = value or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                lng += if ((value and 1) != 0) (value shr 1).inv() else (value shr 1)
                result.add(RoutePoint(lat / 1e5, lng / 1e5))
            }
            return result
        }
    }
}
