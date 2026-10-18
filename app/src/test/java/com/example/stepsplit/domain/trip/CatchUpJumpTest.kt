package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A GPS position that lags behind a walker and then catches up in one jump: 8 m in 14 s, 8 m in
 * 6 s, then 110 m in 23 s (17 km/h), all straight ahead, while the phone reports 4.5-4.9 km/h. The
 * route's shape and length are right, so nothing is removed - only that stretch's speed is wrong,
 * and the 30 s average that gives a trip its top speed took it in. Rebuilt on a made-up grid in
 * open sea, not from real coordinates, and checked through the displayed-route pipeline:
 * [RouteSanitizer], [RouteSmoother], then [TripMotion] and [RouteSpeedColoring].
 */
class CatchUpJumpTest {

    private class Walk(private val accuracies: FloatArray = floatArrayOf(20f, 22f, 25f, 21f, 23f), private val reportedKmh: DoubleArray? = doubleArrayOf(4.5, 4.7, 4.9, 4.6, 4.8)) {
        private var t = 0L
        private var west = 0.0
        private var index = 0
        val points = mutableListOf(point())

        private fun point(northMeters: Double = 0.0, reported: Double? = reportedKmh?.let { it[index % it.size] }) = TripPoint(
            capturedAtEpochSecond = t,
            latitude = BASE_LATITUDE + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
            longitude = BASE_LONGITUDE - Math.toDegrees(west / (EARTH_RADIUS_METERS * cos(Math.toRadians(BASE_LATITUDE)))),
            accuracyMeters = accuracies[index % accuracies.size],
            altitudeMeters = null,
            speedMetersPerSecond = reported?.let { (it / 3.6).toFloat() },
        )

        /** [meters] further west after [seconds], the phone reporting [reported] km/h (the walk's own by default). */
        fun step(meters: Double, seconds: Long, northMeters: Double = 0.0, reported: Double? = reportedKmh?.let { it[(index + 1) % it.size] }): Walk {
            t += seconds
            west += meters
            index++
            points += point(northMeters, reported)
            return this
        }

        fun steps(count: Int, meters: Double, seconds: Long): Walk = apply { repeat(count) { step(meters, seconds) } }
    }

    private fun displayed(points: List<TripPoint>): SmoothedRoute = RouteSmoother.smooth(RouteSanitizer.sanitize(points).points)

    private fun topSpeedKmh(route: SmoothedRoute, type: TripType = TripType.WALK): Double? =
        TripMotion.measure(route.points, type).topSpeedMetersPerSecond?.let { it * 3.6 }

    /** Twenty steps of 8 m every 5-6 s, the lag and the jump, then twenty steps of 8 m every 5 s. */
    private fun lagThenCatchUp(walk: Walk = Walk()): Walk {
        repeat(20) { walk.step(8.0, if (it % 2 == 0) 5 else 6) }
        walk.step(8.0, 14).step(8.0, 6).step(110.0, 23)
        return walk.steps(20, 8.0, 5)
    }

    @Test
    fun `a lag and a catch-up jump don't set a walk's top speed, and nothing is removed`() {
        val walk = lagThenCatchUp()
        val route = displayed(walk.points)

        assertTrue("top speed ${topSpeedKmh(route)} km/h", topSpeedKmh(route)!! <= 8.0)
        assertEquals("every point kept", walk.points.size, route.points.size)
        assertEquals("the distance stays", 446.0, route.distanceMeters, 1.0)
    }

    @Test
    fun `the speed legend's fast end stays at or below the top speed`() {
        val route = displayed(lagThenCatchUp().points)

        val legendFastKmh = RouteSpeedColoring.colorize(route.points).scale!!.fastMetersPerSecond * 3.6
        assertTrue("legend $legendFastKmh, top ${topSpeedKmh(route)}", legendFastKmh <= topSpeedKmh(route)!! + 1e-9)
    }

    // ---- Kept as before ----

    /** A jeep at 54 km/h, the phone reporting it: a real fast stretch is the top speed. */
    @Test
    fun `a drive's top speed is its real speed`() {
        val drive = Walk(reportedKmh = doubleArrayOf(54.0)).steps(30, 75.0, 5)

        assertEquals(54.0, topSpeedKmh(displayed(drive.points), TripType.DRIVE)!!, 2.0)
    }

    /** Running for a minute in the middle of a walk, the phone reporting the run. */
    @Test
    fun `a real burst of speed the phone reports still sets the top speed`() {
        val walk = Walk().steps(15, 7.0, 5)
        repeat(12) { walk.step(15.0, 5, reported = 10.8) }
        walk.steps(15, 7.0, 5)

        assertEquals(10.8, topSpeedKmh(displayed(walk.points))!!, 0.6)
    }

    /** Back the way you came at walking pace: no stretch is faster than the phone says. */
    @Test
    fun `a real turnaround keeps its walking top speed`() {
        val walk = Walk().steps(15, 7.0, 5).steps(15, -7.0, 5).steps(15, 7.0, 5)

        assertEquals(5.0, topSpeedKmh(displayed(walk.points))!!, 0.4)
    }

    /** Standing still, the phone reporting 0 m/s and the GPS wandering a little. */
    @Test
    fun `standing still in the middle of a walk keeps the walk's top speed`() {
        val walk = Walk().steps(15, 7.0, 5)
        for (n in listOf(4.0, -3.0, 5.0, -4.0)) walk.step(0.0, 20, northMeters = n, reported = 0.0)
        walk.steps(15, 7.0, 5)

        assertEquals(5.0, topSpeedKmh(displayed(walk.points))!!, 0.4)
    }

    /** Without the phone's speed nothing shows the jump is wrong, so it counts, as it always did. */
    @Test
    fun `without a reported speed the jump still counts`() {
        val route = displayed(lagThenCatchUp(Walk(reportedKmh = null)).points)

        assertTrue("top speed ${topSpeedKmh(route)} km/h", topSpeedKmh(route)!! > 12.0)
    }

    @Test
    fun `a route with only contradicted stretches still has a speed scale`() {
        val jumpy = Walk().step(110.0, 23).step(110.0, 23).step(110.0, 23)

        val coloring = RouteSpeedColoring.colorize(jumpy.points)

        assertTrue(coloring.scale != null)
        assertNull("nothing trustworthy is a top speed", TripMotion.measure(jumpy.points, TripType.WALK).topSpeedMetersPerSecond)
    }

    private companion object {
        const val BASE_LATITUDE = 10.0
        const val BASE_LONGITUDE = 20.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
