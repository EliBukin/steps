package com.example.stepsplit.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedDistanceFormattingTest {

    @Test
    fun `zero meters displays as meters, not kilometers`() {
        val display = estimatedDistanceDisplay(0.0)
        assertTrue(display is EstimatedDistanceDisplay.Meters)
        assertEquals(0, (display as EstimatedDistanceDisplay.Meters).value)
    }

    @Test
    fun `a small distance below 1 km displays in meters, rounded`() {
        val display = estimatedDistanceDisplay(742.6)
        assertTrue(display is EstimatedDistanceDisplay.Meters)
        assertEquals(743, (display as EstimatedDistanceDisplay.Meters).value)
    }

    @Test
    fun `exactly 1000 meters switches to kilometers`() {
        val display = estimatedDistanceDisplay(1000.0)
        assertTrue(display is EstimatedDistanceDisplay.Kilometers)
        assertEquals(1.0, (display as EstimatedDistanceDisplay.Kilometers).value, 0.0001)
    }

    @Test
    fun `a large distance displays in kilometers`() {
        val display = estimatedDistanceDisplay(42_195.0)
        assertTrue(display is EstimatedDistanceDisplay.Kilometers)
        assertEquals(42.195, (display as EstimatedDistanceDisplay.Kilometers).value, 0.0001)
    }
}
