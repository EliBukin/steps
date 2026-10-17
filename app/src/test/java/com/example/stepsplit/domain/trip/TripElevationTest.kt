package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripElevationTest {

    /**
     * A recorded route heading east from ([startLatitude], [startLongitude]): a fix every [every]
     * seconds, [step] meters apart, for [meters] in all, each with the height [height] gives for its
     * distance from the start (null for no height).
     */
    private fun route(
        meters: Double,
        step: Double = 13.0,
        every: Long = 10L,
        startLatitude: Double = 31.77,
        startLongitude: Double = 35.10,
        height: (Double) -> Double?,
    ): List<TripPoint> {
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(startLatitude))
        val count = (meters / step).toInt() + 1
        return List(count) { i ->
            val along = i * step
            TripPoint(
                capturedAtEpochSecond = 1_000_000L + i * every,
                latitude = startLatitude,
                longitude = startLongitude + along / metersPerDegreeLongitude,
                accuracyMeters = 5f,
                altitudeMeters = height(along),
                speedMetersPerSecond = null,
            )
        }
    }

    /** GPS-like height noise: up to ±[white] m at random on every fix, plus a slow ±[wander] m swell over [wanderMeters] of route. */
    private fun noise(seed: Long, white: Double, wander: Double = 0.0, wanderMeters: Double = 500.0): (Double) -> Double {
        val random = Random(seed)
        val phase = random.nextDouble() * 2 * PI
        return { along -> (random.nextDouble() * 2 - 1) * white + wander * sin(2 * PI * along / wanderMeters + phase) }
    }

    /** What adding up every rise and fall gives - the far-too-high number this class exists to avoid. */
    private fun naiveClimb(points: List<TripPoint>): Double =
        points.mapNotNull { it.altitudeMeters }.zipWithNext { a, b -> (b - a).coerceAtLeast(0.0) }.sum()

    private fun measure(points: List<TripPoint>): TripElevation.Elevation {
        val elevation = TripElevation.measure(points)
        assertNotNull(elevation)
        return elevation!!
    }

    // ---- Noise ----

    @Test
    fun `a flat walk with noisy GPS height has almost no climb`() {
        // 4 km on level ground with every fix up to 15 m off: twenty different draws.
        val climbs = (1L..20L).map { seed ->
            val noisy = noise(seed, white = 15.0)
            val walk = route(4_000.0) { 750.0 + noisy(it) }
            val elevation = measure(walk)
            assertTrue("the noise is real: naive climb ${naiveClimb(walk)}", naiveClimb(walk) > 1_000.0)
            assertTrue("seed $seed: climb ${elevation.climbMeters}", elevation.climbMeters <= 15.0)
            assertTrue("seed $seed: descent ${elevation.descentMeters}", elevation.descentMeters <= 15.0)
            assertEquals(750.0, elevation.highestMeters, 10.0)
            assertEquals(750.0, elevation.lowestMeters, 10.0)
            elevation.climbMeters
        }
        assertTrue("mostly none at all: ${climbs.average()}", climbs.average() < 3.0)
    }

    @Test
    fun `a slow swell of the GPS height looks like gentle hills and is only partly left out`() {
        // The same walk with a ±3 m swell over every 500 m on top: the documented limit of GPS height.
        val climbs = (1L..20L).map { seed ->
            val noisy = noise(seed, white = 15.0, wander = 3.0)
            measure(route(4_000.0) { 750.0 + noisy(it) }).climbMeters
        }
        assertTrue("worst ${climbs.max()}", climbs.max() < 30.0)
        assertTrue("average ${climbs.average()}", climbs.average() < 15.0)
    }

    @Test
    fun `a steady climb counts the whole climb and no descent`() {
        // 3 km from 100 m up to 400 m (a 10% slope), every fix up to 10 m off.
        for (seed in 1L..10L) {
            val noisy = noise(seed, white = 10.0)
            val elevation = measure(route(3_000.0) { 100.0 + it * 0.1 + noisy(it) })
            assertEquals("seed $seed", 300.0, elevation.climbMeters, 20.0)
            assertTrue("seed $seed: descent ${elevation.descentMeters}", elevation.descentMeters < 10.0)
            assertEquals(400.0, elevation.highestMeters, 12.0)
            assertEquals(100.0, elevation.lowestMeters, 12.0)
        }
    }

    @Test
    fun `a hike down from Jerusalem towards the Dead Sea goes below sea level`() {
        // 16 km east from Jerusalem: down 200 m into a valley, up 80 m over a ridge, then down 880 m
        // to 250 m below sea level, every fix up to 10 m off.
        fun trueHeight(along: Double): Double = when {
            along <= 5_000.0 -> 750.0 - 200.0 * along / 5_000.0
            along <= 7_000.0 -> 550.0 + 80.0 * (along - 5_000.0) / 2_000.0
            else -> 630.0 - 880.0 * (along - 7_000.0) / 9_000.0
        }
        for (seed in 1L..10L) {
            val noisy = noise(seed, white = 10.0, wander = 2.0)
            val hike = route(16_000.0, step = 15.0, startLatitude = 31.78, startLongitude = 35.23) { trueHeight(it) + noisy(it) }
            val elevation = measure(hike)
            assertEquals("seed $seed", 1_080.0, elevation.descentMeters, 25.0)
            assertEquals("seed $seed", 80.0, elevation.climbMeters, 15.0)
            assertEquals(750.0, elevation.highestMeters, 10.0)
            assertEquals(-250.0, elevation.lowestMeters, 12.0)
            assertTrue(elevation.profile.first().heightMeters > 700.0)
            assertTrue(elevation.profile.last().heightMeters < -200.0)
        }
    }

    @Test
    fun `a drive down to the Dead Sea, with points far apart, is smoothed over as many points as a walk`() {
        // Arad (600 m) to the Dead Sea shore (-410 m) over 22 km at 60 km/h, a fix every 5 s (83 m).
        val noisy = noise(3, white = 10.0)
        val drive = route(22_000.0, step = 83.0, every = 5, startLatitude = 31.25, startLongitude = 35.21) { 600.0 - 1_010.0 * it / 22_000.0 + noisy(it) }
        val distances = DoubleArray(drive.size) { it * 83.0 }
        assertEquals(166.0, TripElevation.smoothingSigmaMeters(distances), 0.01)
        val elevation = measure(drive)
        assertEquals(1_010.0, elevation.descentMeters, 25.0)
        assertTrue(elevation.climbMeters < 10.0)
        assertEquals(-410.0, elevation.lowestMeters, 12.0)
    }

    // ---- Missing heights ----

    @Test
    fun `no heights at all, no elevation`() {
        assertNull(TripElevation.measure(route(2_000.0) { null }))
        assertNull(TripElevation.measure(emptyList()))
    }

    @Test
    fun `too few points with a height, no elevation`() {
        // Nine heights is too few, whatever the share.
        assertNull(TripElevation.measure(route(13.0 * 8) { 700.0 }.take(9)))
        // Under half the points with a height is too few, however many.
        val sparse = route(4_000.0) { along -> if ((along / 13.0).toInt() % 3 == 0) 700.0 else null }
        assertTrue(sparse.count { it.altitudeMeters != null } > 50)
        assertNull(TripElevation.measure(sparse))
    }

    @Test
    fun `heights spanning too little of the route, no elevation`() {
        // 20 points with heights but only 95 m between the first and last of them.
        assertNull(TripElevation.measure(route(95.0, step = 5.0) { 700.0 + it }))
    }

    @Test
    fun `a non-finite height counts as missing`() {
        val walk = route(2_000.0) { along -> if (along < 1_200.0) Double.NaN else 700.0 }
        assertNull(TripElevation.measure(walk))
    }

    @Test
    fun `points without a height are skipped, but their distance still counts`() {
        // Every third point has no height; the climb is still found, at the right distances.
        val walk = route(3_000.0) { along -> if ((along / 13.0).toInt() % 3 == 1) null else 500.0 + along * 0.05 }
        val elevation = measure(walk)
        assertEquals(150.0, elevation.climbMeters, 8.0)
        assertEquals(0.0, elevation.profile.first().distanceMeters, 0.5)
        assertEquals(2_990.0, elevation.profile.last().distanceMeters, 1.0)
    }

    // ---- The chart ----

    @Test
    fun `the chart has at most 100 points, in distance order, between the lowest and highest point`() {
        val noisy = noise(5, white = 10.0)
        val hike = route(8_000.0) { 600.0 + 100.0 * sin(it / 900.0) + noisy(it) }
        val elevation = measure(hike)
        assertEquals(TripElevation.PROFILE_SAMPLES, elevation.profile.size)
        elevation.profile.zipWithNext { a, b -> assertTrue(b.distanceMeters >= a.distanceMeters) }
        elevation.profile.forEach { assertTrue(it.heightMeters in elevation.lowestMeters - 1e-9..elevation.highestMeters + 1e-9) }
        assertEquals(0.0, elevation.profile.first().distanceMeters, 1e-9)
        assertEquals(hike.size - 1.0, elevation.profile.last().distanceMeters / 13.0, 0.01)
    }

    @Test
    fun `a short route's chart keeps every point`() {
        val elevation = measure(route(13.0 * 20) { 700.0 + it * 0.1 })
        assertEquals(21, elevation.profile.size)
    }

    // ---- The parts ----

    @Test
    fun `ups and downs smaller than the dead band are not counted`() {
        assertEquals(0.0 to 0.0, TripElevation.climbAndDescent(doubleArrayOf(0.0, 5.0, 0.0, 5.0, 0.0, 9.0, 1.0)))
    }

    @Test
    fun `a real climb is counted whole, from valley to peak`() {
        val (climb, descent) = TripElevation.climbAndDescent(doubleArrayOf(0.0, 20.0, 5.0, 30.0))
        assertEquals(45.0, climb, 1e-9)
        assertEquals(15.0, descent, 1e-9)
    }

    @Test
    fun `hills that never leave the starting height's dead band still count`() {
        // Up and down 12 m around the start, never 10 m away from it: counted from the first dip.
        val (climb, descent) = TripElevation.climbAndDescent(doubleArrayOf(0.0, -6.0, 6.0, -6.0, 6.0))
        assertEquals(24.0, climb, 1e-9)
        assertEquals(12.0, descent, 1e-9)
    }

    @Test
    fun `a small bump on the way down doesn't split the descent`() {
        val (climb, descent) = TripElevation.climbAndDescent(doubleArrayOf(100.0, 90.0, 80.0, 85.0, 60.0))
        assertEquals(0.0, climb, 1e-9)
        assertEquals(40.0, descent, 1e-9)
    }

    @Test
    fun `the last stretch counts once it is out of the dead band`() {
        assertEquals(50.0 to 0.0, TripElevation.climbAndDescent(doubleArrayOf(0.0, 50.0)))
        assertEquals(0.0 to 0.0, TripElevation.climbAndDescent(doubleArrayOf(0.0, 9.0)))
    }

    @Test
    fun `heights from a long stop at one place are averaged together, not drawn as hills`() {
        // Twelve fixes at the same spot, wandering 20 m up and down, between two flat walks.
        val distances = DoubleArray(40) { i -> if (i < 14) i * 13.0 else if (i < 26) 13.0 * 13 else (i - 12) * 13.0 }
        val heights = DoubleArray(40) { i -> if (i in 14 until 26) 700.0 + if (i % 2 == 0) 20.0 else -20.0 else 700.0 }
        val smoothed = TripElevation.smooth(distances, heights, TripElevation.MIN_SMOOTHING_SIGMA_METERS)
        for (i in 14 until 26) assertEquals(700.0, smoothed[i], 2.0)
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
    }
}
