package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTraceGeometryTest {

    @Test
    fun `an empty route produces no offsets`() {
        assertTrue(RouteTraceGeometry.normalize(emptyList(), 100f, 100f).isEmpty())
    }

    @Test
    fun `a zero-sized drawing area produces no offsets`() {
        assertTrue(RouteTraceGeometry.normalize(listOf(RoutePoint(1.0, 1.0)), 0f, 100f).isEmpty())
        assertTrue(RouteTraceGeometry.normalize(listOf(RoutePoint(1.0, 1.0)), 100f, 0f).isEmpty())
    }

    @Test
    fun `a single point is centered in the drawing area`() {
        val offsets = RouteTraceGeometry.normalize(listOf(RoutePoint(1.0, 1.0)), 100f, 200f, paddingFraction = 0f)
        assertEquals(1, offsets.size)
        assertEquals(50f, offsets.single().x, 0.01f)
        assertEquals(100f, offsets.single().y, 0.01f)
    }

    @Test
    fun `a perfectly horizontal route (constant latitude) is drawn at a constant vertical position`() {
        val points = listOf(RoutePoint(10.0, 0.0), RoutePoint(10.0, 1.0), RoutePoint(10.0, 2.0))
        val offsets = RouteTraceGeometry.normalize(points, 100f, 100f, paddingFraction = 0f)
        val yValues = offsets.map { it.y }
        assertTrue(yValues.all { kotlin.math.abs(it - yValues.first()) < 0.01f })
        // x should still vary across the three distinct longitudes.
        assertTrue(offsets[0].x != offsets[2].x)
    }

    @Test
    fun `a perfectly vertical route (constant longitude) is drawn at a constant horizontal position`() {
        val points = listOf(RoutePoint(0.0, 20.0), RoutePoint(1.0, 20.0), RoutePoint(2.0, 20.0))
        val offsets = RouteTraceGeometry.normalize(points, 100f, 100f, paddingFraction = 0f)
        val xValues = offsets.map { it.x }
        assertTrue(xValues.all { kotlin.math.abs(it - xValues.first()) < 0.01f })
        assertTrue(offsets[0].y != offsets[2].y)
    }

    @Test
    fun `northernmost points map to a smaller y than southernmost points`() {
        val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(1.0, 0.0))
        val offsets = RouteTraceGeometry.normalize(points, 100f, 100f, paddingFraction = 0f)
        // Canvas Y increases downward, so the more-northern (larger-latitude) point is drawn higher (smaller y).
        assertTrue(offsets[1].y < offsets[0].y)
    }

    @Test
    fun `every offset stays within the requested bounds`() {
        val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(5.0, 5.0), RoutePoint(-3.0, 8.0))
        val offsets = RouteTraceGeometry.normalize(points, 200f, 150f)
        offsets.forEach {
            assertTrue(it.x in 0f..200f)
            assertTrue(it.y in 0f..150f)
        }
    }
}
