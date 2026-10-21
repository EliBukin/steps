package com.example.stepsplit.ui.map

import com.example.stepsplit.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePartsTest {

    @Test
    fun `a route with no gap is one stretch`() {
        val route = walk(startSecond = 0L, startLatitude = 31.0, count = 5)

        val parts = splitAtGaps(route)

        assertEquals(listOf(route), parts.stretches)
        assertTrue(parts.gaps.isEmpty())
    }

    @Test
    fun `a resumed recording splits at the gap, which joins the last point before it to the first after it`() {
        val before = walk(startSecond = 0L, startLatitude = 31.0, count = 4)
        // Five minutes later and about 1 km further on: nothing shows how the phone got there.
        val after = walk(startSecond = 330L, startLatitude = 31.01, count = 3)

        val parts = splitAtGaps(before + after)

        assertEquals(listOf(before, after), parts.stretches)
        assertEquals(listOf(before.last() to after.first()), parts.gaps)
    }

    @Test
    fun `a long stop in one place is no gap, and neither is a quick jump`() {
        val walk = walk(startSecond = 0L, startLatitude = 31.0, count = 3)
        // Ten minutes later, 20 m on: a stop, not a gap.
        val afterStop = walk.last().copy(capturedAtEpochSecond = walk.last().capturedAtEpochSecond + 600, latitude = walk.last().latitude + 0.00018)
        // 30 seconds later, 300 m on: fast, but the points are there.
        val afterJump = afterStop.copy(capturedAtEpochSecond = afterStop.capturedAtEpochSecond + 30, latitude = afterStop.latitude + 0.0027)

        val parts = splitAtGaps(walk + afterStop + afterJump)

        assertEquals(1, parts.stretches.size)
        assertTrue(parts.gaps.isEmpty())
    }

    @Test
    fun `an empty route has no parts`() {
        val parts = splitAtGaps(emptyList())

        assertTrue(parts.stretches.isEmpty())
        assertTrue(parts.gaps.isEmpty())
    }

    /** About 14 m north every 10 seconds. */
    private fun walk(startSecond: Long, startLatitude: Double, count: Int) = (0 until count).map { i ->
        TripPoint(
            capturedAtEpochSecond = startSecond + i * 10L,
            latitude = startLatitude + i * 0.000126,
            longitude = 35.0,
            accuracyMeters = 5f,
            altitudeMeters = null,
            speedMetersPerSecond = 1.4f,
        )
    }
}
