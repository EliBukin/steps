package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatsCalculatorTest {

    private fun finishedTrip(id: Long, durationSeconds: Long, distanceMeters: Double) = TripSummary(
        id = id,
        startEpochSecond = 1_000_000L,
        endEpochSecond = 1_000_000L + durationSeconds,
        startZoneId = "UTC",
        state = TripState.FINISHED,
        distanceMeters = distanceMeters,
        lastAcceptedPointEpochSecond = 1_000_000L + durationSeconds,
    )

    @Test
    fun `no trips gives an empty total with no averages`() {
        val totals = TripStatsCalculator.calculate(emptyList())

        assertEquals(TripTotals.EMPTY, totals)
        assertFalse(totals.hasTrips)
    }

    @Test
    fun `totals add up every trip's time and distance`() {
        val totals = TripStatsCalculator.calculate(
            listOf(finishedTrip(1, durationSeconds = 57 * 60, distanceMeters = 4_490.0), finishedTrip(2, 21 * 60, 2_630.0)),
        )

        assertTrue(totals.hasTrips)
        assertEquals(2, totals.tripCount)
        assertEquals(78 * 60L, totals.totalDurationSeconds)
        assertEquals(7_120.0, totals.totalDistanceMeters, 1e-9)
    }

    @Test
    fun `averages are overall - total distance over total time - not a mean of per-trip averages`() {
        // A fast short trip and a slow long one: 1 km in 6 min (10 km/h) and 3 km in 54 min (3.33 km/h).
        val totals = TripStatsCalculator.calculate(
            listOf(finishedTrip(1, durationSeconds = 6 * 60, distanceMeters = 1_000.0), finishedTrip(2, 54 * 60, 3_000.0)),
        )

        // 4 km in 1 h - not the 6.67 km/h a plain mean of the two trips' speeds would give.
        assertEquals(4.0, totals.averageSpeedKmh!!, 1e-9)
        assertEquals(15 * 60L, totals.averagePaceSecondsPerKm)
    }

    @Test
    fun `pace rounds to whole seconds per kilometer`() {
        // 7.12 km in 78 min -> 657.30... s/km, shown as 10:57.
        val totals = TripStatsCalculator.calculate(
            listOf(finishedTrip(1, durationSeconds = 78 * 60, distanceMeters = 7_120.0)),
        )

        assertEquals(657L, totals.averagePaceSecondsPerKm)
        assertEquals(5.477, totals.averageSpeedKmh!!, 1e-3)
    }

    @Test
    fun `trips with no distance or no time give no averages instead of dividing by zero`() {
        val noDistance = TripStatsCalculator.calculate(listOf(finishedTrip(1, durationSeconds = 600, distanceMeters = 0.0)))
        val noTime = TripStatsCalculator.calculate(listOf(finishedTrip(1, durationSeconds = 0, distanceMeters = 500.0)))

        assertNull(noDistance.averageSpeedKmh)
        assertNull(noDistance.averagePaceSecondsPerKm)
        assertNull(noTime.averageSpeedKmh)
        assertNull(noTime.averagePaceSecondsPerKm)
        assertEquals(1, noTime.tripCount)
    }

    @Test
    fun `a trip without an end time counts as zero duration, never negative`() {
        val totals = TripStatsCalculator.calculate(
            listOf(finishedTrip(1, durationSeconds = 600, distanceMeters = 800.0).copy(endEpochSecond = null)),
        )

        assertEquals(0L, totals.totalDurationSeconds)
    }
}
