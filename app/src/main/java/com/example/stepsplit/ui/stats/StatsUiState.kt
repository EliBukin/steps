package com.example.stepsplit.ui.stats

import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.domain.stats.LifetimeStepTotals
import com.example.stepsplit.domain.stats.LifetimeWalkingStats

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: LifetimeWalkingStats = LifetimeStatsCalculator.calculate(LifetimeStepTotals.EMPTY),
    /**
     * Pre-existing history still carrying `LEGACY_UNVERIFIED` (imported before strict vehicle
     * validation existed - see [com.example.stepsplit.data.repository.StepRepository.observeLegacyStats]).
     * Shown as its own compact card, never merged into [stats] - legacy steps never count toward
     * the verified lifetime total, distance, globe progress, or achievements.
     */
    val legacyStats: LifetimeStepTotals = LifetimeStepTotals.EMPTY,
) {
    val legacyEstimatedKilometers: Double
        get() = legacyStats.lifetimeSteps * LifetimeStatsCalculator.METERS_PER_STEP / 1000.0
}
