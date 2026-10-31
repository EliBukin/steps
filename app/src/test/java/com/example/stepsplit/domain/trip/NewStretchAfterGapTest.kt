package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a gap - a Resume once the phone has travelled, a bus ride with no fix - the walk on from
 * there starts a new stretch instead of being rejected as one long jump: see
 * [RoutePointAcceptancePolicy]'s "After a gap" section. Read-time side ([RouteSanitizer]); the live
 * side is in `TripRepositoryTest`. Made-up points in open sea.
 */
class NewStretchAfterGapTest {

    private fun point(northMeters: Double, t: Long, accuracy: Float = 8f, speed: Float? = 1.3f) = TripPoint(
        capturedAtEpochSecond = t,
        latitude = BASE_LATITUDE + Math.toDegrees(northMeters / EARTH_RADIUS_METERS),
        longitude = BASE_LONGITUDE,
        accuracyMeters = accuracy,
        altitudeMeters = null,
        speedMetersPerSecond = speed,
    )

    /** [count] fixes walking north at 1.3 m/s from [fromMeters], one every 10 s from [startT]. */
    private fun walk(fromMeters: Double, startT: Long, count: Int) = (0 until count).map { point(fromMeters + 13.0 * it, startT + 10L * it) }

    private fun pathMeters(points: List<TripPoint>) =
        points.zipWithNext { a, b -> RouteMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }.sum()

    @Test
    fun `a walk on 20 km away after 20 minutes is kept, with the gap's straight line in the distance`() {
        val before = walk(0.0, 1_000L, 6)
        val after = walk(20_000.0, before.last().capturedAtEpochSecond + 20 * 60, 30)

        val result = RouteSanitizer.sanitize(before + after)

        assertEquals(before + after, result.points)
        assertEquals(pathMeters(before + after), result.distanceMeters, 1e-6)
        assertTrue("drawn as a gap", RouteSpeedColoring.isGap(before.last(), after.first()))
        val displayed = RouteSmoother.smooth(result.points)
        assertEquals(before.size + after.size, displayed.points.size)
        assertEquals(pathMeters(before + after), displayed.distanceMeters, 5.0)
    }

    @Test
    fun `the same with no reported speed at all`() {
        val before = walk(0.0, 1_000L, 6).map { it.copy(speedMetersPerSecond = null) }
        val after = walk(10_000.0, before.last().capturedAtEpochSecond + 15 * 60, 10).map { it.copy(speedMetersPerSecond = null) }

        assertEquals(before + after, RouteSanitizer.sanitize(before + after).points)
    }

    @Test
    fun `a spike within a minute of the last point is still removed, even two that agree`() {
        val path = walk(0.0, 1_000L, 12)
        val spikes = listOf(point(500.0, path[5].capturedAtEpochSecond + 3, accuracy = 5f), point(510.0, path[5].capturedAtEpochSecond + 6, accuracy = 5f))
        val stored = (path + spikes).sortedBy { it.capturedAtEpochSecond }

        val result = RouteSanitizer.sanitize(stored)

        assertEquals(path, result.points)
    }

    @Test
    fun `one wild point after a quiet minute, that the next point doesn't follow, is removed`() {
        val before = walk(0.0, 1_000L, 6)
        val quietUntil = before.last().capturedAtEpochSecond + 120
        val wild = point(2_000.0, quietUntil, accuracy = 6f)
        val walkOn = walk(13.0 * 5 + 10.0, quietUntil + 10, 6)

        val result = RouteSanitizer.sanitize(before + wild + walkOn)

        assertEquals(before + walkOn, result.points)
    }

    @Test
    fun `a point after a gap that the next point follows only after another gap is not a new stretch`() {
        val before = walk(0.0, 1_000L, 6)
        val first = point(20_000.0, before.last().capturedAtEpochSecond + 20 * 60)
        val next = point(20_013.0, first.capturedAtEpochSecond + 61)

        assertEquals(before, RouteSanitizer.sanitize(before + first + next).points)
    }

    @Test
    fun `a point after a gap that is only acceptably accurate, not well, is not a new stretch`() {
        val before = walk(0.0, 1_000L, 6)
        val rough = point(20_000.0, before.last().capturedAtEpochSecond + 20 * 60, accuracy = 25f)
        val after = walk(20_013.0, rough.capturedAtEpochSecond + 10, 10)

        val result = RouteSanitizer.sanitize(before + rough + after)

        assertEquals("the walk on starts at the first well-placed point", before + after, result.points)
    }

    @Test
    fun `the rule's boundaries`() {
        val tooFast = RouteSampleRejectionReason.IMPLAUSIBLE_JUMP
        assertFalse(RoutePointAcceptancePolicy.mayStartNewStretch(tooFast, elapsedSeconds = 60, accuracyMeters = 8f))
        assertTrue(RoutePointAcceptancePolicy.mayStartNewStretch(tooFast, elapsedSeconds = 61, accuracyMeters = 8f))
        assertTrue(RoutePointAcceptancePolicy.mayStartNewStretch(RouteSampleRejectionReason.SPEED_CONTRADICTION, 61, 20f))
        assertFalse(RoutePointAcceptancePolicy.mayStartNewStretch(tooFast, 61, 20.5f))
        assertFalse(RoutePointAcceptancePolicy.mayStartNewStretch(RouteSampleRejectionReason.STATIONARY_WOBBLE, 600, 8f))
        assertFalse(RoutePointAcceptancePolicy.mayStartNewStretch(RouteSampleRejectionReason.POOR_ACCURACY, 600, 8f))

        assertTrue(RoutePointAcceptancePolicy.confirmsNewStretch(ordinaryMove = true, elapsedSeconds = 60))
        assertFalse(RoutePointAcceptancePolicy.confirmsNewStretch(ordinaryMove = true, elapsedSeconds = 61))
        assertFalse(RoutePointAcceptancePolicy.confirmsNewStretch(ordinaryMove = true, elapsedSeconds = 0))
        assertFalse(RoutePointAcceptancePolicy.confirmsNewStretch(ordinaryMove = false, elapsedSeconds = 10))
    }

    private companion object {
        const val BASE_LATITUDE = 10.0
        const val BASE_LONGITUDE = 20.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
