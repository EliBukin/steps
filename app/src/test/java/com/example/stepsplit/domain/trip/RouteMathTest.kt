package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {

    @Test
    fun `distance between identical coordinates is zero`() {
        assertEquals(0.0, RouteMath.haversineMeters(32.0, 34.0, 32.0, 34.0), 1e-9)
    }

    @Test
    fun `distance is symmetric`() {
        val a = RouteMath.haversineMeters(32.0, 34.0, 32.01, 34.01)
        val b = RouteMath.haversineMeters(32.01, 34.01, 32.0, 34.0)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun `one degree of latitude is approximately 111 kilometers`() {
        val distance = RouteMath.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, distance, 500.0)
    }

    @Test
    fun `a known short distance matches within a small tolerance`() {
        // Roughly 111.32m per 0.001 degree of latitude at the equator.
        val distance = RouteMath.haversineMeters(0.0, 0.0, 0.001, 0.0)
        assertEquals(111.32, distance, 1.0)
    }

    @Test
    fun `antipodal coordinates return a finite distance approximately half the earth's circumference`() {
        val distance = RouteMath.haversineMeters(0.0, 0.0, 0.0, 180.0)
        assertTrue("expected a finite distance, got $distance", distance.isFinite())
        assertEquals(20_015_086.0, distance, 1_000.0) // pi * R
    }

    @Test
    fun `antipodal coordinates through the poles return a finite distance`() {
        val distance = RouteMath.haversineMeters(90.0, 0.0, -90.0, 0.0)
        assertTrue("expected a finite distance, got $distance", distance.isFinite())
        assertEquals(20_015_086.0, distance, 1_000.0)
    }

    @Test
    fun `coordinates a hair's breadth from exactly antipodal still return a finite, non-negative distance`() {
        // Exercises the floating-point boundary the coerceIn guard exists for: `a` can round to
        // fractionally outside [0, 1] right at (or one ULP from) the antipode.
        val points = listOf(
            0.0 to 180.0,
            1e-9 to 180.0,
            -1e-9 to 180.0,
            0.0 to (180.0 - 1e-9),
            0.0 to -180.0,
            89.9999999 to 0.0,
        )
        for ((lat, lon) in points) {
            val distance = RouteMath.haversineMeters(0.0, 0.0, lat, lon)
            assertTrue("expected finite distance for ($lat, $lon), got $distance", distance.isFinite())
            assertTrue("expected non-negative distance for ($lat, $lon), got $distance", distance >= 0.0)
        }
    }
}
