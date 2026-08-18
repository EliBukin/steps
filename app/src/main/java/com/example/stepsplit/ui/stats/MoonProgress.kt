package com.example.stepsplit.ui.stats

/**
 * "Progress to the Moon" is purely a display-derived UI computation from
 * [com.example.stepsplit.domain.stats.LifetimeWalkingStats.estimatedKilometers] - it has no
 * database field, repository query, or domain-layer persistence of its own, unlike
 * [com.example.stepsplit.domain.stats.LifetimeStatsCalculator.EARTH_CIRCUMFERENCE_KM]'s progress.
 * Like [com.example.stepsplit.domain.model.GoalProgress.percent] and `earthProgressPercent`, the
 * result is deliberately never clamped to 100 - only a visual progress bar in the UI clamps its own
 * fraction.
 */
internal object MoonProgress {
    /** Mean Earth-to-Moon (center-to-center) distance. */
    const val EARTH_MOON_DISTANCE_KM: Double = 384_400.0

    fun percent(estimatedKilometers: Double): Double = estimatedKilometers / EARTH_MOON_DISTANCE_KM * 100.0
}
