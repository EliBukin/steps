package com.example.stepsplit.domain.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifetimeStatsCalculatorTest {

    @Test
    fun `zero lifetime steps produce a valid, non-crashing zero state`() {
        val stats = LifetimeStatsCalculator.calculate(LifetimeStepTotals.EMPTY)

        assertEquals(0L, stats.lifetimeSteps)
        assertEquals(0.0, stats.estimatedKilometers, 0.0)
        assertEquals(0.0, stats.earthProgressPercent, 0.0)
        assertEquals(0.0, stats.marathonEquivalents, 0.0)
        assertEquals(0, stats.activeDays)
        assertEquals(0L, stats.averageStepsPerActiveDay)
        assertFalse(stats.hasData)
    }

    @Test
    fun `distance is estimated using 0,75 meters per step`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 10_000, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        assertEquals(7.5, stats.estimatedKilometers, 1e-9)
    }

    @Test
    fun `earth progress percent is distance over 40075km times 100`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 10_000, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        val expectedPercent = 7.5 / 40_075.0 * 100.0
        assertEquals(expectedPercent, stats.earthProgressPercent, 1e-9)
    }

    @Test
    fun `earth progress percent is never clamped to 100, even past one full circumference`() {
        // 40,075 km / 0.75 m per step ~= 53,433,333 steps for one full circumference - doubled here
        // to land comfortably past it.
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 53_433_334L * 2, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        assertTrue("earth progress must be allowed to exceed 100%, not clamp", stats.earthProgressPercent > 100.0)
    }

    @Test
    fun `a tiny nonzero step count still produces a strictly positive earth percentage`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 10, activeDays = 1, bestDayDate = null, bestDaySteps = 10),
        )

        assertTrue("a nonzero step count must never round down to an indistinguishable-from-zero percent internally", stats.earthProgressPercent > 0.0)
    }

    @Test
    fun `marathon equivalents is distance over 42195km`() {
        // 42,195 m / 0.75 m per step = exactly 56,260 steps for one marathon.
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 56_260, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        assertEquals(1.0, stats.marathonEquivalents, 1e-6)
    }

    @Test
    fun `average steps per active day divides lifetime steps by active days`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 30_000, activeDays = 3, bestDayDate = null, bestDaySteps = 0),
        )

        assertEquals(10_000L, stats.averageStepsPerActiveDay)
    }

    @Test
    fun `average steps per active day is zero, not a division error, when there are no active days`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 0, activeDays = 0, bestDayDate = null, bestDaySteps = 0),
        )

        assertEquals(0L, stats.averageStepsPerActiveDay)
    }

    @Test
    fun `best day date and steps are carried through unchanged`() {
        val date = LocalDate.of(2026, 1, 15)
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 5_000, activeDays = 2, bestDayDate = date, bestDaySteps = 4_000),
        )

        assertEquals(date, stats.bestDayDate)
        assertEquals(4_000L, stats.bestDaySteps)
    }

    @Test
    fun `kilometers to next marathon is always positive and shrinks as distance grows`() {
        val early = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 1_000, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )
        val later = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 50_000, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        assertTrue(early.kilometersToNextMarathon > 0.0)
        assertTrue(later.kilometersToNextMarathon > 0.0)
        assertTrue(later.kilometersToNextMarathon < early.kilometersToNextMarathon)
    }

    @Test
    fun `kilometers to next marathon resets to a full marathon just after crossing a whole-marathon boundary`() {
        // Exactly one completed marathon (56,260 steps) - the next target must be the 2nd marathon,
        // i.e. a full 42.195km still remaining, not (incorrectly) 0.
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = 56_260, activeDays = 1, bestDayDate = null, bestDaySteps = 0),
        )

        assertEquals(42.195, stats.kilometersToNextMarathon, 1e-6)
    }

    @Test
    fun `a very large lifetime step count produces finite, non-negative derived values`() {
        val stats = LifetimeStatsCalculator.calculate(
            LifetimeStepTotals(lifetimeSteps = Long.MAX_VALUE, activeDays = 1, bestDayDate = null, bestDaySteps = Long.MAX_VALUE),
        )

        assertTrue(stats.estimatedKilometers.isFinite() && stats.estimatedKilometers > 0.0)
        assertTrue(stats.earthProgressPercent.isFinite() && stats.earthProgressPercent > 0.0)
        assertTrue(stats.marathonEquivalents.isFinite() && stats.marathonEquivalents > 0.0)
        assertTrue(stats.kilometersToNextMarathon.isFinite() && stats.kilometersToNextMarathon >= 0.0)
    }
}
