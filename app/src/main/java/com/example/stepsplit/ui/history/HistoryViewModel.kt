package com.example.stepsplit.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: StepRepository,
    settingsRepository: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private val today = LocalDate.now(clock)
    private val last7Days = (6 downTo 0).map { today.minusDays(it.toLong()) }

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.observeDailyBreakdowns(last7Days),
        settingsRepository.settings,
    ) { breakdowns, settings ->
        HistoryUiState(
            isLoading = false,
            days = last7Days.map { date -> breakdowns[date]!! },
            dailyGoal = settings.goals.dailyGoalSteps,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun refresh() {
        viewModelScope.launch { repository.syncNow() }
    }
}
