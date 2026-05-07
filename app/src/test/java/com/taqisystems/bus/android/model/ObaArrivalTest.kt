// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.model

import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.TransitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObaArrivalTest {

    private fun arrival(
        predictedMs: Long = 0L,
        scheduledMs: Long = System.currentTimeMillis() + 10 * 60_000L,
        status: ArrivalStatus = ArrivalStatus.SCHEDULED,
    ) = ObaArrival(
        routeId              = "route1",
        routeShortName       = "1",
        tripId               = "trip1",
        tripHeadsign         = "City Centre",
        predictedArrivalTime = predictedMs,
        scheduledArrivalTime = scheduledMs,
        predicted            = predictedMs > 0,
        status               = status,
        minutesUntilArrival  = 10,
        vehicleId            = null,
        vehicleLat           = null,
        vehicleLon           = null,
        vehicleOrientation   = null,
        vehicleLastUpdateTime = null,
        shapeId              = null,
    )

    // ── liveMinutesUntilArrival ──────────────────────────────────────────────

    @Test fun `liveMinutesUntilArrival uses predicted time when set`() {
        val futureMs = System.currentTimeMillis() + 5 * 60_000L
        val a = arrival(predictedMs = futureMs, scheduledMs = futureMs + 10 * 60_000L)
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~5 minutes, got $live", live in 4..6)
    }

    @Test fun `liveMinutesUntilArrival falls back to scheduled time when predicted is zero`() {
        val futureMs = System.currentTimeMillis() + 8 * 60_000L
        val a = arrival(predictedMs = 0L, scheduledMs = futureMs)
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~8 minutes, got $live", live in 7..9)
    }

    @Test fun `liveMinutesUntilArrival is negative for a past arrival`() {
        val pastMs = System.currentTimeMillis() - 3 * 60_000L
        val a = arrival(predictedMs = pastMs)
        assertTrue(a.liveMinutesUntilArrival() < 0)
    }

    @Test fun `liveMinutesUntilArrival is zero for an imminent arrival`() {
        val imminentMs = System.currentTimeMillis() + 30_000L  // 30 seconds from now
        val a = arrival(predictedMs = imminentMs)
        // Integer division: 30000/60000 = 0
        assertEquals(0, a.liveMinutesUntilArrival())
    }

    @Test fun `liveMinutesUntilArrival calculation is independent of stored minutesUntilArrival`() {
        val futureMs = System.currentTimeMillis() + 20 * 60_000L
        val a = arrival(predictedMs = futureMs).copy(minutesUntilArrival = 999)
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~20 minutes, got $live", live in 19..21)
    }

    // ── Status / deviation ───────────────────────────────────────────────────

    @Test fun `DELAYED status is preserved`() {
        val a = arrival(predictedMs = System.currentTimeMillis() + 5 * 60_000L,
                        status = ArrivalStatus.DELAYED)
        assertEquals(ArrivalStatus.DELAYED, a.status)
    }

    @Test fun `EARLY status is preserved`() {
        val a = arrival(predictedMs = System.currentTimeMillis() + 5 * 60_000L,
                        status = ArrivalStatus.EARLY)
        assertEquals(ArrivalStatus.EARLY, a.status)
    }

    @Test fun `ON_TIME status is preserved`() {
        val a = arrival(predictedMs = System.currentTimeMillis() + 5 * 60_000L,
                        status = ArrivalStatus.ON_TIME)
        assertEquals(ArrivalStatus.ON_TIME, a.status)
    }

    @Test fun `deviationMinutes defaults to zero`() {
        assertEquals(0, arrival().deviationMinutes)
    }

    @Test fun `positive deviationMinutes represents bus running late`() {
        val a = arrival(status = ArrivalStatus.DELAYED).copy(deviationMinutes = 3)
        assertEquals(3, a.deviationMinutes)
    }

    @Test fun `negative deviationMinutes represents bus running early`() {
        val a = arrival(status = ArrivalStatus.EARLY).copy(deviationMinutes = -2)
        assertEquals(-2, a.deviationMinutes)
    }

    // ── Headway ──────────────────────────────────────────────────────────────

    @Test fun `headway fields default to null and false`() {
        val a = arrival()
        assertFalse(a.isHeadway)
        assertNull(a.headwaySecs)
        assertNull(a.headwayEndTime)
    }

    @Test fun `headway arrival has isHeadway set to true`() {
        val a = arrival().copy(isHeadway = true, headwaySecs = 600)
        assertTrue(a.isHeadway)
    }

    @Test fun `headwaySecs is preserved when set`() {
        val a = arrival().copy(isHeadway = true, headwaySecs = 900)
        assertEquals(900, a.headwaySecs)
    }

    @Test fun `headwayEndTime epoch is preserved when set`() {
        val endEpoch = System.currentTimeMillis() + 60 * 60_000L
        val a = arrival().copy(isHeadway = true, headwayEndTime = endEpoch)
        assertEquals(endEpoch, a.headwayEndTime)
    }

    @Test fun `headway liveMinutesUntilArrival still uses predicted time`() {
        val futureMs = System.currentTimeMillis() + 7 * 60_000L
        val a = arrival(predictedMs = futureMs).copy(isHeadway = true, headwaySecs = 600)
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~7 minutes for headway trip, got $live", live in 6..8)
    }

    @Test fun `headway with no real-time falls back to scheduled time`() {
        val scheduledMs = System.currentTimeMillis() + 12 * 60_000L
        val a = arrival(predictedMs = 0L, scheduledMs = scheduledMs)
            .copy(isHeadway = true, headwaySecs = 600)
        assertFalse(a.predicted) // no real-time
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~12 minutes scheduled fallback, got $live", live in 11..13)
    }

    // ── Data class equality ──────────────────────────────────────────────────

    @Test fun `two arrivals with identical fields are equal`() {
        val a1 = arrival(predictedMs = 1000L, scheduledMs = 2000L)
        val a2 = arrival(predictedMs = 1000L, scheduledMs = 2000L)
        assertEquals(a1, a2)
    }

    @Test fun `arrivals with different routeIds are not equal`() {
        val a1 = arrival().copy(routeId = "A")
        val a2 = arrival().copy(routeId = "B")
        assertTrue(a1 != a2)
    }

    // ── Optional fields ──────────────────────────────────────────────────────

    @Test fun `default transitType is BUS`() {
        assertEquals(TransitType.BUS, arrival().transitType)
    }

    @Test fun `transitType can be overridden to LRT_TRAM`() {
        val a = arrival().copy(transitType = TransitType.LRT_TRAM)
        assertEquals(TransitType.LRT_TRAM, a.transitType)
    }

    @Test fun `agencyName defaults to empty string`() {
        assertEquals("", arrival().agencyName)
    }

    @Test fun `agencyName is preserved when set`() {
        val a = arrival().copy(agencyName = "Rapid Bus")
        assertEquals("Rapid Bus", a.agencyName)
    }

    @Test fun `routeLongName defaults to empty string`() {
        assertEquals("", arrival().routeLongName)
    }

    @Test fun `routeLongName is preserved when set`() {
        val a = arrival().copy(routeLongName = "City Express")
        assertEquals("City Express", a.routeLongName)
    }

    @Test fun `vehicle fields default to null`() {
        val a = arrival()
        assertNull(a.vehicleId)
        assertNull(a.vehicleLat)
        assertNull(a.vehicleLon)
        assertNull(a.vehicleOrientation)
        assertNull(a.vehicleLastUpdateTime)
    }

    @Test fun `vehicle fields are preserved when set`() {
        val a = arrival().copy(
            vehicleId          = "VH-123",
            vehicleLat         = 5.123,
            vehicleLon         = 102.456,
            vehicleOrientation = 90.0,
            vehicleLastUpdateTime = 1_700_000_000_000L,
        )
        assertEquals("VH-123", a.vehicleId)
        assertEquals(5.123, a.vehicleLat!!, 1e-9)
        assertEquals(102.456, a.vehicleLon!!, 1e-9)
        assertEquals(90.0, a.vehicleOrientation!!, 1e-9)
        assertEquals(1_700_000_000_000L, a.vehicleLastUpdateTime)
    }

    @Test fun `routeColor and routeTextColor default to null`() {
        assertNull(arrival().routeColor)
        assertNull(arrival().routeTextColor)
    }
}
