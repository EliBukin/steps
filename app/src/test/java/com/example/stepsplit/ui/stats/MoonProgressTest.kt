package com.example.stepsplit.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonProgressTest {

    @Test
    fun `zero distance is exactly zero percent`() {
        assertEquals(0.0, MoonProgress.percent(0.0), 0.0)
    }

    @Test
    fun `a tiny nonzero distance is a tiny nonzero percentage, not zero`() {
        val percent = MoonProgress.percent(1.0)
        assertTrue("expected a positive percentage, got $percent", percent > 0.0)
        assertTrue("expected a very small percentage, got $percent", percent < 0.001)
    }

    @Test
    fun `walking the full mean Earth-Moon distance is exactly 100 percent`() {
        assertEquals(100.0, MoonProgress.percent(MoonProgress.EARTH_MOON_DISTANCE_KM), 0.0001)
    }

    @Test
    fun `progress past the Moon is not clamped to 100`() {
        val percent = MoonProgress.percent(MoonProgress.EARTH_MOON_DISTANCE_KM * 2)
        assertEquals(200.0, percent, 0.0001)
    }

    @Test
    fun `a realistic lifetime distance produces a small double-digit-free percentage`() {
        // ~1000 km lifetime is a plausible, still-early distance - well under a quarter of the way.
        val percent = MoonProgress.percent(1_000.0)
        assertTrue(percent > 0.0)
        assertTrue(percent < 1.0)
    }
}
