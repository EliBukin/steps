package com.example.stepsplit.ui.map

import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.ui.trips.RouteCameraBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.spatialk.geojson.Position

class OpenMapCameraTest {

    private val fix = MapFix(latitude = 31.77, longitude = 35.21, accuracyMeters = 6f, bearingDegrees = null, elapsedRealtimeNanos = 0L)
    private val route = listOf(point(31.70, 35.10), point(31.72, 35.12))

    @Test
    fun `the map opens on the phone's fix when there is one, ready to follow`() {
        val position = OpenMapCamera.initialPosition(fix, route)

        assertEquals(Position(longitude = 35.21, latitude = 31.77), position.target)
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, position.zoom, 0.0)
    }

    @Test
    fun `without a fix it opens on the newest point of the trip being recorded`() {
        val position = OpenMapCamera.initialPosition(null, route)

        assertEquals(Position(longitude = 35.12, latitude = 31.72), position.target)
        assertTrue(position.zoom >= OpenMapCamera.MIN_FOLLOW_ZOOM)
    }

    @Test
    fun `with nothing to show it opens on the whole world, zoomed out far enough that the first fix jumps there`() {
        val position = OpenMapCamera.initialPosition(null, emptyList())

        assertTrue(position.zoom < OpenMapCamera.MIN_FOLLOW_ZOOM)
    }

    @Test
    fun `following zooms in from far out, and otherwise keeps the user's zoom`() {
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, OpenMapCamera.followZoom(1.0, fix), 0.0)
        assertTrue(OpenMapCamera.isFarOut(1.0, fix))
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, OpenMapCamera.followZoom(OpenMapCamera.MIN_FOLLOW_ZOOM - 0.1, fix), 0.0)
        assertEquals(OpenMapCamera.MIN_FOLLOW_ZOOM, OpenMapCamera.followZoom(OpenMapCamera.MIN_FOLLOW_ZOOM, fix), 0.0)
        assertFalse(OpenMapCamera.isFarOut(OpenMapCamera.MIN_FOLLOW_ZOOM, fix))
        assertEquals(18.5, OpenMapCamera.followZoom(18.5, fix), 0.0)
    }

    @Test
    fun `an approximate fix opens zoomed out far enough that its circle fits on the map`() {
        val approximate = fix.copy(accuracyMeters = 2_000f)

        val zoom = OpenMapCamera.initialPosition(approximate, emptyList()).zoom

        assertTrue(zoom < OpenMapCamera.MIN_FOLLOW_ZOOM)
        val radiusDp = 2_000.0 / metersPerDp(approximate.latitude, zoom)
        assertEquals(OpenMapCamera.MAX_ACCURACY_RADIUS_DP, radiusDp, 0.5)
        // Following it from there doesn't count as far out, so the map glides along rather than jumping.
        assertFalse(OpenMapCamera.isFarOut(zoom, approximate))
        assertEquals(zoom, OpenMapCamera.followZoom(zoom, approximate), 0.0)
    }

    @Test
    fun `a GPS fix, or one with no accuracy, follows at street zoom`() {
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, OpenMapCamera.fittingZoom(fix), 0.0)
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, OpenMapCamera.fittingZoom(fix.copy(accuracyMeters = 30f)), 0.0)
        assertEquals(OpenMapCamera.FOLLOW_ZOOM, OpenMapCamera.fittingZoom(fix.copy(accuracyMeters = null)), 0.0)
    }

    @Test
    fun `show whole route frames the route and where the phone is now`() {
        val fit = OpenMapCamera.wholeRouteFit(route, fix) as RouteCameraBounds.Fit.Bounded

        val box = fit.boundingBox
        assertTrue(box.south < 31.70 && box.north > 31.77)
        assertTrue(box.west < 35.10 && box.east > 35.21)
    }

    @Test
    fun `show whole route without a fix frames just the route`() {
        val withoutFix = OpenMapCamera.wholeRouteFit(route, null)

        assertEquals(RouteCameraBounds.compute(route), withoutFix)
        assertNull(OpenMapCamera.wholeRouteFit(emptyList(), null))
    }

    /** MapLibre's meters per dp: 512 dp tiles. */
    private fun metersPerDp(latitude: Double, zoom: Double) = 40_075_016.686 * Math.cos(Math.toRadians(latitude)) / (512.0 * Math.pow(2.0, zoom))

    private fun point(latitude: Double, longitude: Double) =
        TripPoint(capturedAtEpochSecond = 0L, latitude = latitude, longitude = longitude, accuracyMeters = 5f, altitudeMeters = null, speedMetersPerSecond = null)
}
