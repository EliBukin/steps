package com.example.stepsplit.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.stepsplit.ui.history.HistoryViewModel
import com.example.stepsplit.ui.settings.SettingsViewModel
import com.example.stepsplit.ui.stats.StatsViewModel
import com.example.stepsplit.ui.today.TodayViewModel
import com.example.stepsplit.ui.trips.TripDetailViewModel
import com.example.stepsplit.ui.trips.TripsViewModel

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

        StatsViewModel::class.java -> StatsViewModel(container.stepRepository)

        SettingsViewModel::class.java -> SettingsViewModel(
            container.settingsRepository,
            container.stepRepository,
            container.stepSourceHealthStore,
            container.deviceDiagnostics,
            container.clock,
        )

        TripsViewModel::class.java -> TripsViewModel(container.tripRepository, container.clock)

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    } as T

    /** [TripDetailViewModel] takes a navigation-argument [tripId] the shared dispatch table above has no way to supply - a small per-call factory instead, constructed at the composable's own call site. */
    fun forTrip(tripId: Long): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TripDetailViewModel::class.java) { "Unknown ViewModel class: ${modelClass.name}" }
            return TripDetailViewModel(container.tripRepository, tripId) as T
        }
    }
}
