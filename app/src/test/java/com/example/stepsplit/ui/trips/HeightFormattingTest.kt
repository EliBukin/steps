package com.example.stepsplit.ui.trips

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HeightFormattingTest {

    @Test
    fun `heights are whole meters with thousands separators`() {
        assertEquals("721", formatHeightNumber(720.6, Locale.US))
        assertEquals("1,027", formatHeightNumber(1_027.4, Locale.US))
        assertEquals("1,027", formatHeightNumber(1_027.4, Locale.forLanguageTag("he")))
    }

    @Test
    fun `below sea level gets a minus that stays in front in right-to-left text`() {
        assertEquals("‎−427", formatHeightNumber(-427.2, Locale.US))
        assertEquals("‎−1,410", formatHeightNumber(-1_410.0, Locale.forLanguageTag("he")))
    }

    @Test
    fun `less than half a meter below sea level is zero, without a minus`() {
        assertEquals("0", formatHeightNumber(-0.4, Locale.US))
        assertEquals("0", formatHeightNumber(0.4, Locale.US))
    }

    @Test
    fun `top speed and the legend's ends share one format, so the top speed never reads lower`() {
        // The same rounding for both: a top speed at or above the fast end never displays below it.
        for (fast in listOf(4.94, 9.94, 9.95, 37.4, 68.6)) {
            for (top in listOf(fast, fast + 0.01, fast + 0.6)) {
                val shownTop = formatLegendSpeed(top, Locale.US).toDouble()
                val shownFast = formatLegendSpeed(fast, Locale.US).toDouble()
                assertEquals("top $top vs fast $fast", true, shownTop >= shownFast)
            }
        }
    }
}
