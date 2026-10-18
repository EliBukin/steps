package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPS out-and-back excursions on a walk: one to three fixes that jump away from the path and come
 * back, each leg under the 6 m/s walking cap, while the phone's own reported speed stays at walking
 * pace. The shape is the one seen on a real walk (a 76 m jump back, 26 m further, then 173 m forward
 * onto the path, 18 km/h legs against 4-5 km/h reported), rebuilt here on a made-up grid in open sea
 * - no real coordinates. Checked through the whole displayed-route pipeline: [RouteSanitizer], then
 * [RouteSmoother], then [TripMotion]'s top speed.
 */
class OutAndBackExcursionTest {

    private data class Fix(val eastMeters: Double, val northMeters: Double, val t: Long, val accuracy: Float = 18f, val speed: Float? = WALK_SPEED)

    private fun TripPointOf(fix: Fix) = TripPoint(
        capturedAtEpochSecond = fix.t,
        latitude = BASE_LATITUDE + Math.toDegrees(fix.northMeters / EARTH_RADIUS_METERS),
        longitude = BASE_LONGITUDE + Math.toDegrees(fix.eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(BASE_LATITUDE)))),
        accuracyMeters = fix.accuracy,
        altitudeMeters = null,
        speedMetersPerSecond = fix.speed,
    )

    /** Where a walker heading west at [WALK_METERS_PER_SECOND] is at [t], a few metres either side of the line. */
    private fun onPath(t: Long, index: Int) = Fix(
        eastMeters = -WALK_METERS_PER_SECOND * t,
        northMeters = JITTER[index % JITTER.size],
        t = t,
        accuracy = ACCURACIES[index % ACCURACIES.size],
        speed = REPORTED[index % REPORTED.size],
    )

    /**
     * Twelve minutes west at 4.5 km/h, a fix every 5-10 s, with two excursions:
     * - at 120-160 s, three fixes up to 75 m north of the path and back (15 km/h legs);
     * - at 400-457 s, the walk's own shape: 76 m back east, 26 m further, then onto the path again.
     */
    private fun walkWithExcursions(): Pair<List<Fix>, List<Fix>> {
        val walk = mutableListOf<Fix>()
        val excursion = mutableListOf<Fix>()
        var t = 0L
        var index = 0
        while (t <= 720L) {
            when (t) {
                in 121L..159L -> Unit
                in 401L..456L -> Unit
                else -> walk += onPath(t, index)
            }
            t += INTERVALS[index % INTERVALS.size]
            index++
        }
        // Each anchor is exactly where the excursion starts and ends.
        walk += onPath(120L, 0)
        walk += onPath(160L, 1)
        walk += onPath(400L, 2)
        walk += onPath(457L, 3)
        excursion += Fix(-WALK_METERS_PER_SECOND * 130, 40.0, 130L)
        excursion += Fix(-WALK_METERS_PER_SECOND * 140, 75.0, 140L)
        excursion += Fix(-WALK_METERS_PER_SECOND * 150, 40.0, 150L)
        val a = -WALK_METERS_PER_SECOND * 400
        excursion += Fix(a + 76.0, 2.0, 415L, accuracy = 22f)
        excursion += Fix(a + 102.0, 3.0, 423L, accuracy = 25f)
        return walk.distinctBy { it.t }.sortedBy { it.t } to excursion
    }

    private fun displayed(fixes: List<Fix>): SmoothedRoute = RouteSmoother.smooth(RouteSanitizer.sanitize(fixes.map(::TripPointOf)).points)

    private fun topSpeedKmh(route: SmoothedRoute): Double = (TripMotion.measure(route.points, TripType.WALK).topSpeedMetersPerSecond ?: 0.0) * 3.6

    @Test
    fun `out-and-back excursions on a walk are removed, so the top speed and distance are a walker's`() {
        val (walk, excursion) = walkWithExcursions()
        val route = displayed((walk + excursion).sortedBy { it.t })

        val trueMeters = WALK_METERS_PER_SECOND * 720
        assertTrue("top speed ${topSpeedKmh(route)} km/h", topSpeedKmh(route) <= 8.0)
        assertEquals("distance ${route.distanceMeters} m", trueMeters, route.distanceMeters, trueMeters * 0.03)
    }

    @Test
    fun `only the excursion points are removed`() {
        val (walk, excursion) = walkWithExcursions()

        val result = RouteSanitizer.sanitize((walk + excursion).sortedBy { it.t }.map(::TripPointOf))

        assertEquals(walk.map { it.t }, result.points.map { it.capturedAtEpochSecond })
        assertEquals(excursion.map { it.t }.toSet(), result.rejections.map { it.point.capturedAtEpochSecond }.toSet())
    }

    /**
     * One fix 76 m off, sideways and a little back, 18 s after the last good one, then 120 m back
     * onto the path 27 s later (15-16 km/h legs), while the phone reports 3.2-5.0 km/h and the two
     * good fixes are 73 m apart in 45 s. The one-point look-ahead (step 4) already removes it.
     */
    @Test
    fun `a single fix far off the path and back is removed`() {
        val walk = (0 until 12).map { Fix(eastMeters = 15.0 + 9.0 * (12 - it), northMeters = 0.0, t = -60L + 5 * it, accuracy = 16f, speed = 1.36f) } +
            listOf(Fix(15.0, 0.0, 0L, 15f, 1.36f), Fix(0.0, 0.0, 11L, 18f, 1.39f)) +
            listOf(Fix(-73.0, 0.0, 56L, 14f, 1.31f)) +
            (1..15).map { Fix(-73.0 - 9.0 * it, 0.0, 56L + 5 * it, 16f, 1.33f) }
        val excursion = Fix(22.6, 72.6, 29L, 9f, 0.89f)
        val fixes = (walk + excursion).sortedBy { it.t }

        val result = RouteSanitizer.sanitize(fixes.map(::TripPointOf))

        assertEquals(listOf(29L), result.rejections.map { it.point.capturedAtEpochSecond })
        assertTrue("top speed ${topSpeedKmh(displayed(fixes))} km/h", topSpeedKmh(displayed(fixes)) <= 8.0)
    }

    // ---- Kept: what looks like an excursion but isn't ----

    /** Forgot something: 30 s back east at walking pace, then on west. The phone's speed fits every leg. */
    @Test
    fun `a real turnaround at walking pace is kept`() {
        val fixes = listOf(
            Fix(0.0, 0.0, 0L), Fix(-11.0, 0.0, 9L), Fix(-22.0, 1.0, 18L),
            Fix(-11.0, 2.0, 27L), Fix(0.0, 1.0, 36L), Fix(11.0, 0.0, 45L),
            Fix(0.0, 0.0, 54L), Fix(-11.0, -1.0, 63L), Fix(-22.0, 0.0, 72L), Fix(-33.0, 0.0, 81L),
        )

        assertEquals(fixes.size, RouteSanitizer.sanitize(fixes.map(::TripPointOf)).points.size)
    }

    /** Thirty metres off the trail to a viewpoint and back, at the pace the phone reports. */
    @Test
    fun `a short detour off the path at walking pace is kept`() {
        val fixes = listOf(
            Fix(0.0, 0.0, 0L), Fix(-11.0, 0.0, 9L),
            Fix(-15.0, 10.0, 18L), Fix(-18.0, 21.0, 27L), Fix(-20.0, 30.0, 36L), Fix(-21.0, 19.0, 45L), Fix(-22.0, 8.0, 54L),
            Fix(-30.0, 0.0, 63L), Fix(-41.0, 0.0, 72L), Fix(-52.0, 0.0, 81L),
        )

        assertEquals(fixes.size, RouteSanitizer.sanitize(fixes.map(::TripPointOf)).points.size)
    }

    /** A hairpin bend at 54 km/h: the out-and-back shape, but the phone reports driving speed. */
    @Test
    fun `a hairpin while driving is kept`() {
        val drive = 15f
        val fixes = listOf(
            Fix(0.0, 0.0, 0L, speed = drive), Fix(-75.0, 0.0, 5L, speed = drive), Fix(-150.0, 0.0, 10L, speed = drive),
            Fix(-180.0, 40.0, 15L, speed = 9f), Fix(-150.0, 80.0, 20L, speed = drive),
            Fix(-75.0, 80.0, 25L, speed = drive), Fix(0.0, 80.0, 30L, speed = drive),
        )

        assertEquals(fixes.size, RouteSanitizer.sanitize(fixes.map(::TripPointOf)).points.size)
    }

    /** The stop, its drift and the first fix after stopping stay as the standing-still rule leaves them. */
    @Test
    fun `a stop is not an excursion`() {
        val stop = StandingStillDriftFixture.all.map { fix ->
            TripPointOf(Fix(fix.eastMeters, fix.northMeters, fix.t, fix.accuracy, fix.speed))
        }

        val kept = RouteSanitizer.sanitize(stop).points.map { it.capturedAtEpochSecond }

        val fixture = StandingStillDriftFixture
        assertTrue((fixture.walkIn + fixture.stop + fixture.walkOut).all { it.t in kept })
        assertTrue(RouteSanitizer.sanitize(stop).rejections.none { it.reason == RouteSampleRejectionReason.OUT_AND_BACK_EXCURSION })
    }

    /** Without the phone's speed there is nothing to show the walker couldn't have gone there: nothing is removed. */
    @Test
    fun `without a reported speed an excursion is kept`() {
        val (walk, excursion) = walkWithExcursions()
        val noSpeed = (walk + excursion).sortedBy { it.t }.map { it.copy(speed = null) }

        val result = RouteSanitizer.sanitize(noSpeed.map(::TripPointOf))

        assertEquals(noSpeed.size, result.points.size)
    }

    @Test
    fun `an excursion whose own fixes report no speed is kept`() {
        val (walk, excursion) = walkWithExcursions()
        val fixes = (walk + excursion.map { it.copy(speed = null) }).sortedBy { it.t }

        val result = RouteSanitizer.sanitize(fixes.map(::TripPointOf))

        assertEquals(fixes.size, result.points.size)
    }

    private companion object {
        const val BASE_LATITUDE = 10.0
        const val BASE_LONGITUDE = 20.0
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /** 4.5 km/h, walked and reported. */
        const val WALK_METERS_PER_SECOND = 1.25
        const val WALK_SPEED = 1.25f
        val INTERVALS = longArrayOf(5, 8, 10, 6, 7, 9)
        val JITTER = doubleArrayOf(0.0, 2.5, -2.0, 1.0, -3.0, 2.0, -1.0)
        val ACCURACIES = floatArrayOf(13f, 18f, 22f, 26f, 16f, 20f)

        /** 4.0-4.8 km/h, as the phone reported. */
        val REPORTED = floatArrayOf(1.11f, 1.25f, 1.33f, 1.2f, 1.28f)
    }
}
