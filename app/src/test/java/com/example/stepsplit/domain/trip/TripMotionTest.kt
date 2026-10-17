package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMotionTest {

    /**
     * A recorded trip heading north from (31.0, 35.0), built the way the phone records one: a fix
     * every few seconds while moving, and none while standing still (location updates need 8 m of
     * movement), so a stop ends in one long stretch.
     */
    private class Track {
        private var time = 1_000_000L
        private var metersNorth = 0.0
        val points = mutableListOf<TripPoint>()

        /** Moves for [seconds] at [kmh], with a fix every [every] seconds. */
        fun move(seconds: Long, kmh: Double, every: Long = 10L): Track {
            val metersPerSecond = kmh / 3.6
            if (points.isEmpty()) points += point()
            var elapsed = 0L
            while (elapsed + every <= seconds) {
                elapsed += every
                time += every
                metersNorth += metersPerSecond * every
                points += point()
            }
            return this
        }

        /** Stands still for [seconds]: no fixes. */
        fun stop(seconds: Long): Track {
            if (points.isEmpty()) points += point()
            time += seconds
            return this
        }

        /** Stands still for [seconds] while the GPS drifts [meters] back and forth, a fix every [every] seconds. */
        fun drift(seconds: Long, every: Long, meters: Double): Track {
            if (points.isEmpty()) points += point()
            var elapsed = 0L
            var sign = 1
            while (elapsed + every <= seconds) {
                elapsed += every
                time += every
                metersNorth += sign * meters
                sign = -sign
                points += point()
            }
            return this
        }

        /** Nothing recorded for [seconds] while covering [meters]: a gap if both are big enough. */
        fun jump(seconds: Long, meters: Double): Track {
            if (points.isEmpty()) points += point()
            time += seconds
            metersNorth += meters
            points += point()
            return this
        }

        private fun point() = TripPoint(
            capturedAtEpochSecond = time,
            latitude = 31.0 + metersNorth / METERS_PER_DEGREE_LATITUDE,
            longitude = 35.0,
            accuracyMeters = 5f,
            altitudeMeters = null,
            speedMetersPerSecond = null,
        )
    }

    private fun measure(track: Track, type: TripType = TripType.WALK) = TripMotion.measure(track.points, type)

    private fun minutes(seconds: Long) = seconds / 60.0

    // ---- Moving time ----

    @Test
    fun `a 20-minute break on a hike is not moving time`() {
        val hike = Track().move(40 * 60, kmh = 4.0).stop(20 * 60).move(40 * 60, kmh = 4.0)
        val motion = measure(hike)
        assertEquals(80.0, minutes(motion.movingSeconds), 0.5)
    }

    @Test
    fun `GPS drift while standing still is not moving`() {
        // Ten minutes of fixes 10 m apart every two minutes: 0.3 km/h.
        val hike = Track().move(30 * 60, kmh = 4.0).drift(10 * 60, every = 120, meters = 10.0).move(30 * 60, kmh = 4.0)
        assertEquals(60.0, minutes(measure(hike).movingSeconds), 1.0)
    }

    @Test
    fun `a very slow scramble still counts as moving on a walk`() {
        val scramble = Track().move(30 * 60, kmh = 1.5, every = 20)
        assertEquals(30.0, minutes(measure(scramble).movingSeconds), 0.01)
    }

    @Test
    fun `a short pause is averaged with the walking around it and counts`() {
        val walk = Track().move(10 * 60, kmh = 4.0).stop(20).move(10 * 60, kmh = 4.0)
        assertEquals(20.0 + 20 / 60.0, minutes(measure(walk).movingSeconds), 0.01)
    }

    @Test
    fun `a drive's stop with its drive-off isn't moving, though the same stretch would be for a walk`() {
        // Two minutes stopped, then 55 m of drive-off before the next fix: 125 s for 55 m, 1.6 km/h.
        val drive = Track().move(10 * 60, kmh = 40.0, every = 5).stop(120).jump(5, 55.0).move(10 * 60, kmh = 40.0, every = 5)
        assertEquals(20.0, minutes(measure(drive, TripType.DRIVE).movingSeconds), 0.2)
        assertEquals(22.0, minutes(measure(drive, TripType.WALK).movingSeconds), 0.2)
    }

    @Test
    fun `a 4x4 crawling over rocks counts as moving`() {
        val crawl = Track().move(15 * 60, kmh = 4.0)
        assertEquals(15.0, minutes(measure(crawl, TripType.DRIVE).movingSeconds), 0.01)
    }

    @Test
    fun `a short GPS dropout while walking counts as moving`() {
        // Three minutes and 200 m with no fix: a gap, walked at 4 km/h in a straight line at least.
        val walk = Track().move(10 * 60, kmh = 4.0).jump(180, 200.0).move(10 * 60, kmh = 4.0)
        assertNull(RouteSpeedColoring.smoothedSpeeds(walk.points)[60])
        assertEquals(23.0, minutes(measure(walk).movingSeconds), 0.01)
    }

    @Test
    fun `a long interruption spent mostly standing isn't moving`() {
        // An hour with nothing recorded and only 500 m between its ends: 0.5 km/h at the least.
        val walk = Track().move(10 * 60, kmh = 4.0).jump(3600, 500.0).move(10 * 60, kmh = 4.0)
        assertEquals(20.0, minutes(measure(walk).movingSeconds), 0.01)
    }

    @Test
    fun `moving time never exceeds the time between the first and last point`() {
        val trips = listOf(
            Track().move(3600, kmh = 5.0),
            Track().move(600, kmh = 60.0, every = 5).jump(900, 15_000.0).move(600, kmh = 60.0, every = 5),
            Track().stop(300).move(600, kmh = 3.0).stop(600),
        )
        for (trip in trips) {
            val span = trip.points.last().capturedAtEpochSecond - trip.points.first().capturedAtEpochSecond
            assertTrue(measure(trip).movingSeconds <= span)
        }
    }

    @Test
    fun `fewer than two points have no moving time and no top speed`() {
        assertEquals(TripMotion.Motion(0L, null), TripMotion.measure(emptyList(), TripType.WALK))
        assertEquals(TripMotion.Motion(0L, null), TripMotion.measure(Track().stop(0).points, TripType.DRIVE))
    }

    // ---- Top speed ----

    @Test
    fun `top speed is the fastest smoothed stretch, not one fast fix`() {
        // One 10 s stretch that looks like 14.4 km/h in a 4 km/h walk.
        val walk = Track().move(5 * 60, kmh = 4.0).jump(10, 40.0).move(5 * 60, kmh = 4.0)
        val top = assertNotNullAndGet(measure(walk).topSpeedMetersPerSecond)
        assertEquals(RouteSpeedColoring.smoothedSpeeds(walk.points).filterNotNull().max(), top, 1e-9)
        assertTrue("averaged down from 14.4 km/h: ${top * 3.6}", top * 3.6 < 8.0)
    }

    @Test
    fun `the phone's own reported speed is never used`() {
        val walk = Track().move(10 * 60, kmh = 4.0)
        val spiked = walk.points.mapIndexed { i, point -> if (i == 30) point.copy(speedMetersPerSecond = 40f) else point }
        assertEquals(measure(walk), TripMotion.measure(spiked, TripType.WALK))
        assertEquals(4.0, measure(walk).topSpeedMetersPerSecond!! * 3.6, 0.01)
    }

    @Test
    fun `a gap's straight-line speed is never the top speed`() {
        // 15 km in 10 minutes with nothing recorded would be 90 km/h; the recorded driving is 60.
        val drive = Track().move(600, kmh = 60.0, every = 5).jump(600, 15_000.0).move(600, kmh = 60.0, every = 5)
        assertEquals(60.0, measure(drive, TripType.DRIVE).topSpeedMetersPerSecond!! * 3.6, 0.01)
    }

    @Test
    fun `a trip that never moved has no top speed`() {
        val standing = Track().drift(30 * 60, every = 120, meters = 10.0)
        val motion = measure(standing)
        assertEquals(0L, motion.movingSeconds)
        assertNull(motion.topSpeedMetersPerSecond)
    }

    @Test
    fun `top speed is never below the speed legend's fast end`() {
        // The slowest threshold is still at least the scale's 1 km/h minimum span, which the proof in
        // RouteSpeedColoring.scaleFor relies on.
        assertTrue(TripMotion.WALK_MOVING_SPEED_KMH / 3.6 >= RouteSpeedColoring.MIN_SCALE_SPAN_METERS_PER_SECOND - 1e-12)
        val trips = listOf(
            Track().move(3600, kmh = 4.0),
            Track().move(3600, kmh = 1.1),
            Track().move(1800, kmh = 3.5).stop(1200).move(1800, kmh = 5.0),
            Track().move(600, kmh = 4.2).move(600, kmh = 4.4).move(600, kmh = 4.3),
            Track().move(1200, kmh = 40.0, every = 5).stop(300).move(1200, kmh = 70.0, every = 5),
            Track().move(1200, kmh = 50.0, every = 5),
            Track().drift(1800, every = 120, meters = 10.0).move(300, kmh = 2.0),
        )
        for (trip in trips) {
            val speeds = RouteSpeedColoring.smoothedSpeeds(trip.points).filterNotNull()
            val scale = RouteSpeedColoring.scaleFor(speeds)!!
            for (type in TripType.entries) {
                val top = TripMotion.measure(trip.points, type).topSpeedMetersPerSecond ?: continue
                assertTrue("$type: top ${top * 3.6} km/h, legend fast end ${scale.fastMetersPerSecond * 3.6} km/h", top >= scale.fastMetersPerSecond - 1e-9)
            }
        }
    }

    private fun assertNotNullAndGet(value: Double?): Double {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
    }
}
