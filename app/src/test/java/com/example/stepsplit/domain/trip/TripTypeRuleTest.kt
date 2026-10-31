package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType
import org.junit.Assert.assertEquals
import org.junit.Test

class TripTypeRuleTest {

    /**
     * A recorded trip heading north from (31.0, 35.0), built the way the phone records one: a fix
     * every few seconds while moving, carrying the speed the phone reports, and no fix at all while
     * standing still (location updates need 8 m of movement).
     */
    private class Track {
        private var time = 1_000_000L
        private var metersNorth = 0.0
        val points = mutableListOf<TripPoint>()

        /** Moves for [seconds] at [kmh], with a fix every [every] seconds. */
        fun move(seconds: Long, kmh: Double, every: Long = 10L): Track {
            val metersPerSecond = kmh / 3.6
            if (points.isEmpty()) points += point(metersPerSecond)
            var elapsed = 0L
            while (elapsed + every <= seconds) {
                elapsed += every
                time += every
                metersNorth += metersPerSecond * every
                points += point(metersPerSecond)
            }
            return this
        }

        /** Stands still for [seconds]: no fixes, so the next one after it ends one long, slow stretch. */
        fun stop(seconds: Long): Track {
            time += seconds
            return this
        }

        private fun point(metersPerSecond: Double) = TripPoint(
            capturedAtEpochSecond = time,
            latitude = 31.0 + metersNorth / METERS_PER_DEGREE_LATITUDE,
            longitude = 35.0,
            accuracyMeters = 5f,
            altitudeMeters = null,
            speedMetersPerSecond = metersPerSecond.toFloat(),
        )
    }

    /** The rule as the app runs it: on the same cleaned-up and smoothed route the map draws. */
    private fun suggest(track: Track): TripType = TripTypeRule.suggest(RouteSmoother.smooth(RouteSanitizer.sanitize(track.points).points).points)

    private fun steady(stretches: Int, kmh: Double, every: Long = 10L) = Track().move(stretches * every, kmh, every)

    // ---- Walks ----

    @Test
    fun `a slow hike with breaks and a steep climb is a walk`() {
        val hike = Track()
            .move(40 * 60, kmh = 3.5)
            .stop(10 * 60)
            .move(30 * 60, kmh = 1.8, every = 15) // steep and rocky
            .stop(20 * 60) // lunch
            .move(15 * 60, kmh = 5.5) // easy downhill
            .move(45 * 60, kmh = 3.0)
        assertEquals(TripType.WALK, suggest(hike))
    }

    @Test
    fun `a brisk walk and a jog are walks`() {
        assertEquals(TripType.WALK, suggest(Track().move(40 * 60, kmh = 6.5).move(20 * 60, kmh = 11.0, every = 5)))
        assertEquals(TripType.WALK, suggest(steady(stretches = 300, kmh = 12.0, every = 5)))
    }

    @Test
    fun `a hike where recording ran on for a few minutes in the car is still a walk`() {
        val hike = Track().move(3 * 3600, kmh = 4.0).stop(5 * 60).move(5 * 60, kmh = 40.0, every = 5)
        assertEquals(TripType.WALK, suggest(hike))
    }

    // ---- Drives ----

    @Test
    fun `a jeep drive with stops and a slow rocky stretch is a drive`() {
        val drive = Track()
            .move(20 * 60, kmh = 25.0, every = 5)
            .stop(10 * 60) // viewpoint
            .move(15 * 60, kmh = 40.0, every = 5)
            .stop(15 * 60)
            .move(10 * 60, kmh = 8.0) // crawling over rocks
            .move(10 * 60, kmh = 30.0, every = 5)
            .stop(30 * 60) // standing at the destination before finishing
        assertEquals(TripType.DRIVE, suggest(drive))
    }

    @Test
    fun `a jeep trip with a long walk at a viewpoint is still a drive`() {
        val trip = Track().move(30 * 60, kmh = 30.0, every = 5).move(45 * 60, kmh = 4.0).move(30 * 60, kmh = 30.0, every = 5)
        assertEquals(TripType.DRIVE, suggest(trip))
    }

    // ---- The threshold ----

    @Test
    fun `the line is 15 kmh`() {
        assertEquals(TripType.WALK, suggest(steady(stretches = 60, kmh = 14.0, every = 5)))
        assertEquals(TripType.DRIVE, suggest(steady(stretches = 60, kmh = 16.0, every = 5)))
    }

    @Test
    fun `fast stretches under a tenth of the trip don't make it a drive, more than a tenth do`() {
        // Directly on a route, no cleanup, to pin the percentile itself.
        fun route(slow: Int, fast: Int) = Track().move(slow * 10L, kmh = 4.0).move(fast * 10L, kmh = 30.0).points
        assertEquals(TripType.WALK, TripTypeRule.suggest(route(slow = 95, fast = 5)))
        assertEquals(TripType.DRIVE, TripTypeRule.suggest(route(slow = 80, fast = 20)))
    }

    @Test
    fun `a 4x4 crawl that never reaches 15 kmh is suggested as a walk - the owner can change it`() {
        assertEquals(TripType.WALK, suggest(Track().move(60 * 60, kmh = 9.0)))
    }

    // ---- Too little to tell ----

    @Test
    fun `an empty or tiny trip is a walk, even a fast one`() {
        assertEquals(TripType.WALK, TripTypeRule.suggest(emptyList()))
        assertEquals(TripType.WALK, TripTypeRule.suggest(steady(stretches = 0, kmh = 50.0).points))
        assertEquals(TripType.WALK, TripTypeRule.suggest(steady(stretches = 1, kmh = 50.0).points))
        assertEquals(TripType.WALK, TripTypeRule.suggest(steady(stretches = TripTypeRule.MIN_STRETCHES - 1, kmh = 50.0, every = 5).points))
        assertEquals(TripType.DRIVE, TripTypeRule.suggest(steady(stretches = TripTypeRule.MIN_STRETCHES, kmh = 50.0, every = 5).points))
    }

    @Test
    fun `stretches across a gap don't count`() {
        // Three fast-looking stretches, each over a minute and over 100 m: gaps with no speed.
        val gappy = Track().move(5 * 120L, kmh = 30.0, every = 120)
        assertEquals(TripType.WALK, TripTypeRule.suggest(gappy.points))
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
    }
}
