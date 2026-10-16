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

    @Test
    fun `fitKeepingShape keeps a wide route wide instead of stretching it into a square`() {
        // At the equator a degree of longitude and of latitude are the same length: 2 by 1 degrees.
        val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(1.0, 2.0))
        val offsets = RouteTraceGeometry.fitKeepingShape(points, 100f, 100f, paddingFraction = 0f)
        val width = offsets[1].x - offsets[0].x
        val height = offsets[0].y - offsets[1].y
        assertEquals(100f, width, 0.01f)
        assertEquals(50f, height, 0.01f)
        // Centered vertically, and north is up.
        assertEquals(75f, offsets[0].y, 0.01f)
        assertEquals(25f, offsets[1].y, 0.01f)
    }

    @Test
    fun `fitKeepingShape narrows longitude away from the equator`() {
        // At 60 degrees north a degree of longitude is half as long as a degree of latitude, so a
        // route 2 degrees east and 1 degree north is square on the ground.
        val points = listOf(RoutePoint(59.5, 0.0), RoutePoint(60.5, 2.0))
        val offsets = RouteTraceGeometry.fitKeepingShape(points, 100f, 100f, paddingFraction = 0f)
        assertEquals(offsets[1].x - offsets[0].x, offsets[0].y - offsets[1].y, 0.5f)
    }

    @Test
    fun `fitKeepingShape centers a single point and handles an empty route`() {
        val single = RouteTraceGeometry.fitKeepingShape(listOf(RoutePoint(31.0, 35.0)), 60f, 40f)
        assertEquals(30f, single.single().x, 0.01f)
        assertEquals(20f, single.single().y, 0.01f)
        assertTrue(RouteTraceGeometry.fitKeepingShape(emptyList(), 60f, 40f).isEmpty())
    }

    @Test
    fun `fitKeepingShape draws a straight north-south route as a centered vertical line`() {
        val points = listOf(RoutePoint(10.0, 20.0), RoutePoint(10.5, 20.0), RoutePoint(11.0, 20.0))
        val offsets = RouteTraceGeometry.fitKeepingShape(points, 100f, 100f, paddingFraction = 0.1f)
        offsets.forEach { assertEquals(50f, it.x, 0.01f) }
        assertEquals(90f, offsets.first().y, 0.01f)
        assertEquals(10f, offsets.last().y, 0.01f)
    }

    @Test
    fun `downsample keeps a short route as it is`() {
        val points = (0 until 10).map { RoutePoint(it.toDouble(), 0.0) }
        assertEquals(points, RouteTraceGeometry.downsample(points, 64))
    }

    @Test
    fun `downsample thins a long route evenly and keeps its first and last points`() {
        val points = (0 until 1000).map { RoutePoint(it.toDouble(), 0.0) }
        val thinned = RouteTraceGeometry.downsample(points, 64)
        assertEquals(64, thinned.size)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
        val gaps = thinned.zipWithNext { a, b -> b.latitude - a.latitude }
        assertTrue("points must stay in order and evenly spread: $gaps", gaps.all { it in 15.0..17.0 })
    }
}
