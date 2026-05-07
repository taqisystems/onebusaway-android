// SPDX-FileCopyrightText: 2026 Taqi Systems
// SPDX-License-Identifier: Apache-2.0

package com.taqisystems.bus.android.model

import com.taqisystems.bus.android.data.model.TransitType
import org.junit.Assert.assertEquals
import org.junit.Test

class TransitTypeTest {

    // ── fromGtfsType ──────────────────────────────────────────────────────────

    @Test fun `gtfs type 0 maps to LRT_TRAM`() =
        assertEquals(TransitType.LRT_TRAM, TransitType.fromGtfsType(0))

    @Test fun `gtfs type 1 maps to MRT_METRO`() =
        assertEquals(TransitType.MRT_METRO, TransitType.fromGtfsType(1))

    @Test fun `gtfs type 2 maps to COMMUTER_RAIL`() =
        assertEquals(TransitType.COMMUTER_RAIL, TransitType.fromGtfsType(2))

    @Test fun `gtfs type 3 maps to BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsType(3))

    @Test fun `gtfs type 4 maps to FERRY`() =
        assertEquals(TransitType.FERRY, TransitType.fromGtfsType(4))

    @Test fun `gtfs type 11 maps to MONORAIL`() =
        assertEquals(TransitType.MONORAIL, TransitType.fromGtfsType(11))

    @Test fun `unknown positive gtfs type defaults to BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsType(99))

    @Test fun `unknown negative gtfs type defaults to BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsType(-1))

    // ── fromGtfsTypes ─────────────────────────────────────────────────────────

    @Test fun `empty set defaults to BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsTypes(emptySet()))

    @Test fun `set with only bus types returns BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsTypes(setOf(3)))

    @Test fun `set with duplicate bus type returns BUS`() =
        assertEquals(TransitType.BUS, TransitType.fromGtfsTypes(setOf(3, 3)))

    @Test fun `single non-bus type returns that type`() =
        assertEquals(TransitType.MRT_METRO, TransitType.fromGtfsTypes(setOf(1)))

    @Test fun `bus plus one non-bus type returns the non-bus type`() =
        assertEquals(TransitType.LRT_TRAM, TransitType.fromGtfsTypes(setOf(3, 0)))

    @Test fun `two different non-bus types returns MIXED`() =
        assertEquals(TransitType.MIXED, TransitType.fromGtfsTypes(setOf(0, 1)))

    @Test fun `three different non-bus types returns MIXED`() =
        assertEquals(TransitType.MIXED, TransitType.fromGtfsTypes(setOf(0, 1, 2)))

    @Test fun `bus plus two different non-bus types returns MIXED`() =
        assertEquals(TransitType.MIXED, TransitType.fromGtfsTypes(setOf(3, 0, 4)))

    @Test fun `FERRY type alone returns FERRY`() =
        assertEquals(TransitType.FERRY, TransitType.fromGtfsTypes(setOf(4)))

    @Test fun `MONORAIL type alone returns MONORAIL`() =
        assertEquals(TransitType.MONORAIL, TransitType.fromGtfsTypes(setOf(11)))

    @Test fun `COMMUTER_RAIL plus bus returns COMMUTER_RAIL`() =
        assertEquals(TransitType.COMMUTER_RAIL, TransitType.fromGtfsTypes(setOf(2, 3)))

    // ── mapColor sanity ───────────────────────────────────────────────────────

    @Test fun `each TransitType has a distinct colour`() {
        val colors = TransitType.entries.map { it.mapColor }
        assertEquals("Expected all TransitType mapColors to be unique", colors.size, colors.toSet().size)
    }
}
