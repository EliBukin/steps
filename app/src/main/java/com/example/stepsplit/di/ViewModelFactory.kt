package com.example.stepsplit.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.stepsplit.ui.history.HistoryViewModel
import com.example.stepsplit.ui.sessions.SessionsViewModel
import com.example.stepsplit.ui.settings.SettingsViewModel
import com.example.stepsplit.ui.today.TodayViewModel

/** Hand-written [ViewModelProvider.Factory] wiring each screen's ViewModel to [AppContainer]. */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        TodayViewModel::class.java -> TodayViewModel(
            container.stepRepository,
            container.settingsRepository,
            container.clock,
        )

        HistoryViewModel::class.java -> HistoryViewModel(
            container.stepRepository,
            container.settingsRepository,
            container.clock,
        )

        SessionsViewModel::class.java -> SessionsViewModel(container.stepRepository)

        SettingsViewModel::class.java -> SettingsViewModel(
            container.settingsRepository,
            container.stepRepository,
        )

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    } as T
}
