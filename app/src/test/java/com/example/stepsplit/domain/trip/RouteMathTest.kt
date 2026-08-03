package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
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
}
