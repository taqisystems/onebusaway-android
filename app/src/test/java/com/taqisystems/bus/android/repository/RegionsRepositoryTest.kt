// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.repository

import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.RegionBound
import com.taqisystems.bus.android.data.repository.RegionsRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegionsRepositoryTest {

    // ── MockWebServer fixture ─────────────────────────────────────────────────

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun repoWithUrl(url: String) = RegionsRepository(http = client, regionsUrl = url)

    private val REGIONS_JSON = """
        {
          "data": {
            "list": [
              {
                "id": 1,
                "regionName": "Kelantan Bus",
                "obaBaseUrl": "https://api.kelantanbus.com",
                "otpBaseUrl": null,
                "sidecarBaseUrl": "https://sidecar.kelantanbus.com",
                "active": true,
                "bounds": [
                  {"lat": 6.0, "lon": 102.0, "latSpan": 2.0, "lonSpan": 2.0}
                ]
              },
              {
                "id": 2,
                "regionName": "Inactive Region",
                "obaBaseUrl": "https://api.inactive.com",
                "otpBaseUrl": null,
                "sidecarBaseUrl": null,
                "active": false,
                "bounds": []
              }
            ]
          }
        }
    """.trimIndent()

    // ── fetchRegions ─────────────────────────────────────────────────────────

    @Test fun `fetchRegions filters out inactive regions`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val regions = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)
        assertEquals(1, regions.size)
        assertEquals("Kelantan Bus", regions[0].regionName)
    }

    @Test fun `fetchRegions parses id, obaBaseUrl, sidecarBaseUrl correctly`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val r = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)[0]
        assertEquals(1, r.id)
        assertEquals("https://api.kelantanbus.com", r.obaBaseUrl)
        assertEquals("https://sidecar.kelantanbus.com", r.sidecarBaseUrl)
    }

    @Test fun `fetchRegions computes centerLat as average of bound lats`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val r = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)[0]
        // Single bound at lat=6.0 → centerLat = 6.0
        assertEquals(6.0, r.centerLat, 1e-9)
    }

    @Test fun `fetchRegions computes latSpan as max of bound latSpans`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val r = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)[0]
        assertEquals(2.0, r.latSpan, 1e-9)
    }

    @Test fun `fetchRegions returns active=true on parsed regions`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val r = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)[0]
        assertTrue(r.active)
    }

    @Test fun `fetchRegions caches result — no second HTTP request on second call`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val repo = repoWithUrl(server.url("/").toString())
        repo.fetchRegions(forceRefresh = true)   // first call — hits server
        val second = repo.fetchRegions()          // second call — should use cache
        // If a second HTTP request was made the server would have no response queued
        // and would throw — so reaching here proves the cache was used
        assertEquals(1, server.requestCount)
        assertEquals(1, second.size)
    }

    @Test fun `fetchRegions with forceRefresh makes a new HTTP request`() = runTest {
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        server.enqueue(MockResponse().setBody(REGIONS_JSON))
        val repo = repoWithUrl(server.url("/").toString())
        repo.fetchRegions(forceRefresh = true)
        repo.fetchRegions(forceRefresh = true)
        assertEquals(2, server.requestCount)
    }

    @Test fun `fetchRegions returns empty list for empty response body`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":{\"list\":[]}}"))
        val regions = repoWithUrl(server.url("/").toString()).fetchRegions(forceRefresh = true)
        assertTrue(regions.isEmpty())
    }

    // ── REGIONS_URL constant ─────────────────────────────────────────────────

    @Test fun `REGIONS_URL constant has expected value`() {
        assertEquals(
            "https://cdn.unrealasia.net/onebusaway/regions.json",
            RegionsRepository.REGIONS_URL,
        )
    }

    // ── findRegionForLocation ─────────────────────────────────────────────────

    private fun bound(lat: Double, lon: Double, latSpan: Double, lonSpan: Double) =
        RegionBound(lat = lat, lon = lon, latSpan = latSpan, lonSpan = lonSpan)

    private fun region(id: Int, vararg bounds: RegionBound) = ObaRegion(
        id = id, regionName = "R$id", obaBaseUrl = "https://r$id.example.com",
        otpBaseUrl = null, bounds = bounds.toList(), active = true,
        centerLat = 0.0, centerLon = 0.0, latSpan = 0.0, lonSpan = 0.0,
    )

    private val repo = RegionsRepository()

    @Test fun `findRegionForLocation returns null for empty list`() {
        assertNull(repo.findRegionForLocation(emptyList(), 5.0, 102.0))
    }

    @Test fun `findRegionForLocation returns matching region`() {
        // Bound: lat=6, lon=102, latSpan=2, lonSpan=2 → [5..7, 101..103]
        val r = region(1, bound(6.0, 102.0, 2.0, 2.0))
        assertEquals(r, repo.findRegionForLocation(listOf(r), 6.0, 102.0))
    }

    @Test fun `findRegionForLocation returns null when outside all bounds`() {
        val r = region(1, bound(6.0, 102.0, 2.0, 2.0))
        assertNull(repo.findRegionForLocation(listOf(r), 0.0, 0.0))
    }

    @Test fun `findRegionForLocation returns second region when first does not match`() {
        val r1 = region(1, bound(6.0, 102.0, 2.0, 2.0))  // [5..7, 101..103]
        val r2 = region(2, bound(3.0,  101.5, 2.0, 2.0)) // [2..4, 100.5..102.5]
        // 3.5 is inside r2 but not r1
        assertEquals(r2, repo.findRegionForLocation(listOf(r1, r2), 3.5, 101.5))
    }

    @Test fun `findRegionForLocation includes boundary edges`() {
        val r = region(1, bound(6.0, 102.0, 2.0, 2.0))  // [5..7, 101..103]
        // Exact corner
        assertEquals(r, repo.findRegionForLocation(listOf(r), 5.0, 101.0))
        assertEquals(r, repo.findRegionForLocation(listOf(r), 7.0, 103.0))
    }

    @Test fun `findRegionForLocation excludes points just outside boundary`() {
        val r = region(1, bound(6.0, 102.0, 2.0, 2.0))  // [5..7, 101..103]
        assertNull(repo.findRegionForLocation(listOf(r), 4.9999, 102.0))
        assertNull(repo.findRegionForLocation(listOf(r), 7.0001, 102.0))
    }

    @Test fun `findRegionForLocation first match wins when multiple regions overlap`() {
        // Both regions cover (6.0, 102.0)
        val r1 = region(1, bound(6.0, 102.0, 4.0, 4.0))
        val r2 = region(2, bound(6.0, 102.0, 2.0, 2.0))
        assertEquals(r1, repo.findRegionForLocation(listOf(r1, r2), 6.0, 102.0))
    }
}
