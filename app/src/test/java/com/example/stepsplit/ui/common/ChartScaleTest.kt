package com.example.stepsplit.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {

    @Test
    fun `an all-zero week with no goal still produces a positive, non-crashing axis`() {
        assertEquals(1L, ChartScale.computeMax(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), goalSteps = 0L))
    }

    @Test
    fun `a positive goal with all-zero data sets the axis to cover the goal`() {
        val max = ChartScale.computeMax(listOf(0L, 0L, 0L), goalSteps = 8_000L)
        assertTrue("axis max $max must be at least the goal", max >= 8_000L)
    }

    @Test
    fun `a large single-day outlier rounds up to a nice ceiling above it`() {
        val max = ChartScale.computeMax(listOf(500L, 300L, 87_300L, 900L), goalSteps = 0L)
        assertEquals(100_000L, max)
        assertTrue(max >= 87_300L)
    }

    @Test
    fun `an exact power-of-ten value maps to itself`() {
        assertEquals(10_000L, ChartScale.computeMax(listOf(10_000L), goalSteps = 0L))
    }

    @Test
    fun `the goal never gets clipped off the axis even when every day is below it`() {
        val max = ChartScale.computeMax(listOf(1_000L, 2_000L), goalSteps = 15_000L)
        assertTrue(max >= 15_000L)
    }

    @Test
    fun `segment fractions for a mixed day sum to at most 1 and match the expected proportions`() {
        val fractions = ChartScale.computeSegmentFractions(workoutSteps = 3_000L, incidentalSteps = 2_000L, scaleMax = 10_000L)
        assertEquals(0.3f, fractions.workout, 0.001f)
        assertEquals(0.2f, fractions.incidental, 0.001f)
        assertEquals(0.5f, fractions.remainder, 0.001f)
    }

    @Test
    fun `segment fractions for an all-zero day leave the full bar as empty remainder`() {
        val fractions = ChartScale.computeSegmentFractions(workoutSteps = 0L, incidentalSteps = 0L, scaleMax = 5_000L)
        assertEquals(0f, fractions.workout, 0.0001f)
        assertEquals(0f, fractions.incidental, 0.0001f)
        assertEquals(1f, fractions.remainder, 0.0001f)
    }

    @Test
    fun `segment fractions never exceed 1 even if totals somehow exceed scaleMax`() {
        val fractions = ChartScale.computeSegmentFractions(workoutSteps = 9_000L, incidentalSteps = 9_000L, scaleMax = 10_000L)
        assertTrue(fractions.workout <= 1f)
        assertTrue(fractions.incidental <= 1f)
        assertTrue(fractions.remainder >= 0f)
    }

    @Test
    fun `a tiny nonzero workout segment is not coerced away to zero`() {
        val fractions = ChartScale.computeSegmentFractions(workoutSteps = 1L, incidentalSteps = 0L, scaleMax = 1_000_000L)
        assertTrue(fractions.workout > 0f)
    }

    @Test
    fun `the total bar height fraction matches the representative 9,162 on 20,000 case exactly`() {
        val fraction = ChartScale.computeTotalFraction(totalSteps = 9_162L, scaleMax = 20_000L)
        assertEquals(0.4581f, fraction, 0.0001f)
    }

    @Test
    fun `the total bar height fraction is zero for a zero-step day`() {
        assertEquals(0f, ChartScale.computeTotalFraction(totalSteps = 0L, scaleMax = 20_000L), 0.0001f)
    }

    @Test
    fun `the total bar height fraction is coerced to 1 when the total exceeds scaleMax`() {
        assertEquals(1f, ChartScale.computeTotalFraction(totalSteps = 25_000L, scaleMax = 20_000L), 0.0001f)
    }

    @Test
    fun `an extreme input terminates safely without overflowing into a negative ceiling`() {
        val max = ChartScale.computeMax(listOf(Long.MAX_VALUE), goalSteps = 0L)
        assertEquals(Long.MAX_VALUE, max)
        assertTrue("axis ceiling must never be negative", max >= 0L)
    }

    @Test
    fun `an extreme goal alone also terminates safely`() {
        val max = ChartScale.computeMax(listOf(100L, 200L), goalSteps = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, max)
    }
}
