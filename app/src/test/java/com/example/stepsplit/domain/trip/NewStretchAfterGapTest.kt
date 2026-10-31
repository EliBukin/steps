package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType
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

    /** [points] without the read-time [TripPoint.startsStretch] mark, to compare with what went in. */
    private fun unmarked(points: List<TripPoint>) = points.map { it.copy(startsStretch = false) }

    @Test
    fun `a walk on 20 km away after 20 minutes is kept, and the jump counts in no distance or moving time`() {
        val before = walk(0.0, 1_000L, 6)
        val after = walk(20_000.0, before.last().capturedAtEpochSecond + 20 * 60, 30)

        val result = RouteSanitizer.sanitize(before + after)

        assertEquals(before + after, unmarked(result.points))
        assertEquals(listOf(before.size), result.points.indices.filter { result.points[it].startsStretch })
        assertEquals(pathMeters(before) + pathMeters(after), result.distanceMeters, 1e-6)
        assertTrue("drawn as a gap", RouteSpeedColoring.isGap(before.last(), after.first()))
        val displayed = RouteSmoother.smooth(result.points)
        assertEquals(before.size + after.size, displayed.points.size)
        assertTrue("the mark survives smoothing", displayed.points[before.size].startsStretch)
        // Smoothing pulls each stretch's end points in by a few meters.
        assertEquals(pathMeters(before) + pathMeters(after), displayed.distanceMeters, 20.0)
        // Moving: the 50 s before and the 290 s after, not the 20 minutes in between.
        assertEquals(340L, TripMotion.measure(displayed.points, TripType.WALK).movingSeconds)
    }

    @Test
    fun `the same with no reported speed at all`() {
        val before = walk(0.0, 1_000L, 6).map { it.copy(speedMetersPerSecond = null) }
        val after = walk(10_000.0, before.last().capturedAtEpochSecond + 15 * 60, 10).map { it.copy(speedMetersPerSecond = null) }

        assertEquals(before + after, unmarked(RouteSanitizer.sanitize(before + after).points))
    }

    @Test
    fun `a walking dropout still counts its straight line, and as moving`() {
        // Three minutes with no fix while walking on at 1.3 m/s: an ordinary gap, not a new stretch.
        val before = walk(0.0, 1_000L, 6)
        val after = walk(13.0 * 5 + 1.3 * 180, before.last().capturedAtEpochSecond + 180, 6)

        val displayed = RouteSmoother.smooth(RouteSanitizer.sanitize(before + after).points)

        assertTrue(displayed.points.none { it.startsStretch })
        assertEquals(pathMeters(before + after), displayed.distanceMeters, 5.0)
        assertEquals(50L + 180 + 50, TripMotion.measure(displayed.points, TripType.WALK).movingSeconds)
    }

    @Test
    fun `a driving gap still counts its straight line`() {
        // 20 m/s, a fix every 10 s, then two minutes in a tunnel: the driving rule accepts the far side.
        fun drive(fromMeters: Double, startT: Long, count: Int) = (0 until count).map { point(fromMeters + 200.0 * it, startT + 10L * it, speed = 20f) }
        val before = drive(0.0, 1_000L, 6)
        val after = drive(200.0 * 5 + 2_400.0, before.last().capturedAtEpochSecond + 120, 6)

        val sanitized = RouteSanitizer.sanitize(before + after)
        val displayed = RouteSmoother.smooth(sanitized.points)

        assertEquals("all kept, none a new stretch", before + after, sanitized.points)
        assertEquals(pathMeters(before + after), displayed.distanceMeters, 5.0)
    }

    /**
     * The code re-review's probe: 15 m/s, slowing to a stop at a red light, a 1.5 km tunnel with no
     * fix (100 s), crawling at 1 m/s in a queue after it, then 15 m/s again. Both ends of the gap
     * look like walking, but the minute before it was a drive.
     */
    private fun tunnelDrive(): List<TripPoint> = buildList {
        var at = 0.0
        var t = 1_000L
        repeat(30) { add(point(at, t, accuracy = 6f, speed = 15f)); at += 30.0; t += 2 }
        for (v in listOf(10f, 6f, 3f, 1f)) { add(point(at, t, accuracy = 6f, speed = v)); at += v * 2; t += 2 }
        repeat(10) { add(point(at + (it % 2), t, accuracy = 6f, speed = 0.1f)); t += 2 }
        at += 1_500.0
        t += 100
        repeat(15) { add(point(at, t, speed = 1f)); at += 2.0; t += 2 }
        repeat(30) { add(point(at, t, accuracy = 6f, speed = 15f)); at += 30.0; t += 2 }
    }

    @Test
    fun `a tunnel after a red light, slow at both ends, counts as the drive it was`() {
        val stored = tunnelDrive()

        val sanitized = RouteSanitizer.sanitize(stored)
        val displayed = RouteSmoother.smooth(sanitized.points)

        assertTrue(sanitized.points.none { it.startsStretch })
        // About 900 m, 32 m of slowing, the 1.5 km tunnel, 30 m of queue, then 900 m: about 3.36 km.
        assertEquals(3_362.0, displayed.distanceMeters, 30.0)
    }

    @Test
    fun `a drive between two hikes, walking to and from the car, is still a new stretch with no distance`() {
        // The minute before the gap is walking: nothing says the phone was in a vehicle.
        val before = walk(0.0, 1_000L, 8)
        val after = walk(20_000.0, before.last().capturedAtEpochSecond + 20 * 60, 10)

        val displayed = RouteSmoother.smooth(RouteSanitizer.sanitize(before + after).points)

        assertTrue(displayed.points[before.size].startsStretch)
        assertEquals(pathMeters(before) + pathMeters(after), displayed.distanceMeters, 20.0)
    }

    @Test
    fun `driving speeds more than a minute before the gap don't count`() {
        // Parked, then walked for two minutes before the gap: the drive is out of the minute.
        val drive = (0 until 10).map { point(30.0 * it, 1_000L + 2L * it, speed = 15f) }
        val walking = walk(300.0, drive.last().capturedAtEpochSecond + 10, 13)
        val after = walk(20_000.0, walking.last().capturedAtEpochSecond + 20 * 60, 10)

        assertTrue(RouteSanitizer.sanitize(drive + walking + after).points[drive.size + walking.size].startsStretch)
    }

    @Test
    fun `the driving speed needs two vehicle readings in the minute, and is the slower of the two fastest`() {
        assertEquals(10f, RoutePointAcceptancePolicy.drivingSpeed(listOf(1f, null, 15f, 10f))!!, 0f)
        assertEquals(15f, RoutePointAcceptancePolicy.drivingSpeed(listOf(15f, 1f, 15f, 40f))!!, 0f)
        assertEquals("one glitch is no drive", null, RoutePointAcceptancePolicy.drivingSpeed(listOf(1.3f, 7f, 1.3f)))
        assertEquals(null, RoutePointAcceptancePolicy.drivingSpeed(listOf(1.3f, 6f, null, Float.NaN)))
        assertEquals(null, RoutePointAcceptancePolicy.drivingSpeed(emptyList()))
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

        assertEquals("the walk on starts at the first well-placed point", before + after, unmarked(result.points))
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
