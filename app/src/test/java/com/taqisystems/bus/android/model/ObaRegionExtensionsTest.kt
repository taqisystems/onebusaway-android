// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.model

import com.taqisystems.bus.android.data.model.ObaRegion
import com.taqisystems.bus.android.data.model.RegionBound
import com.taqisystems.bus.android.data.model.outerBoundingBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObaRegionExtensionsTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun region(vararg bounds: RegionBound) = ObaRegion(
        id            = 1,
        regionName    = "Test",
        obaBaseUrl    = "https://example.com",
        otpBaseUrl    = null,
        bounds        = bounds.toList(),
        active        = true,
        centerLat     = 0.0,
        centerLon     = 0.0,
        latSpan       = 0.0,
        lonSpan       = 0.0,
    )

    private fun bound(lat: Double, lon: Double, latSpan: Double, lonSpan: Double) =
        RegionBound(lat = lat, lon = lon, latSpan = latSpan, lonSpan = lonSpan)

    // ── outerBoundingBox ─────────────────────────────────────────────────────

    @Test fun `empty bounds returns null`() {
        assertNull(region().outerBoundingBox())
    }

    @Test fun `single bound produces correct bounding box`() {
        // lat=6.0, latSpan=2.0 → minLat=5.0, maxLat=7.0
        // lon=102.0, lonSpan=2.0 → minLon=101.0, maxLon=103.0
        val r = region(bound(lat = 6.0, lon = 102.0, latSpan = 2.0, lonSpan = 2.0))
        val box = r.outerBoundingBox()!!
        assertArrayEquals(doubleArrayOf(5.0, 7.0, 101.0, 103.0), box, 1e-9)
    }

    @Test fun `two non-overlapping bounds produces outer union`() {
        // Bound A: lat=4.0, latSpan=2.0 → [3.0, 5.0]  lon=100.0, lonSpan=2.0 → [99.0, 101.0]
        // Bound B: lat=8.0, latSpan=2.0 → [7.0, 9.0]  lon=104.0, lonSpan=2.0 → [103.0, 105.0]
        // Outer:   minLat=3.0, maxLat=9.0, minLon=99.0, maxLon=105.0
        val r = region(
            bound(lat = 4.0, lon = 100.0, latSpan = 2.0, lonSpan = 2.0),
            bound(lat = 8.0, lon = 104.0, latSpan = 2.0, lonSpan = 2.0),
        )
        val box = r.outerBoundingBox()!!
        assertArrayEquals(doubleArrayOf(3.0, 9.0, 99.0, 105.0), box, 1e-9)
    }

    @Test fun `two overlapping bounds produces outer envelope`() {
        // Bound A: lat=5.0, latSpan=4.0 → [3.0, 7.0]  lon=101.0, lonSpan=4.0 → [99.0, 103.0]
        // Bound B: lat=6.0, latSpan=2.0 → [5.0, 7.0]  lon=102.0, lonSpan=2.0 → [101.0, 103.0]
        // Outer:   minLat=3.0, maxLat=7.0, minLon=99.0, maxLon=103.0
        val r = region(
            bound(lat = 5.0, lon = 101.0, latSpan = 4.0, lonSpan = 4.0),
            bound(lat = 6.0, lon = 102.0, latSpan = 2.0, lonSpan = 2.0),
        )
        val box = r.outerBoundingBox()!!
        assertArrayEquals(doubleArrayOf(3.0, 7.0, 99.0, 103.0), box, 1e-9)
    }

    @Test fun `result array order is minLat maxLat minLon maxLon`() {
        // Verify index conventions: [0]=minLat [1]=maxLat [2]=minLon [3]=maxLon
        val r = region(bound(lat = 10.0, lon = 20.0, latSpan = 4.0, lonSpan = 6.0))
        val box = r.outerBoundingBox()!!
        assert(box[0] < box[1]) { "index 0 should be minLat, index 1 maxLat" }
        assert(box[2] < box[3]) { "index 2 should be minLon, index 3 maxLon" }
        assertArrayEquals(doubleArrayOf(8.0, 12.0, 17.0, 23.0), box, 1e-9)
    }

    @Test fun `bounding box handles negative coordinates`() {
        // Southern hemisphere / western hemisphere
        val r = region(bound(lat = -34.0, lon = -70.0, latSpan = 4.0, lonSpan = 6.0))
        val box = r.outerBoundingBox()!!
        assertArrayEquals(doubleArrayOf(-36.0, -32.0, -73.0, -67.0), box, 1e-9)
    }
}
