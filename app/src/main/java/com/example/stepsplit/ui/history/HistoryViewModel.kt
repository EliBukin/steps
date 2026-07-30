package com.example.stepsplit.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.time.currentDateFlow
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: StepRepository,
    settingsRepository: SettingsRepository,
    clock: Clock,
) : ViewModel() {

    private fun last7Days(today: LocalDate): List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()) }

    /**
     * Re-derived from [currentDateFlow] rather than computed once at construction time, so the
     * rolling seven-day window advances if the screen stays open (or comes back to the
     * foreground) across a midnight boundary.
     */
    private val breakdownsForToday: Flow<Pair<LocalDate, Map<LocalDate, DateStepBreakdown>>> =
        currentDateFlow(clock).flatMapLatest { today ->
            repository.observeDailyBreakdowns(last7Days(today)).map { today to it }
        }

    val uiState: StateFlow<HistoryUiState> = combine(
        breakdownsForToday,
        settingsRepository.settings,
    ) { (today, breakdowns), settings ->
        val days = last7Days(today)
        HistoryUiState(
            isLoading = false,
            days = days.map { date -> breakdowns.getValue(date) },
            dailyGoal = settings.goals.dailyGoalSteps,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun refresh() {
        viewModelScope.launch { repository.syncNow() }
    }
}
