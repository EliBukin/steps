package com.example.stepsplit.ui.stats

import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.domain.stats.LifetimeStepTotals
import com.example.stepsplit.domain.stats.LifetimeWalkingStats
import com.example.stepsplit.domain.trip.TripTotals

data class StatsUiState(
    val isLoading: Boolean = true,
    val stats: LifetimeWalkingStats = LifetimeStatsCalculator.calculate(LifetimeStepTotals.EMPTY),
    val trips: TripTotals = TripTotals.EMPTY,
)
