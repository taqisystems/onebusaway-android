// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.data.repository

import com.google.gson.Gson
import com.taqisystems.bus.android.data.model.PlaceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class GeocodingRepository(private val http: OkHttpClient = OkHttpClient()) {
    private val gson = Gson()

    suspend fun searchPlaces(
        query: String,
        size: Int = 6,
        focusLat: Double? = null,
        focusLon: Double? = null,
        boundMinLat: Double? = null,
        boundMaxLat: Double? = null,
        boundMinLon: Double? = null,
        boundMaxLon: Double? = null,
    ): List<PlaceResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val params = buildString {
            append("text=${q.encodeUrl()}&size=$size")
            focusLat?.let { append("&focus.point.lat=$it") }
            focusLon?.let { append("&focus.point.lon=$it") }
            boundMinLat?.let { append("&boundary.rect.min_lat=$it") }
            boundMaxLat?.let { append("&boundary.rect.max_lat=$it") }
            boundMinLon?.let { append("&boundary.rect.min_lon=$it") }
            boundMaxLon?.let { append("&boundary.rect.max_lon=$it") }
        }
        val endpoint = if (q.length >= 4) "search" else "autocomplete"
        val url = "$BASE/$endpoint?$params"
        runCatching {
            val body = http.newCall(Request.Builder().url(url).build()).execute()
                .body?.string() ?: return@runCatching emptyList()
            parseFeatures(gson.fromJson(body, FeatureCollection::class.java))
        }.getOrElse { emptyList() }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): PlaceResult? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE/reverse?point.lat=$lat&point.lon=$lon&size=1"
                val body = http.newCall(Request.Builder().url(url).build()).execute()
                    .body?.string() ?: return@runCatching null
                parseFeatures(gson.fromJson(body, FeatureCollection::class.java)).firstOrNull()
            }.getOrNull()
        }

    private fun parseFeatures(fc: FeatureCollection?): List<PlaceResult> =
        (fc?.features ?: emptyList())
            .filter { it.geometry?.coordinates?.size == 2 }
            .map { f ->
                val name = f.properties?.name ?: f.properties?.label ?: ""
                val label = f.properties?.label ?: name
                PlaceResult(
                    label = label,
                    name = name,
                    address = label,
                    lat = f.geometry!!.coordinates[1],
                    lon = f.geometry.coordinates[0],
                )
            }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")

    // Minimal GeoJSON models
    private data class FeatureCollection(val features: List<Feature>?)
    private data class Feature(val geometry: Geometry?, val properties: Props?)
    private data class Geometry(val coordinates: List<Double>)
    private data class Props(val label: String?, val name: String?)

    companion object {
        const val BASE = "https://geocode.kelantanbus.com/v1"
    }
}
