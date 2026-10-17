package com.example.stepsplit.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.domain.trip.TripStatsCalculator
import com.example.stepsplit.ui.trips.FinishedTripHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Lifetime step stats and finished-trip totals, shown as two separate sections. The two features
 * stay separate underneath (see the README's "The two features are separate"): this screen only
 * reads each one's own repository and never mixes their numbers.
 *
 * Trip totals are split into walks and drives. Each finished trip's type comes from
 * [FinishedTripHistory], which works it out off the main thread from the same smoothed route it
 * already reads for the displayed distance (or takes the owner's choice); each kept deleted trip
 * carries the type it had when it was deleted.
 *
 * Trip totals include the trips deleted while [keepDeletedTripsInStats] was on, but only while it
 * is still on: turning it off hides them, and turning it back on shows them again.
 */
class StatsViewModel(
    private val stepRepository: StepRepository,
    tripRepository: TripRepository,
    keepDeletedTripsInStats: Flow<Boolean>,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = combine(
        stepRepository.observeLifetimeStats(),
        FinishedTripHistory(tripRepository).finishedTrips,
        tripRepository.observeDeletedTripTotals(),
        keepDeletedTripsInStats,
    ) { lifetime, finishedTrips, deletedTrips, keepDeleted ->
        val typeById = finishedTrips.associate { it.summary.id to it.type }
        StatsUiState(
            isLoading = false,
            stats = LifetimeStatsCalculator.calculate(lifetime),
            trips = TripStatsCalculator.calculateByType(
                finishedTrips = finishedTrips.map { it.summary },
                typeOf = { typeById.getValue(it.id) },
                deletedTrips = if (keepDeleted) deletedTrips else emptyList(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun refresh() {
        viewModelScope.launch { stepRepository.syncNow() }
    }
}
