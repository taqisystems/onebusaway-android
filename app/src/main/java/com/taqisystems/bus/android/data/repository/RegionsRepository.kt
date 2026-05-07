// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.RegionBound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private data class RegionsResponse(
    @SerializedName("data") val data: RegionsData?,
)
private data class RegionsData(
    @SerializedName("list") val list: List<RegionJson>?,
)
private data class RegionJson(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("regionName") val regionName: String = "",
    @SerializedName("obaBaseUrl") val obaBaseUrl: String = "",
    @SerializedName("otpBaseUrl") val otpBaseUrl: String? = null,
    @SerializedName("sidecarBaseUrl") val sidecarBaseUrl: String? = null,
    @SerializedName("active") val active: Boolean = false,
    @SerializedName("bounds") val bounds: List<BoundJson>? = null,
)
private data class BoundJson(
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lon") val lon: Double = 0.0,
    @SerializedName("latSpan") val latSpan: Double = 0.0,
    @SerializedName("lonSpan") val lonSpan: Double = 0.0,
)

class RegionsRepository(
    private val http: OkHttpClient = OkHttpClient(),
    private val regionsUrl: String = REGIONS_URL,
) {
    private val gson = Gson()
    private var cache: List<ObaRegion>? = null

    suspend fun fetchRegions(forceRefresh: Boolean = false): List<ObaRegion> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) cache?.let { return@withContext it }
            val request = Request.Builder().url(regionsUrl).build()
            val body = http.newCall(request).execute().body?.string() ?: "[]"
            val resp = gson.fromJson(body, RegionsResponse::class.java)
            val regions = (resp.data?.list ?: emptyList())
                .filter { it.active }
                .map { r ->
                    val bounds = r.bounds?.map { b ->
                        RegionBound(b.lat, b.lon, b.latSpan, b.lonSpan)
                    } ?: emptyList()
                    val centerLat = if (bounds.isEmpty()) 0.0 else bounds.sumOf { it.lat } / bounds.size
                    val centerLon = if (bounds.isEmpty()) 0.0 else bounds.sumOf { it.lon } / bounds.size
                    val latSpan = bounds.maxOfOrNull { it.latSpan } ?: 0.0
                    val lonSpan = bounds.maxOfOrNull { it.lonSpan } ?: 0.0
                    ObaRegion(
                        id = r.id,
                        regionName = r.regionName,
                        obaBaseUrl = r.obaBaseUrl,
                        otpBaseUrl = r.otpBaseUrl,
                        sidecarBaseUrl = r.sidecarBaseUrl?.takeIf { it.isNotBlank() },
                        bounds = bounds,
                        active = r.active,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        latSpan = latSpan,
                        lonSpan = lonSpan,
                    )
                }
            cache = regions
            regions
        }

    fun findRegionForLocation(regions: List<ObaRegion>, lat: Double, lon: Double): ObaRegion? =
        regions.firstOrNull { region ->
            region.bounds.any { b ->
                val minLat = b.lat - b.latSpan / 2
                val maxLat = b.lat + b.latSpan / 2
                val minLon = b.lon - b.lonSpan / 2
                val maxLon = b.lon + b.lonSpan / 2
                lat in minLat..maxLat && lon in minLon..maxLon
            }
        }

    companion object {
        const val REGIONS_URL = "https://cdn.unrealasia.net/onebusaway/regions.json"
    }
}
