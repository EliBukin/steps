package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.time.WeekWindow
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodayViewModel(
    private val repository: StepRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val availability = MutableStateFlow<StepSourceAvailability?>(null)

    private val today = LocalDate.now(clock)
    private val weekDatesToDate = generateSequence(WeekWindow.startOfWeek(today)) { it.plusDays(1) }
        .takeWhile { !it.isAfter(today) }
        .toList()

    val uiState: StateFlow<TodayUiState> = combine(
        repository.observeDailyBreakdowns(weekDatesToDate),
        settingsRepository.settings,
        repository.observeLastSuccessfulSync(),
        repository.observeOngoingManualWalk(),
        availability,
    ) { breakdowns, settings, lastSync, ongoingWalk, currentAvailability ->
        TodayUiState(
            isLoading = false,
            today = breakdowns[today],
            dailyGoal = settings.goals.dailyGoalSteps,
            weeklyTotalSteps = weekDatesToDate.sumOf { breakdowns[it]?.totalSteps ?: 0L },
            weeklyGoal = settings.goals.weeklyGoalSteps,
            lastSuccessfulSync = lastSync,
            availability = currentAvailability,
            hasOngoingManualWalk = ongoingWalk != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            availability.value = repository.checkAvailability()
            repository.syncNow()
            availability.value = repository.checkAvailability()
        }
    }

    fun startManualWalk() {
        viewModelScope.launch { repository.startManualWalk() }
    }

    fun finishManualWalk() {
        viewModelScope.launch { repository.finishManualWalk() }
    }
}
