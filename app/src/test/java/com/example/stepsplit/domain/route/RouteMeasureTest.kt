package com.example.stepsplit.domain.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class RouteMeasureTest {

    @Test
    fun `the distance adds up every segment and leaves out the gaps between them`() {
        val first = RouteTestShapes.north(1_000.0)
        val second = RouteTestShapes.north(500.0)
        // The second segment starts back at the start: the 1 km jump between them isn't counted.
        val measure = RouteMeasure.measure(listOf(RouteLine(listOf(first, second))), emptyList())

        assertEquals(1_500.0, measure.distanceMeters, 2.0)
        assertEquals(first.size + second.size, measure.pointCount)
        assertNull(measure.climbMeters)
    }

    @Test
    fun `one hill up and down is its climb and descent, like a trip's`() {
        // 4 km out, up 200 m and back down.
        val hill = RouteTestShapes.north(4_000.0, stepMeters = 20.0) { along -> 500 + 200 * sin(Math.PI * along / 4_000) }

        val measure = RouteMeasure.measure(listOf(RouteLine(listOf(hill))), emptyList())

        assertEquals(200.0, measure.climbMeters!!, 10.0)
        assertEquals(200.0, measure.descentMeters!!, 10.0)
    }

    @Test
    fun `lines add up their climbs, and a line without heights adds nothing`() {
        val hill = RouteTestShapes.north(4_000.0, stepMeters = 20.0) { along -> 500 + 100 * sin(Math.PI * along / 4_000) }
        val flat = RouteTestShapes.north(2_000.0)

        val measure = RouteMeasure.measure(listOf(RouteLine(listOf(hill)), RouteLine(listOf(hill)), RouteLine(listOf(flat))), emptyList())

        assertEquals(200.0, measure.climbMeters!!, 10.0)
    }

    @Test
    fun `the height step across a gap between segments is no climb`() {
        // A walk at 500 m, a cable car up (not in the file), and a walk on at 900 m.
        val below = RouteTestShapes.north(2_000.0, stepMeters = 20.0) { 500.0 }
        val above = RouteTestShapes.north(2_000.0, stepMeters = 20.0) { 900.0 }

        val measure = RouteMeasure.measure(listOf(RouteLine(listOf(below, above))), emptyList())

        assertEquals(0.0, measure.climbMeters!!, 1.0)
        assertEquals(0.0, measure.descentMeters!!, 1.0)
    }

    @Test
    fun `each segment's own climb counts, and they add up`() {
        val hill = RouteTestShapes.north(4_000.0, stepMeters = 20.0) { along -> 500 + 100 * sin(Math.PI * along / 4_000) }
        val higherHill = RouteTestShapes.north(4_000.0, stepMeters = 20.0) { along -> 1_500 + 150 * sin(Math.PI * along / 4_000) }

        val measure = RouteMeasure.measure(listOf(RouteLine(listOf(hill, higherHill))), emptyList())

        assertEquals(250.0, measure.climbMeters!!, 10.0)
        assertEquals(250.0, measure.descentMeters!!, 10.0)
    }

    @Test
    fun `the bounds hold every point and waypoint`() {
        val line = RouteLine(listOf(RouteTestShapes.north(1_000.0)))
        val waypoint = RouteWaypoint(latitude = 30.9, longitude = 35.2, elevationMeters = null, name = "Off to the side", note = null)

        val bounds = RouteMeasure.measure(listOf(line), listOf(waypoint)).bounds!!

        assertEquals(30.9, bounds.minLatitude, 0.0)
        assertEquals(RouteTestShapes.northOf(1_000.0), bounds.maxLatitude, 1e-9)
        assertEquals(35.0, bounds.minLongitude, 0.0)
        assertEquals(35.2, bounds.maxLongitude, 0.0)
    }

    @Test
    fun `waypoints alone have bounds and no distance`() {
        val measure = RouteMeasure.measure(emptyList(), listOf(RouteWaypoint(31.0, 35.0, null, "A", null)))

        assertEquals(0.0, measure.distanceMeters, 0.0)
        assertEquals(RouteBounds(31.0, 35.0, 31.0, 35.0), measure.bounds)
    }
}
