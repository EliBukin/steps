package com.example.stepsplit.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.stepsplit.ui.history.HistoryViewModel
import com.example.stepsplit.ui.map.OpenMapViewModel
import com.example.stepsplit.ui.settings.SettingsViewModel
import com.example.stepsplit.ui.stats.StatsViewModel
import com.example.stepsplit.ui.today.TodayViewModel
import com.example.stepsplit.ui.trips.TripDetailViewModel
import com.example.stepsplit.ui.trips.TripsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Hand-written [ViewModelProvider.Factory] wiring each screen's ViewModel to [AppContainer]. */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        TodayViewModel::class.java -> TodayViewModel(
            container.stepRepository,
            container.settingsRepository,
            container.stepSourceHealthStore,
            container.clock,
        )

        HistoryViewModel::class.java -> HistoryViewModel(
            container.stepRepository,
            container.settingsRepository,
            container.clock,
        )

        StatsViewModel::class.java -> StatsViewModel(container.stepRepository, container.tripRepository, keepDeletedTripsInStats())

        SettingsViewModel::class.java -> SettingsViewModel(
            container.settingsRepository,
            container.stepRepository,
            container.stepSourceHealthStore,
            container.deviceDiagnostics,
            container.clock,
        )

        TripsViewModel::class.java -> TripsViewModel(container.tripRepository, container.clock, container.tripSpotMarker::markSpot)

        OpenMapViewModel::class.java -> OpenMapViewModel(container.tripRepository, container.mapLocationClient)

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    } as T

    /** [TripDetailViewModel] takes a navigation-argument [tripId] the shared dispatch table above has no way to supply - a small per-call factory instead, constructed at the composable's own call site. */
    fun forTrip(tripId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TripDetailViewModel::class.java) { "Unknown ViewModel class: ${modelClass.name}" }
            return TripDetailViewModel(container.tripRepository, tripId, keepDeletedTripsInStats(), container.seaLevelHeights) as T
        }
    }

    private fun keepDeletedTripsInStats(): Flow<Boolean> =
        container.settingsRepository.settings.map { it.keepDeletedTripsInStats }.distinctUntilChanged()
}
