// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.taqisystems.bus.android.data.model.OtpIntermediateStop
import com.taqisystems.bus.android.data.model.OtpItinerary
import com.taqisystems.bus.android.data.model.OtpLeg
import com.taqisystems.bus.android.data.model.OtpPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class OtpResponse(
    @SerializedName("plan") val plan: OtpPlan?,
    @SerializedName("error") val error: OtpError?,
)
private data class OtpPlan(@SerializedName("itineraries") val itineraries: List<OtpItineraryJson>?)
private data class OtpError(
    @SerializedName("id")      val id: Int? = null,
    @SerializedName("message") val message: String?,   // machine code, e.g. PATH_NOT_FOUND
    @SerializedName("msg")     val msg: String? = null, // OTP's own description
)

/** Maps OTP error codes to commuter-friendly messages. */
private fun otpErrorMessage(code: String?, msg: String?): String = when (code) {
    "TOO_CLOSE" ->
        "Your origin and destination are too close together to plan a transit route. Try walking directly."
    "PATH_NOT_FOUND", "NO_TRANSIT_CONNECTION", "NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW" ->
        "No transit route found between those two points at the requested time. " +
        "Try a different departure time or check if transit serves that area."
    "LOCATION_NOT_ACCESSIBLE" ->
        "One of your locations cannot be reached on foot. Try adjusting your start or end point."
    "OUTSIDE_BOUNDS", "OUTSIDE_SERVICE_AREA" ->
        "One or both locations are outside the transit service area for this region."
    "NO_STOPS_IN_RANGE" ->
        "No bus stops found near your start or destination. Try moving your location closer to a road or bus stop."
    "WALKING_BETTER_THAN_TRANSIT" ->
        "The fastest option for this journey is to walk — no transit route is faster."
    "BOGUS_PARAMETER" ->
        "There was a problem with the trip request. Please check your origin and destination and try again."
    "REQUEST_TIMEOUT" ->
        "The trip planning request timed out. Please try again."
    else ->
        msg?.takeIf { it.isNotBlank() }
            ?: "No route could be found. Try adjusting your locations, time, or travel mode."
}
private data class OtpItineraryJson(
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("startTime") val startTime: Long = 0,
    @SerializedName("endTime") val endTime: Long = 0,
    @SerializedName("legs") val legs: List<OtpLegJson>?,
)
private data class OtpLegJson(
    @SerializedName("mode") val mode: String = "",
    @SerializedName("transitLeg") val transitLeg: Boolean = false,
    @SerializedName("route") val route: String = "",
    @SerializedName("routeShortName") val routeShortName: String? = null,
    @SerializedName("routeLongName") val routeLongName: String? = null,
    @SerializedName("routeType") val routeType: Int? = null,
    @SerializedName("routeId") val routeId: String? = null,
    @SerializedName("tripId") val tripId: String? = null,
    @SerializedName("headsign") val headsign: String? = null,
    @SerializedName("agencyName") val agencyName: String? = null,
    @SerializedName("from") val from: OtpPlaceJson?,
    @SerializedName("to") val to: OtpPlaceJson?,
    @SerializedName("startTime") val startTime: Long = 0,
    @SerializedName("endTime") val endTime: Long = 0,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("distance") val distance: Double = 0.0,
    @SerializedName("legGeometry") val legGeometry: LegGeometryJson? = null,
    @SerializedName("intermediateStops") val intermediateStops: List<OtpIntermediateStopJson>?,
)
private data class OtpPlaceJson(
    @SerializedName("name") val name: String = "",
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lon") val lon: Double = 0.0,
    @SerializedName("stopId") val stopId: String? = null,
    @SerializedName("departure") val departure: Long? = null,
    @SerializedName("arrival") val arrival: Long? = null,
)
private data class LegGeometryJson(@SerializedName("points") val points: String?)
private data class OtpIntermediateStopJson(
    @SerializedName("name") val name: String = "",
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lon") val lon: Double = 0.0,
    @SerializedName("arrival") val arrival: Long = 0,
    @SerializedName("departure") val departure: Long = 0,
    @SerializedName("stopId") val stopId: String? = null,
)

/** Maps a GTFS route type integer to the canonical OTP mode string used for icons/colors. */
private fun gtfsTypeToOtpMode(routeType: Int): String = when (routeType) {
    0    -> "TRAM"      // LRT / light rail / tram
    1    -> "SUBWAY"    // MRT / metro / rapid transit
    2    -> "RAIL"      // KTM Komuter / intercity rail
    4    -> "FERRY"
    11   -> "MONORAIL"
    3    -> "BUS"
    else -> "BUS"
}

class OtpRepository(private val http: OkHttpClient = OkHttpClient()) {
    private val gson = Gson()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    suspend fun planTrip(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        otpBaseUrl: String,
        date: String? = null,
        time: String? = null,
        modes: String = "TRANSIT,WALK",
        numItineraries: Int = 5,
        arriveBy: Boolean = false,
    ): List<OtpItinerary> = withContext(Dispatchers.IO) {
        val now = Date()
        val d = date ?: dateFmt.format(now)
        val t = time ?: timeFmt.format(now)
        val base = otpBaseUrl.trimEnd('/')
        val query = buildString {
            append("fromPlace=${fromLat},${fromLon}")
            append("&toPlace=${toLat},${toLon}")
            append("&date=$d&time=$t")
            append("&mode=$modes")
            append("&numItineraries=$numItineraries")
            if (arriveBy) append("&arriveBy=true")
        }
        val url = "$base/routers/default/plan?$query"
        val request = Request.Builder().url(url).build()
        val body = http.newCall(request).execute().body?.string()
            ?: return@withContext emptyList()
        val resp = gson.fromJson(body, OtpResponse::class.java)
        if (resp.error != null) throw Exception(otpErrorMessage(resp.error.message, resp.error.msg))
        (resp.plan?.itineraries ?: emptyList()).map { it.toDomain() }
    }

    private fun OtpItineraryJson.toDomain() = OtpItinerary(
        duration = duration,
        startTime = startTime,
        endTime = endTime,
        legs = legs?.map { leg ->
            // Derive canonical OTP mode from GTFS routeType if present — more reliable
            // than the 'mode' string which can vary between OTP versions/configs.
            val resolvedMode = leg.routeType?.let { gtfsTypeToOtpMode(it) } ?: leg.mode
            OtpLeg(
                mode = resolvedMode,
                transitLeg = leg.transitLeg,
                route = leg.route,
                routeId = leg.routeId,
                tripId = leg.tripId,
                headsign = leg.headsign,
                agencyName = leg.agencyName,
                from = leg.from?.toDomain() ?: OtpPlace("", 0.0, 0.0),
                to = leg.to?.toDomain() ?: OtpPlace("", 0.0, 0.0),
                startTime = leg.startTime,
                endTime = leg.endTime,
                duration = leg.duration,
                distance = leg.distance,
                legGeometry = leg.legGeometry?.points,
                routeShortName = leg.routeShortName?.takeIf { it.isNotBlank() }
                    ?: leg.route.takeIf { it.isNotBlank() },
                routeLongName = leg.routeLongName,
                intermediateStops = leg.intermediateStops?.map { s ->
                    OtpIntermediateStop(s.name, s.lat, s.lon, s.arrival, s.departure, s.stopId)
                } ?: emptyList(),
            )
        } ?: emptyList(),
    )

    private fun OtpPlaceJson.toDomain() = OtpPlace(name, lat, lon, stopId, departure, arrival)
}
