package com.taqisystems.bus.android.repository

import com.taqisystems.bus.android.data.repository.ObaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodePolylineTest {

    // ── empty input ──────────────────────────────────────────────────────────

    @Test fun `empty string returns empty list`() {
        assertEquals(emptyList<Any>(), ObaRepository.decodePolyline(""))
    }

    // ── single coordinate pair ───────────────────────────────────────────────

    @Test fun `decodes single positive coordinate pair`() {
        // "_ibE_seK" encodes (1.0, 2.0) in Google Polyline Encoding
        val pts = ObaRepository.decodePolyline("_ibE_seK")
        assertEquals(1, pts.size)
        assertEquals(1.0, pts[0].lat, 1e-5)
        assertEquals(2.0, pts[0].lon, 1e-5)
    }

    @Test fun `decodes single negative coordinate pair`() {
        // "~hbE~reK" encodes (-1.0, -2.0)
        val pts = ObaRepository.decodePolyline("~hbE~reK")
        assertEquals(1, pts.size)
        assertEquals(-1.0, pts[0].lat, 1e-5)
        assertEquals(-2.0, pts[0].lon, 1e-5)
    }

    // ── Google sample polyline ───────────────────────────────────────────────

    // Google's documented example: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
    private val GOOGLE_SAMPLE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"

    @Test fun `Google sample polyline decodes to 3 points`() {
        assertEquals(3, ObaRepository.decodePolyline(GOOGLE_SAMPLE).size)
    }

    @Test fun `Google sample first point is approximately (38_5, -120_2)`() {
        val pts = ObaRepository.decodePolyline(GOOGLE_SAMPLE)
        assertEquals(38.5,   pts[0].lat, 1e-4)
        assertEquals(-120.2, pts[0].lon, 1e-4)
    }

    @Test fun `Google sample second point is approximately (40_7, -120_95)`() {
        val pts = ObaRepository.decodePolyline(GOOGLE_SAMPLE)
        assertEquals(40.7,    pts[1].lat, 1e-4)
        assertEquals(-120.95, pts[1].lon, 1e-4)
    }

    @Test fun `Google sample third point is approximately (43_252, -126_453)`() {
        val pts = ObaRepository.decodePolyline(GOOGLE_SAMPLE)
        assertEquals(43.252,  pts[2].lat, 1e-4)
        assertEquals(-126.453, pts[2].lon, 1e-4)
    }

    // ── correctness invariants ───────────────────────────────────────────────

    @Test fun `all decoded points have finite lat and lon`() {
        ObaRepository.decodePolyline(GOOGLE_SAMPLE).forEach { pt ->
            assertTrue(pt.lat.isFinite())
            assertTrue(pt.lon.isFinite())
        }
    }
}
