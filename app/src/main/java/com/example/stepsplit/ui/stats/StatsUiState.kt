package com.example.stepsplit.ui.stats

import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.domain.stats.LifetimeStepTotals
import com.example.stepsplit.domain.stats.LifetimeWalkingStats
import com.example.stepsplit.domain.trip.TripTotalsByType

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: LifetimeWalkingStats = LifetimeStatsCalculator.calculate(LifetimeStepTotals.EMPTY),
    /** Walks and drives totalled separately - see [com.example.stepsplit.domain.trip.TripStatsCalculator.calculateByType]. */
    val trips: TripTotalsByType = TripTotalsByType.EMPTY,
)
