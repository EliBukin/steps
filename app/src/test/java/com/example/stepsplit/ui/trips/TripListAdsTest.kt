package com.example.stepsplit.ui.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class TripListAdsTest {

    /** Trip index -> slot number, for a list of [tripCount] trips. */
    private fun slots(tripCount: Int): Map<Int, Int> =
        (0 until tripCount).mapNotNull { index -> adSlotAfterTrip(index, tripCount)?.let { index to it } }.toMap()

    @Test
    fun `no ad slot with fewer than three trips`() {
        assertEquals(emptyMap<Int, Int>(), slots(0))
        assertEquals(emptyMap<Int, Int>(), slots(1))
        assertEquals(emptyMap<Int, Int>(), slots(2))
    }

    @Test
    fun `the first slot follows the third trip, even when it is the last one`() {
        assertEquals(mapOf(2 to 0), slots(3))
        assertEquals(mapOf(2 to 0), slots(10))
    }

    @Test
    fun `then one after every eight more trips, numbered in order`() {
        assertEquals(mapOf(2 to 0, 10 to 1), slots(11))
        assertEquals(mapOf(2 to 0, 10 to 1), slots(18))
        assertEquals(mapOf(2 to 0, 10 to 1, 18 to 2), slots(19))
        assertEquals(mapOf(2 to 0, 10 to 1, 18 to 2, 26 to 3, 34 to 4), slots(40))
    }

    @Test
    fun `an index outside the list has no slot`() {
        assertEquals(null, adSlotAfterTrip(-1, 12))
        assertEquals(null, adSlotAfterTrip(18, 12))
    }
}
