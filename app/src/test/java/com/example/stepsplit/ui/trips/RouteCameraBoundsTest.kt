package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCameraBoundsTest {

    private fun point(lat: Double, lon: Double) = TripPoint(
        capturedAtEpochSecond = 0L,
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
        altitudeMeters = null,
        speedMetersPerSecond = null,
    )

    private fun bounded(points: List<TripPoint>): RouteCameraBounds.Fit.Bounded =
        RouteCameraBounds.compute(points) as RouteCameraBounds.Fit.Bounded

    @Test
    fun `an empty route has no bounds to fit`() {
        assertNull(RouteCameraBounds.compute(emptyList()))
    }

    @Test
    fun `a single point produces a small, valid, non-degenerate box centered on it`() {
        val bounds = bounded(listOf(point(32.05, 34.78))).boundingBox
        assertTrue("west must be strictly less than east", bounds.west < bounds.east)
        assertTrue("south must be strictly less than north", bounds.south < bounds.north)
        val centerLat = (bounds.south + bounds.north) / 2.0
        val centerLon = (bounds.west + bounds.east) / 2.0
        assertEquals(32.05, centerLat, 1e-6)
        assertEquals(34.78, centerLon, 1e-6)
    }

    @Test
    fun `identical repeated coordinates behave exactly like a single point`() {
        val single = bounded(listOf(point(10.0, 20.0))).boundingBox
        val repeated = bounded(listOf(point(10.0, 20.0), point(10.0, 20.0), point(10.0, 20.0))).boundingBox
        assertEquals(single.west, repeated.west, 1e-9)
        assertEquals(single.south, repeated.south, 1e-9)
        assertEquals(single.east, repeated.east, 1e-9)
        assertEquals(single.north, repeated.north, 1e-9)
    }

    @Test
    fun `a very small real span is floored to a sensible minimum, not left near-zero`() {
        // ~1 meter apart - far smaller than the minimum span floor.
        val bounds = bounded(listOf(point(10.0, 20.0), point(10.00001, 20.00001))).boundingBox
        val latSpan = bounds.north - bounds.south
        assertTrue("expected a floored, readable span but got $latSpan", latSpan > 0.001)
    }

    @Test
    fun `a very large span is padded proportionally without collapsing or throwing`() {
        val bounds = bounded(listOf(point(-40.0, -70.0), point(55.0, 60.0))).boundingBox
        assertTrue(bounds.south < -40.0)
        assertTrue(bounds.north > 55.0)
        assertTrue(bounds.west < -70.0)
        assertTrue(bounds.east > 60.0)
    }

    @Test
    fun `bounds near the poles never exceed valid latitude range`() {
        val bounds = bounded(listOf(point(89.9999, 10.0))).boundingBox
        assertTrue(bounds.north <= 90.0)
        assertTrue(bounds.south >= -90.0)
    }

    @Test
    fun `bounds near the antimeridian for a single point never exceed valid longitude range`() {
        val bounds = bounded(listOf(point(0.0, 179.9999))).boundingBox
        assertTrue(bounds.east <= 180.0)
        assertTrue(bounds.west >= -180.0)
    }

    @Test
    fun `a multi-point route frames every point within its bounds`() {
        val points = listOf(point(10.0, 20.0), point(10.5, 20.3), point(9.8, 20.6))
        val bounds = bounded(points).boundingBox
        for (p in points) {
            assertTrue(p.latitude in bounds.south..bounds.north)
            assertTrue(p.longitude in bounds.west..bounds.east)
        }
    }

    @Test
    fun `a route crossing the antimeridian frames the narrow route area, not the whole world`() {
        // 179.9E and 179.9W are ~0.2 degrees apart the short way around (via 180), but ~359.8
        // degrees apart by naive min-max - the bug this hardening fixes.
        val fit = RouteCameraBounds.compute(listOf(point(0.0, 179.9), point(0.0, -179.9)))
        assertTrue("a dateline-crossing route must use the Centered fallback, not a wrapping BoundingBox", fit is RouteCameraBounds.Fit.Centered)
        val centered = fit as RouteCameraBounds.Fit.Centered
        assertTrue(
            "camera should center near +-180, not near 0 (the whole-world misinterpretation)",
            centered.target.longitude > 170.0 || centered.target.longitude < -170.0,
        )
        assertTrue("a ~0.2 degree route should zoom in close", centered.zoom > 8.0)
    }

    @Test
    fun `a route crossing the antimeridian keeps a valid, readable zoom for a wider dateline span`() {
        val fit = RouteCameraBounds.compute(listOf(point(10.0, 179.0), point(10.5, -178.0)))
        assertTrue(fit is RouteCameraBounds.Fit.Centered)
        val centered = fit as RouteCameraBounds.Fit.Centered
        assertTrue(centered.zoom in 0.0..16.0)
        assertTrue(centered.target.longitude in -180.0..180.0)
    }

    @Test
    fun `a wide route that does not cross the antimeridian still uses the precise bounded fit`() {
        // -170 to -160 is a legitimate 10-degree-wide span with no dateline crossing at all.
        val fit = RouteCameraBounds.compute(listOf(point(0.0, -170.0), point(0.0, -160.0)))
        assertTrue(fit is RouteCameraBounds.Fit.Bounded)
        val bounds = (fit as RouteCameraBounds.Fit.Bounded).boundingBox
        assertTrue(bounds.west < bounds.east)
        assertTrue(bounds.west <= -170.0)
        assertTrue(bounds.east >= -160.0)
    }
}
