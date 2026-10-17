package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.cbrt
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSpeedColoringTest {

    /** Builds a route heading north from (31.0, 35.0): each leg is (seconds, meters) after the previous point. */
    private fun route(vararg legs: Pair<Long, Double>): List<TripPoint> {
        var time = 1_000_000L
        var meters = 0.0
        val points = mutableListOf(point(time, meters))
        legs.forEach { (seconds, distance) ->
            time += seconds
            meters += distance
            points += point(time, meters)
        }
        return points
    }

    private fun point(time: Long, metersNorth: Double) = TripPoint(
        capturedAtEpochSecond = time,
        latitude = 31.0 + metersNorth / METERS_PER_DEGREE_LATITUDE,
        longitude = 35.0,
        accuracyMeters = 5f,
        altitudeMeters = null,
        speedMetersPerSecond = null,
    )

    private fun legs(count: Int, seconds: Long, meters: Double) = Array(count) { seconds to meters }

    // ---- Speed ----

    @Test
    fun `a steady walk has the same speed on every stretch`() {
        val speeds = RouteSpeedColoring.smoothedSpeeds(route(*legs(10, 10, 14.0)))
        assertEquals(10, speeds.size)
        speeds.forEach { assertEquals(1.4, it!!, 0.01) }
    }

    @Test
    fun `fewer than two points have no stretches`() {
        assertTrue(RouteSpeedColoring.smoothedSpeeds(emptyList()).isEmpty())
        assertTrue(RouteSpeedColoring.smoothedSpeeds(route()).isEmpty())
    }

    @Test
    fun `one fast stretch is softened by its neighbours but still stands out`() {
        val speeds = RouteSpeedColoring.smoothedSpeeds(route(*legs(5, 10, 10.0), 10L to 100.0, *legs(5, 10, 10.0)))
        val fast = speeds[5]!!
        assertTrue("softened: $fast", fast < 10.0)
        // A 30 s window at 10 s fixes spreads it evenly over itself and its two neighbours.
        assertTrue("still the fastest: $speeds", speeds.filterNotNull().all { it <= fast + 1e-6 })
        assertTrue("clearly faster than the walk: $fast", fast > 3.0)
        assertEquals("far from it, unchanged", 1.0, speeds[0]!!, 0.01)
        assertEquals("far from it, unchanged", 1.0, speeds[10]!!, 0.01)
    }

    @Test
    fun `a stop keeps its own near-zero speed and only nudges its neighbours`() {
        // Five minutes at the same spot, give or take GPS drift, between two walks.
        val speeds = RouteSpeedColoring.smoothedSpeeds(route(*legs(5, 10, 14.0), 300L to 4.0, *legs(5, 10, 14.0)))
        assertEquals(4.0 / 300, speeds[5]!!, 0.001)
        assertTrue("neighbours slow down a little: ${speeds[4]}", speeds[4]!! < 1.4 && speeds[4]!! > 0.7)
        assertEquals(1.4, speeds[0]!!, 0.01)
    }

    @Test
    fun `two fixes with the same timestamp don't break the speed`() {
        val speeds = RouteSpeedColoring.smoothedSpeeds(route(10L to 14.0, 0L to 2.0, 10L to 14.0))
        speeds.forEach { assertTrue("finite and plausible: $it", it!!.isFinite() && it in 1.0..2.5) }
    }

    // ---- Gaps ----

    @Test
    fun `a long time with real movement is a gap, with no speed`() {
        val points = route(*legs(3, 10, 14.0), 600L to 2_000.0, *legs(3, 10, 14.0))
        assertTrue(RouteSpeedColoring.isGap(points[3], points[4]))
        assertNull(RouteSpeedColoring.smoothedSpeeds(points)[3])
    }

    @Test
    fun `a long wait with little movement is a stop, not a gap`() {
        val points = route(600L to 30.0)
        assertTrue(!RouteSpeedColoring.isGap(points[0], points[1]))
        assertEquals(30.0 / 600, RouteSpeedColoring.smoothedSpeeds(points).single()!!, 0.001)
    }

    @Test
    fun `fast driving with fixes on time is not a gap`() {
        val points = route(10L to 250.0)
        assertTrue(!RouteSpeedColoring.isGap(points[0], points[1]))
    }

    @Test
    fun `the gap rule needs both limits to be passed`() {
        val minuteButFar = route(60L to 500.0)
        assertTrue(!RouteSpeedColoring.isGap(minuteButFar[0], minuteButFar[1]))
        val longButNear = route(61L to 99.0)
        assertTrue(!RouteSpeedColoring.isGap(longButNear[0], longButNear[1]))
        val justOver = route(61L to 101.0)
        assertTrue(RouteSpeedColoring.isGap(justOver[0], justOver[1]))
    }

    @Test
    fun `smoothing never reaches across a gap`() {
        // A slow walk, a gap, then a fast drive: the walk's last stretch must not borrow the drive's speed.
        val speeds = RouteSpeedColoring.smoothedSpeeds(route(*legs(4, 10, 10.0), 900L to 5_000.0, *legs(4, 10, 200.0)))
        assertEquals(1.0, speeds[3]!!, 0.01)
        assertEquals(20.0, speeds[5]!!, 0.01)
    }

    // ---- Scale ----

    @Test
    fun `the scale runs from the 5th to the 95th percentile, so one outlier doesn't flatten the rest`() {
        val walk = (0..99).map { 0.6 + it * 0.01 } // 0.60 .. 1.59 m/s
        val scale = RouteSpeedColoring.scaleFor(walk + 30.0)!!
        assertEquals(0.65, scale.slowMetersPerSecond, 0.01)
        assertTrue("the 30 m/s outlier is ignored: ${scale.fastMetersPerSecond}", scale.fastMetersPerSecond < 1.6)
        assertEquals(0.0, scale.fractionOf(0.2), 0.0)
        assertEquals(1.0, scale.fractionOf(30.0), 0.0)
    }

    @Test
    fun `a jeep drive gets its own scale`() {
        val drive = (0..99).map { 3.0 + it * 0.15 } // about 11 to 64 km/h
        val scale = RouteSpeedColoring.scaleFor(drive)!!
        assertTrue(scale.slowMetersPerSecond > 3.5 && scale.fastMetersPerSecond > 16.0)
    }

    @Test
    fun `a trip at one steady speed gets a narrow scale ending at it`() {
        val scale = RouteSpeedColoring.scaleFor(List(20) { 1.4 })!!
        assertEquals(RouteSpeedColoring.MIN_SCALE_SPAN_METERS_PER_SECOND, scale.fastMetersPerSecond - scale.slowMetersPerSecond, 1e-9)
        // The fast end can't be above the trip's top speed, so the steady speed is the fast end.
        assertEquals(1.4, scale.fastMetersPerSecond, 1e-9)
    }

    @Test
    fun `a narrow scale is widened around its middle when the fastest stretch leaves room`() {
        // 4.0-4.3 km/h with one stretch at 6 km/h: the 1 km/h span fits under the fastest stretch.
        val speeds = List(40) { (4.0 + (it % 4) * 0.1) / 3.6 } + 6.0 / 3.6
        val scale = RouteSpeedColoring.scaleFor(speeds)!!
        assertEquals(RouteSpeedColoring.MIN_SCALE_SPAN_METERS_PER_SECOND, scale.fastMetersPerSecond - scale.slowMetersPerSecond, 1e-9)
        assertEquals(0.5, scale.fractionOf((4.0 + 4.3) / 2 / 3.6), 0.01)
    }

    @Test
    fun `the widened fast end is never above the fastest stretch`() {
        val steadyish = List(30) { (5.0 + (it % 3) * 0.1) / 3.6 }
        val scale = RouteSpeedColoring.scaleFor(steadyish)!!
        assertTrue(scale.fastMetersPerSecond <= steadyish.max() + 1e-12)
        assertEquals(RouteSpeedColoring.MIN_SCALE_SPAN_METERS_PER_SECOND, scale.fastMetersPerSecond - scale.slowMetersPerSecond, 1e-9)
    }

    @Test
    fun `a scale near zero never goes below zero`() {
        val scale = RouteSpeedColoring.scaleFor(listOf(0.0, 0.01, 0.02))!!
        assertEquals(0.0, scale.slowMetersPerSecond, 0.0)
        assertEquals(RouteSpeedColoring.MIN_SCALE_SPAN_METERS_PER_SECOND, scale.fastMetersPerSecond, 1e-9)
    }

    @Test
    fun `no speeds, no scale`() {
        assertNull(RouteSpeedColoring.scaleFor(emptyList()))
    }

    // ---- Colors ----

    @Test
    fun `colors run red, yellow, green and stop at the ends`() {
        assertEquals(RouteSpeedColoring.SLOW_COLOR, RouteSpeedColoring.colorAt(0.0))
        assertEquals(RouteSpeedColoring.MIDDLE_COLOR, RouteSpeedColoring.colorAt(0.5))
        assertEquals(RouteSpeedColoring.FAST_COLOR, RouteSpeedColoring.colorAt(1.0))
        assertEquals(RouteSpeedColoring.SLOW_COLOR, RouteSpeedColoring.colorAt(-1.0))
        assertEquals(RouteSpeedColoring.FAST_COLOR, RouteSpeedColoring.colorAt(2.0))
        assertEquals(0xFF, RouteSpeedColoring.colorAt(0.3) ushr 24)
    }

    @Test
    fun `slow, middle and fast differ in brightness, not only in hue`() {
        val slow = lightness(RouteSpeedColoring.SLOW_COLOR)
        val middle = lightness(RouteSpeedColoring.MIDDLE_COLOR)
        val fast = lightness(RouteSpeedColoring.FAST_COLOR)
        assertTrue("slow $slow vs fast $fast", fast - slow >= 20)
        assertTrue("middle $middle is the brightest", middle - fast >= 15)
        // Red to yellow brightens steadily, so slow-ish and middle stay apart too.
        val steps = (0..10).map { lightness(RouteSpeedColoring.colorAt(it / 20.0)) }
        assertTrue("red to yellow brightens steadily: $steps", steps.zipWithNext().all { (a, b) -> b > a })
    }

    // ---- Putting it together ----

    @Test
    fun `colorize splits the route into colored runs and gray gaps`() {
        val points = route(*legs(6, 10, 8.0), 900L to 3_000.0, *legs(6, 10, 20.0))
        val colored = RouteSpeedColoring.colorize(points)
        assertNotNull(colored.scale)
        assertEquals(1, colored.gaps.size)
        assertEquals(points[6].latitude, colored.gaps.single().from.latitude, 0.0)
        assertEquals(points[7].latitude, colored.gaps.single().to.latitude, 0.0)
        // The slow walk is red, the fast part green, each one merged run.
        assertEquals(2, colored.runs.size)
        assertEquals(RouteSpeedColoring.SLOW_COLOR, colored.runs.first().color)
        assertEquals(RouteSpeedColoring.FAST_COLOR, colored.runs.last().color)
        assertEquals(7, colored.runs.first().points.size)
        assertEquals(7, colored.runs.last().points.size)
    }

    @Test
    fun `runs share their joining point, so the line has no holes`() {
        val points = route(*legs(5, 10, 5.0), *legs(5, 10, 15.0), *legs(5, 10, 25.0))
        val runs = RouteSpeedColoring.colorize(points).runs
        assertTrue(runs.size >= 2)
        runs.zipWithNext().forEach { (a, b) -> assertEquals(a.points.last(), b.points.first()) }
        assertEquals(points.size, runs.sumOf { it.points.size } - (runs.size - 1))
    }

    @Test
    fun `a route with fewer than two points has nothing to color`() {
        val colored = RouteSpeedColoring.colorize(route())
        assertTrue(colored.runs.isEmpty() && colored.gaps.isEmpty())
        assertNull(colored.scale)
    }

    @Test
    fun `a route that is only a gap is drawn as a gap with no scale`() {
        val colored = RouteSpeedColoring.colorize(route(900L to 3_000.0))
        assertTrue(colored.runs.isEmpty())
        assertEquals(1, colored.gaps.size)
        assertNull(colored.scale)
    }

    /** CIE L* (0 black .. 100 white) of an sRGB ARGB color. */
    private fun lightness(argb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val y = 0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)
        return if (y > 0.008856) 116 * cbrt(y) - 16 else 903.3 * y
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
    }
}
