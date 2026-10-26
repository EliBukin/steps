package com.example.stepsplit.ui.map

import com.example.stepsplit.data.trip.MapFix
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingTest {

    /** A direction in the world: east, north, up. */
    private data class Vec(val e: Float, val n: Float, val u: Float)

    private val east = Vec(1f, 0f, 0f)
    private val west = Vec(-1f, 0f, 0f)
    private val north = Vec(0f, 1f, 0f)
    private val south = Vec(0f, -1f, 0f)
    private val up = Vec(0f, 0f, 1f)
    private val s = (1 / sqrt(2.0)).toFloat()

    /** The rotation matrix of a phone whose x, y and z axes point in these world directions. */
    private fun matrix(x: Vec, y: Vec, z: Vec) = floatArrayOf(x.e, y.e, z.e, x.n, y.n, z.n, x.u, y.u, z.u)

    private fun azimuth(x: Vec, y: Vec, z: Vec, displayRotation: Int = 0) = HeadingMath.azimuthDegrees(matrix(x, y, z), displayRotation)

    @Test
    fun `flat in the hand, the heading is where the screen's top edge points`() {
        assertEquals(0f, azimuth(x = east, y = north, z = up)!!, 0.01f)
        assertEquals(90f, azimuth(x = south, y = east, z = up)!!, 0.01f)
        assertEquals(180f, azimuth(x = west, y = south, z = up)!!, 0.01f)
        assertEquals(270f, azimuth(x = north, y = west, z = up)!!, 0.01f)
    }

    @Test
    fun `held upright like a camera, the heading is where the phone's back faces`() {
        // Top edge up, screen toward the user standing west of it: the phone's back faces west.
        assertEquals(270f, azimuth(x = north, y = up, z = east)!!, 0.01f)
    }

    @Test
    fun `tilted halfway, the top edge and the back agree`() {
        // Facing south, the phone at 45 degrees: top edge south and up, screen toward the user (north) and up.
        assertEquals(180f, azimuth(x = west, y = Vec(0f, -s, s), z = Vec(0f, s, s))!!, 0.01f)
    }

    @Test
    fun `turned sideways, the heading follows the screen's top, not the phone's`() {
        // Flat, the phone's right edge (x) pointing north: in landscape (rotation 90) that edge is the screen's top.
        assertEquals(0f, azimuth(x = north, y = west, z = up, displayRotation = 1)!!, 0.01f)
        assertEquals(270f, azimuth(x = north, y = west, z = up, displayRotation = 0)!!, 0.01f)
        assertEquals(180f, azimuth(x = north, y = west, z = up, displayRotation = 3)!!, 0.01f)
        assertEquals(90f, azimuth(x = north, y = west, z = up, displayRotation = 2)!!, 0.01f)
    }

    @Test
    fun `no heading when the phone faces straight up or down`() {
        // Held overhead, screen down, top edge north and up: the facing direction is straight up.
        assertNull(azimuth(x = west, y = Vec(0f, s, s), z = Vec(0f, s, -s)))
    }

    @Test
    fun `angles wrap around north`() {
        assertEquals(350f, HeadingMath.normalize(-10f), 0.001f)
        assertEquals(10f, HeadingMath.normalize(370f), 0.001f)
        assertEquals(20f, HeadingMath.shortestTurn(350f, 10f), 0.001f)
        assertEquals(-20f, HeadingMath.shortestTurn(10f, 350f), 0.001f)
        assertEquals(180f, HeadingMath.shortestTurn(0f, 180f), 0.001f)
    }

    @Test
    fun `smoothing goes the short way round, and starts at the first reading`() {
        assertEquals(42f, HeadingMath.smooth(null, 42f, 0.3f), 0.001f)
        assertEquals(0f, HeadingMath.smooth(350f, 10f, 0.5f), 0.001f)
        assertEquals(353f, HeadingMath.smooth(350f, 10f, 0.15f), 0.001f)
    }

    private fun fix(speedKmh: Float?, course: Float?, ageSeconds: Long = 1) = MapFix(
        latitude = 30.61,
        longitude = 34.80,
        accuracyMeters = 5f,
        bearingDegrees = course,
        elapsedRealtimeNanos = NOW - ageSeconds * 1_000_000_000L,
        speedMetersPerSecond = speedKmh?.div(3.6f),
    )

    private fun choose(compass: Float?, fix: MapFix?, previous: MapBearing.Source = MapBearing.Source.COMPASS) =
        MapBearing.choose(compass, fix, NOW, previous)

    @Test
    fun `walking or standing, the map turns with the compass`() {
        assertEquals(MapBearing.Choice(30f, MapBearing.Source.COMPASS), choose(30f, fix(speedKmh = 5f, course = 200f)))
        assertEquals(MapBearing.Choice(30f, MapBearing.Source.COMPASS), choose(30f, fix(speedKmh = 0f, course = null)))
        assertEquals(MapBearing.Choice(30f, MapBearing.Source.COMPASS), choose(30f, null))
    }

    @Test
    fun `moving fast, the map turns with the direction of travel`() {
        assertEquals(MapBearing.Choice(200f, MapBearing.Source.COURSE), choose(30f, fix(speedKmh = 40f, course = 200f)))
        assertEquals(MapBearing.Choice(200f, MapBearing.Source.COURSE), choose(30f, fix(speedKmh = 15f, course = 200f)))
    }

    @Test
    fun `between 10 and 15 km per hour it keeps what it used, so it doesn't flip back and forth`() {
        val twelve = fix(speedKmh = 12f, course = 200f)
        assertEquals(MapBearing.Source.COMPASS, choose(30f, twelve, previous = MapBearing.Source.COMPASS).source)
        assertEquals(MapBearing.Source.COURSE, choose(30f, twelve, previous = MapBearing.Source.COURSE).source)
        assertEquals(MapBearing.Source.COMPASS, choose(30f, fix(speedKmh = 9f, course = 200f), previous = MapBearing.Source.COURSE).source)
    }

    @Test
    fun `an old fix's direction of travel doesn't count`() {
        assertEquals(MapBearing.Choice(30f, MapBearing.Source.COMPASS), choose(30f, fix(speedKmh = 40f, course = 200f, ageSeconds = 6)))
    }

    @Test
    fun `without a compass the direction of travel is used whenever there is one`() {
        assertEquals(MapBearing.Choice(200f, MapBearing.Source.COURSE), choose(null, fix(speedKmh = 4f, course = 200f)))
    }

    @Test
    fun `with neither, the map stays turned as it is`() {
        assertEquals(MapBearing.Choice(null, MapBearing.Source.COURSE), choose(null, fix(speedKmh = 0f, course = null), previous = MapBearing.Source.COURSE))
        assertEquals(MapBearing.Choice(null, MapBearing.Source.COMPASS), choose(null, null))
    }

    private companion object {
        const val NOW = 1_000_000_000_000L
    }
}
