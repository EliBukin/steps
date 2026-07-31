package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.time.WeekWindow
import com.example.stepsplit.domain.time.currentDateFlow
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val repository: StepRepository,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val availability = MutableStateFlow<StepSourceAvailability?>(null)

    private fun weekDatesToDate(today: LocalDate): List<LocalDate> =
        generateSequence(WeekWindow.startOfWeek(today)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .toList()

    /**
     * Re-derived from [currentDateFlow] rather than computed once at construction time, so the
     * app never keeps showing yesterday's day/week window if it stays open (or comes back to the
     * foreground) across a midnight boundary.
     */
    private val breakdownsForToday: Flow<Pair<LocalDate, Map<LocalDate, DateStepBreakdown>>> =
        currentDateFlow(clock).flatMapLatest { today ->
            repository.observeDailyBreakdowns(weekDatesToDate(today)).map { today to it }
        }

    val uiState: StateFlow<TodayUiState> = combine(
        breakdownsForToday,
        settingsRepository.settings,
        repository.observeLastSuccessfulSync(),
        availability,
    ) { (today, breakdowns), settings, lastSync, currentAvailability ->
        val weekDates = weekDatesToDate(today)
        TodayUiState(
            isLoading = false,
            today = breakdowns[today],
            dailyGoal = settings.goals.dailyGoalSteps,
            weeklyTotalSteps = weekDates.sumOf { breakdowns[it]?.totalSteps ?: 0L },
            weeklyGoal = settings.goals.weeklyGoalSteps,
            lastSuccessfulSync = lastSync,
            availability = currentAvailability,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

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
}
