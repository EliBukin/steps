package com.example.stepsplit.ui.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class TripListAdsTest {

    @Test
    fun `no ad card with fewer than three trips`() {
        assertEquals(emptySet<Int>(), adSlotsAfterTrips(0))
        assertEquals(emptySet<Int>(), adSlotsAfterTrips(1))
        assertEquals(emptySet<Int>(), adSlotsAfterTrips(2))
    }

    @Test
    fun `the first card follows the third trip, even when it is the last one`() {
        assertEquals(setOf(2), adSlotsAfterTrips(3))
        assertEquals(setOf(2), adSlotsAfterTrips(10))
    }

    @Test
    fun `then one after every eight more trips`() {
        assertEquals(setOf(2, 10), adSlotsAfterTrips(11))
        assertEquals(setOf(2, 10), adSlotsAfterTrips(18))
        assertEquals(setOf(2, 10, 18), adSlotsAfterTrips(19))
        assertEquals(setOf(2, 10, 18, 26, 34), adSlotsAfterTrips(40))
    }
}
