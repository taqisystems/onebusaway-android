package com.taqisystems.bus.android.data.repository

import android.util.Log
import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.ObaRoute
import com.taqisystems.bus.android.data.model.ObaStop
import com.taqisystems.bus.android.data.model.RoutePoint
import com.taqisystems.bus.android.data.model.TransitType
import com.taqisystems.bus.android.data.model.TripDetails
import com.taqisystems.bus.android.data.model.TripStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.onebusaway.client.okhttp.OnebusawaySdkOkHttpClientAsync
import org.onebusaway.models.route.RouteRetrieveParams
import org.onebusaway.models.routesforlocation.RoutesForLocationListParams
import org.onebusaway.models.stop.StopRetrieveParams
import org.onebusaway.models.stopsforlocation.StopsForLocationListParams
import org.onebusaway.models.trip.TripRetrieveParams
import org.onebusaway.models.arrivalanddeparture.ArrivalAndDepartureListParams
import org.onebusaway.models.tripdetails.TripDetailRetrieveParams
import org.onebusaway.models.tripsforroute.TripsForRouteListParams

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

    // Plain OkHttp for raw endpoints not covered by the SDK
    private val http = com.taqisystems.bus.android.ServiceLocator.httpClient

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
            val params = StopsForLocationListParams.builder()
                .lat(lat)
                .lon(lon)
                .radius(radius.toDouble())
                .build()
            val resp = client.stopsForLocation().list(params)
            val data = resp.data()

            // Build routeId → GTFS type map from references (zero extra API calls).
            val routeTypeMap = data.references().routes().associate { r -> r.id() to r.type().toInt() }

            data.list().map { s ->
                val routeIds = s.routeIds()
                val gtfsTypes = routeIds.mapNotNull { routeTypeMap[it] }.toSet()
                ObaStop(
                    id        = s.id(),
                    name      = s.name(),
                    lat       = s.lat(),
                    lon       = s.lon(),
                    code      = s.code() ?: "",
                    direction = s.direction() ?: "",
                    routeIds  = routeIds,
                    transitType = TransitType.fromGtfsTypes(gtfsTypes),
                )
            }
        }.getOrElse { emptyList() }
    }

    // ─── Arrivals ────────────────────────────────────────────────────────────

    /** Returns an [ObaStop] with lat/lon populated from the stop endpoint. */
    suspend fun getStopLocation(stopId: String): ObaStop? = withContext(Dispatchers.IO) {
        runCatching {
            val params = StopRetrieveParams.builder().stopId(stopId).build()
            val entry = client.stop().retrieve(params).data().entry()
            ObaStop(
                id        = entry.id(),
                name      = entry.name(),
                lat       = entry.lat(),
                lon       = entry.lon(),
                code      = entry.code() ?: "",
                direction = entry.direction() ?: "",
            ).takeIf { it.lat != 0.0 || it.lon != 0.0 }
        }.getOrNull()
    }

    suspend fun getArrivalsForStop(stopId: String): List<ObaArrival> = withContext(Dispatchers.IO) {
        runCatching {
            val params = ArrivalAndDepartureListParams.builder()
                .stopId(stopId)
                .minutesAfter(360L)
                .build()
            val resp = client.arrivalAndDeparture().list(params)
            val arrivals = resp.data().entry().arrivalsAndDepartures()
            val now = System.currentTimeMillis()

            // Build routeId → TransitType from references
            val routeTypeMap = resp.data().references().routes()
                .associate { it.id() to TransitType.fromGtfsType(it.type().toInt()) }

            arrivals.map { e ->
                val scheduledMs = e.scheduledArrivalTime()
                val predictedMs = e.predictedArrivalTime()
                val effectiveMs = if (predictedMs > 0) predictedMs else scheduledMs
                val minutesUntil = ((effectiveMs - now) / 60_000).toInt()
                val isPredicted = predictedMs > 0

                // frequency() is String? — non-null signals a headway trip
                // headwaySecs / headwayEndTime unavailable from opaque string
                val isHeadway = e.frequency() != null

                val status = when {
                    !isPredicted -> ArrivalStatus.SCHEDULED
                    predictedMs - scheduledMs > 60_000 -> ArrivalStatus.DELAYED
                    predictedMs - scheduledMs < -60_000 -> ArrivalStatus.EARLY
                    else -> ArrivalStatus.ON_TIME
                }
                val ts = e.tripStatus()
                ObaArrival(
                    routeId = e.routeId(),
                    routeShortName = e.routeShortName()?.takeIf { it.isNotEmpty() } ?: e.routeId(),
                    routeLongName = e.routeLongName() ?: "",
                    tripId = e.tripId(),
                    tripHeadsign = e.tripHeadsign(),
                    predictedArrivalTime = predictedMs,
                    scheduledArrivalTime = scheduledMs,
                    predicted = isPredicted,
                    status = status,
                    minutesUntilArrival = minutesUntil,
                    deviationMinutes = ((predictedMs - scheduledMs) / 60_000).toInt(),
                    vehicleId = e.vehicleId(),
                    vehicleLat = ts?.position()?.lat(),
                    vehicleLon = ts?.position()?.lon(),
                    vehicleOrientation = ts?.orientation(),
                    vehicleLastUpdateTime = ts?.lastLocationUpdateTime(),
                    shapeId = null,
                    isHeadway = isHeadway,
                    headwaySecs = null,
                    headwayEndTime = null,
                    serviceDate = e.serviceDate(),
                    stopSequence = e.stopSequence().toInt(),
                    transitType = routeTypeMap[e.routeId()] ?: TransitType.BUS,
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
            val params = RoutesForLocationListParams.builder()
                .lat(lat)
                .lon(lon)
                .radius(radius.toDouble())
                .apply { if (query.isNotBlank()) query(query) }
                .build()
            client.routesForLocation().list(params).data().list().map { r ->
                ObaRoute(
                    id          = r.id(),
                    shortName   = r.shortName() ?: r.nullSafeShortName() ?: "",
                    longName    = r.longName() ?: "",
                    description = r.description() ?: "",
                    agencyId    = r.agencyId(),
                    color       = r.color(),
                    textColor   = r.textColor(),
                    url         = r.url(),
                    type        = r.type().toInt(),
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
            val params = TripDetailRetrieveParams.builder().tripId(tripId).build()
            val resp = client.tripDetails().retrieve(params)
            val entry = resp.data().entry()
            val refs  = resp.data().references()

            // Schedule stop times
            val stopTimes = entry.schedule()?.stopTimes()?.mapNotNull { st ->
                val sid = st.stopId() ?: return@mapNotNull null
                TripStop(
                    stopId        = sid,
                    stopName      = sid, // resolved below from references
                    arrivalTime   = st.arrivalTime()?.toInt() ?: 0,
                    departureTime = st.departureTime()?.toInt() ?: 0,
                )
            } ?: emptyList()

            // Stop name lookup from references
            val stopNameMap = refs.stops().associate { it.id() to it.name() }
            val namedStopTimes = stopTimes.map { st ->
                st.copy(stopName = stopNameMap[st.stopId]?.takeIf { it.isNotEmpty() } ?: st.stopId)
            }

            // Trip headsign from references
            val headsign = refs.trips().firstOrNull { it.id() == tripId }?.tripHeadsign() ?: ""

            val status = entry.status()
            TripDetails(
                tripId            = entry.tripId(),
                headsign          = headsign,
                stops             = namedStopTimes,
                closestStopId     = status?.closestStop()?.takeIf { it.isNotEmpty() },
                nextStopId        = status?.nextStop(),
                distanceAlongTrip = status?.distanceAlongTrip() ?: 0.0,
                totalDistance     = status?.totalDistanceAlongTrip() ?: 0.0,
                scheduleDeviation = status?.scheduleDeviation()?.toDouble() ?: 0.0,
                predicted         = status?.predicted() ?: false,
                vehicleId         = status?.vehicleId()?.takeIf { it.isNotEmpty() },
            )
        }.onFailure { e ->
            Log.e("ObaRepository", "getTripDetails failed for tripId=$tripId", e)
        }.getOrNull()
    }

    // ─── Shape (polyline) ─────────────────────────────────────────────────────
    /**
     * Returns live vehicle positions for every active trip on [routeId].
     * Uses trips-for-route with includeStatus=true to get real-time positions
     * without needing to query each stop individually.
     */
    suspend fun getVehiclesForRoute(routeId: String): List<ObaArrival> =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = TripsForRouteListParams.builder()
                    .routeId(routeId)
                    .includeSchedule(false)
                    .includeStatus(true)
                    .build()
                val resp = client.tripsForRoute().list(params)
                val refs = resp.data().references()

                // References: routeId → shortName / longName / TransitType
                val routeShortNameMap = mutableMapOf<String, String>()
                val routeLongNameMap  = mutableMapOf<String, String>()
                val routeTypeMap      = mutableMapOf<String, TransitType>()
                refs.routes().forEach { r ->
                    routeShortNameMap[r.id()] = r.shortName() ?: r.nullSafeShortName() ?: ""
                    routeLongNameMap [r.id()] = r.longName() ?: ""
                    routeTypeMap     [r.id()] = TransitType.fromGtfsType(r.type().toInt())
                }

                // References: tripId → headsign + routeId
                val headsignMap = mutableMapOf<String, String>()
                val tripRouteMap = mutableMapOf<String, String>()
                refs.trips().forEach { t ->
                    headsignMap [t.id()] = t.tripHeadsign() ?: ""
                    tripRouteMap[t.id()] = t.routeId()
                }

                resp.data().list().mapNotNull { item ->
                    val tripId = item.tripId()
                    val status = item.status() ?: return@mapNotNull null
                    val position = status.position()
                    val vLat = position?.lat()
                    val vLon = position?.lon()
                    // Skip trips with no live position
                    if (vLat == null || vLon == null || (vLat == 0.0 && vLon == 0.0))
                        return@mapNotNull null
                    val predicted  = status.predicted()
                    val devSec     = status.scheduleDeviation().toDouble()
                    val arrivalStatus = when {
                        !predicted     -> ArrivalStatus.SCHEDULED
                        devSec >  60.0 -> ArrivalStatus.DELAYED
                        devSec < -60.0 -> ArrivalStatus.EARLY
                        else           -> ArrivalStatus.ON_TIME
                    }
                    val rId = tripRouteMap[tripId] ?: routeId
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
                        vehicleId            = status.vehicleId()?.ifEmpty { tripId } ?: tripId,
                        vehicleLat           = vLat,
                        vehicleLon           = vLon,
                        vehicleOrientation   = status.orientation(),
                        vehicleLastUpdateTime = status.lastLocationUpdateTime()
                            .takeIf { it > 0L },
                        shapeId              = null,
                        transitType          = routeTypeMap[rId] ?: TransitType.BUS,
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
                // stops-for-route includes encoded polylines directly — no trip lookup needed
                val url = "$baseUrl/api/where/stops-for-route/$routeId.json?key=$apiKey&includePolylines=true"
                Log.d("ObaRepo", "getShapeForRoute: fetching $url")
                val body = http.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: run {
                        Log.e("ObaRepo", "getShapeForRoute: empty response body")
                        return@runCatching emptyList()
                    }
                val polylines = JSONObject(body)
                    .optJSONObject("data")
                    ?.optJSONObject("entry")
                    ?.optJSONArray("polylines")
                if (polylines != null && polylines.length() > 0) {
                    // Pick the longest segment (main direction) — avoids drawing both
                    // outbound + inbound and the straight connector line between them
                    var best = emptyList<RoutePoint>()
                    for (i in 0 until polylines.length()) {
                        val encoded = polylines.getJSONObject(i).optString("points", "")
                        if (encoded.isNotEmpty()) {
                            val pts = decodePolyline(encoded)
                            if (pts.size > best.size) best = pts
                        }
                    }
                    Log.d("ObaRepo", "getShapeForRoute: best segment has ${best.size} points of ${polylines.length()} total segments")
                    return@runCatching best
                }

                // Fallback: trips-for-route → shapeId
                Log.w("ObaRepo", "getShapeForRoute: no polylines in stops-for-route, trying trips-for-route")
                val tripsUrl = "$baseUrl/api/where/trips-for-route/$routeId.json?key=$apiKey&includeSchedule=false&includeStatus=false"
                val tripsBody = http.newCall(Request.Builder().url(tripsUrl).build())
                    .execute().body?.string() ?: return@runCatching emptyList()
                val data = JSONObject(tripsBody).optJSONObject("data") ?: return@runCatching emptyList()

                val shapeId = data
                    .optJSONObject("references")
                    ?.optJSONArray("trips")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optString("shapeId", "")
                    ?.takeIf { it.isNotEmpty() }
                if (shapeId != null) {
                    Log.d("ObaRepo", "getShapeForRoute: fallback shapeId=$shapeId")
                    return@runCatching getRouteShape(shapeId)
                }

                val tripId = data.optJSONArray("list")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)
                    ?.optString("tripId", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: run {
                        Log.e("ObaRepo", "getShapeForRoute: no tripId in list either")
                        return@runCatching emptyList()
                    }
                Log.d("ObaRepo", "getShapeForRoute: fallback tripId=$tripId")
                getShapeForTrip(tripId)
            }.getOrElse { e ->
                Log.e("ObaRepo", "getShapeForRoute exception: ${e.message}", e)
                emptyList()
            }
        }
    /** Resolves the shapeId for a trip then returns the decoded polyline. */
    suspend fun getShapeForTrip(tripId: String): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d("ObaRepo", "getShapeForTrip: fetching trip $tripId via SDK")
                val params = TripRetrieveParams.builder().tripId(tripId).build()
                val shapeId = client.trip().retrieve(params).data().entry().shapeId()
                    ?: run {
                        Log.e("ObaRepo", "getShapeForTrip: no shapeId for tripId=$tripId")
                        return@runCatching emptyList()
                    }
                Log.d("ObaRepo", "getShapeForTrip: shapeId=$shapeId")
                getRouteShape(shapeId)
            }.getOrElse { e ->
                Log.e("ObaRepo", "getShapeForTrip exception: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getRouteShape(shapeId: String): List<RoutePoint> =
        withContext(Dispatchers.IO) {
            runCatching {
                val points = client.shape().retrieve(shapeId).data().entry().points()
                Log.d("ObaRepo", "getRouteShape: decoded ${points.length} chars -> ${decodePolyline(points).size} points")
                decodePolyline(points)
            }.getOrElse { e ->
                Log.e("ObaRepo", "getRouteShape exception: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Downloads raw text from [url] using the shared OkHttpClient.
     * Used by HomeViewModel to fetch the sidecar-served route shapes GeoJSON.
     */
    suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            resp.body?.string() ?: ""
        }
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
