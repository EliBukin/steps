package com.example.stepsplit.ui.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedClockFormattingTest {

    @Test
    fun `the recording clock shows hours, minutes and seconds`() {
        assertEquals("0:00:00", formatElapsedClock(0))
        assertEquals("0:00:07", formatElapsedClock(7))
        assertEquals("0:42:17", formatElapsedClock(42 * 60 + 17))
        assertEquals("2:05:00", formatElapsedClock(2 * 3600 + 5 * 60))
        assertEquals("27:00:01", formatElapsedClock(27 * 3600 + 1))
    }

    @Test
    fun `a negative elapsed time shows as zero`() {
        assertEquals("0:00:00", formatElapsedClock(-5))
    }
}
