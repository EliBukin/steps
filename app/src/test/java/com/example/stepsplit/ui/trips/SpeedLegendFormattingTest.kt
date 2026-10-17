package com.example.stepsplit.ui.trips

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedLegendFormattingTest {

    @Test
    fun `walking speeds keep one decimal`() {
        assertEquals("0.0", formatLegendSpeed(0.0, Locale.US))
        assertEquals("2.4", formatLegendSpeed(2.43, Locale.US))
        assertEquals("9.9", formatLegendSpeed(9.94, Locale.US))
    }

    @Test
    fun `driving speeds are whole numbers`() {
        assertEquals("10", formatLegendSpeed(9.96, Locale.US))
        assertEquals("37", formatLegendSpeed(36.6, Locale.US))
        assertEquals("64", formatLegendSpeed(64.2, Locale.forLanguageTag("he")))
    }
}
