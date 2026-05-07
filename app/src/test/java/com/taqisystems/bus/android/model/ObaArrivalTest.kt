// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.model

import com.taqisystems.bus.android.data.model.ArrivalStatus
import com.taqisystems.bus.android.data.model.ObaArrival
import com.taqisystems.bus.android.data.model.TransitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObaArrivalTest {

    private fun arrival(
        predictedMs: Long = 0L,
        scheduledMs: Long = System.currentTimeMillis() + 10 * 60_000L,
    ) = ObaArrival(
        routeId              = "route1",
        routeShortName       = "1",
        tripId               = "trip1",
        tripHeadsign         = "City Centre",
        predictedArrivalTime = predictedMs,
        scheduledArrivalTime = scheduledMs,
        predicted            = predictedMs > 0,
        status               = ArrivalStatus.SCHEDULED,
        minutesUntilArrival  = 10,
        vehicleId            = null,
        vehicleLat           = null,
        vehicleLon           = null,
        vehicleOrientation   = null,
        vehicleLastUpdateTime = null,
        shapeId              = null,
    )

    @Test fun `liveMinutesUntilArrival uses predicted time when set`() {
        val futureMs = System.currentTimeMillis() + 5 * 60_000L
        val a = arrival(predictedMs = futureMs, scheduledMs = futureMs + 10 * 60_000L)
        val live = a.liveMinutesUntilArrival()
        // Should be ~5 minutes (allow ±1 for execution time)
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
        // Verify the *live* recalculation differs from the stale stored field
        // when the stored field is wrong
        val futureMs = System.currentTimeMillis() + 20 * 60_000L
        val a = arrival(predictedMs = futureMs).copy(minutesUntilArrival = 999)
        val live = a.liveMinutesUntilArrival()
        assertTrue("Expected ~20 minutes, got $live", live in 19..21)
    }

    // ── Data class equality ───────────────────────────────────────────────────

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

    @Test fun `default transitType is BUS`() {
        assertEquals(TransitType.BUS, arrival().transitType)
    }

    @Test fun `headway fields default to null and false`() {
        val a = arrival()
        assertEquals(false, a.isHeadway)
        assertEquals(null, a.headwaySecs)
        assertEquals(null, a.headwayEndTime)
    }
}
