package com.example.stepsplit.domain.stats

import java.time.LocalDate
import kotlin.math.floor

/**
 * Fully derived, display-ready lifetime walking statistics - every field here is a pure function
 * of [LifetimeStepTotals], computed by [LifetimeStatsCalculator]. [estimatedKilometers] is a rough
 * estimate from step count alone (see [LifetimeStatsCalculator.METERS_PER_STEP]) - it is never a
 * GPS measurement, and must never be combined with GPS trip distance
 * ([com.example.stepsplit.data.trip.TripRepository] is a separate dataset), or the same walk
 * could be double-counted.
 */
data class LifetimeWalkingStats(
    val lifetimeSteps: Long,
    val estimatedKilometers: Double,
    val earthProgressPercent: Double,
    val marathonEquivalents: Double,
    val activeDays: Int,
    val averageStepsPerActiveDay: Long,
    val bestDayDate: LocalDate?,
    val bestDaySteps: Long,
    val kilometersToNextMarathon: Double,
) {
    val hasData: Boolean get() = lifetimeSteps > 0
}

/**
 * Pure arithmetic turning raw [LifetimeStepTotals] into encouraging, display-ready
 * [LifetimeWalkingStats] - no Android/Room dependency, so this is trivially unit-testable in
 * isolation from the database.
 *
 * [LifetimeWalkingStats.earthProgressPercent] is deliberately never clamped to 100 - a lifetime's
 * worth of walking can exceed one full trip around the Earth, and the number must keep climbing
 * past that point (the same convention as
 * [com.example.stepsplit.domain.model.GoalProgress.percent]). Only a visual progress bar in the UI
 * layer clamps its own fraction to 100%.
 */
object LifetimeStatsCalculator {
    /** Rough estimate, not a measured value - see [LifetimeWalkingStats]'s own doc comment. */
    const val METERS_PER_STEP: Double = 0.75
    const val EARTH_CIRCUMFERENCE_KM: Double = 40_075.0
    const val MARATHON_KM: Double = 42.195

    fun calculate(totals: LifetimeStepTotals): LifetimeWalkingStats {
        val estimatedKilometers = totals.lifetimeSteps * METERS_PER_STEP / 1000.0
        val earthProgressPercent = estimatedKilometers / EARTH_CIRCUMFERENCE_KM * 100.0
        val marathonEquivalents = estimatedKilometers / MARATHON_KM
        val averageStepsPerActiveDay = if (totals.activeDays > 0) totals.lifetimeSteps / totals.activeDays else 0L

        // The next whole marathon still ahead - e.g. 1.2 completed -> the 2nd marathon is next.
        val nextMarathonCount = floor(marathonEquivalents).toLong() + 1
        val kilometersToNextMarathon = (nextMarathonCount * MARATHON_KM - estimatedKilometers).coerceAtLeast(0.0)

        return LifetimeWalkingStats(
            lifetimeSteps = totals.lifetimeSteps,
            estimatedKilometers = estimatedKilometers,
            earthProgressPercent = earthProgressPercent,
            marathonEquivalents = marathonEquivalents,
            activeDays = totals.activeDays,
            averageStepsPerActiveDay = averageStepsPerActiveDay,
            bestDayDate = totals.bestDayDate,
            bestDaySteps = totals.bestDaySteps,
            kilometersToNextMarathon = kilometersToNextMarathon,
        )
    }
}
