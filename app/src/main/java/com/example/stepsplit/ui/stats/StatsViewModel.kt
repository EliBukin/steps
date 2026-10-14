package com.example.stepsplit.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.domain.trip.TripStatsCalculator
import com.example.stepsplit.ui.trips.FinishedTripHistory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lifetime step stats and finished-trip totals, shown as two separate sections. The two features
 * stay separate underneath (see the README's "The two features are separate"): this screen only
 * reads each one's own repository and never mixes their numbers.
 */
class StatsViewModel(
    private val stepRepository: StepRepository,
    tripRepository: TripRepository,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        stepRepository.observeLifetimeStats(),
        FinishedTripHistory(tripRepository).trips,
    ) { lifetime, finishedTrips ->
        StatsUiState(
            isLoading = false,
            stats = LifetimeStatsCalculator.calculate(lifetime),
            trips = TripStatsCalculator.calculate(finishedTrips),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun refresh() {
        viewModelScope.launch { stepRepository.syncNow() }
    }
}
