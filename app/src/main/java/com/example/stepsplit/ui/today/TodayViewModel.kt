package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.motion.MotionDiagnosticsStore
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.model.deriveStepCollectionHealth
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
    private val stepSourceHealthStore: StepSourceHealthStore,
    private val motionDiagnosticsStore: MotionDiagnosticsStore,
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

    private val baseUiState: Flow<TodayUiState> = combine(
        breakdownsForToday,
        settingsRepository.settings,
        availability,
        stepSourceHealthStore.snapshot,
    ) { (today, breakdowns), settings, currentAvailability, health ->
        val weekDates = weekDatesToDate(today)
        TodayUiState(
            isLoading = false,
            today = breakdowns[today],
            dailyGoal = settings.goals.dailyGoalSteps,
            weeklyTotalSteps = weekDates.sumOf { breakdowns[it]?.totalSteps ?: 0L },
            weeklyGoal = settings.goals.weeklyGoalSteps,
            // Sourced from the same settings flow already collected above, not a second
            // collection of the same underlying DataStore (see StepRepository.recordSuccessfulSync).
            lastSuccessfulSync = settings.lastSuccessfulSync,
            availability = currentAvailability,
            // Backed by SettingsRepository (a Room/DataStore-persisted value observed through the
            // same settings flow as everything else here), not a transient ViewModel field - a
            // failure recorded by a background WorkManager sync is visible the moment this screen
            // is next observed, not only right after a refresh() triggered from here.
            syncFailure = settings.lastSyncFailure,
            collectionHealth = deriveStepCollectionHealth(currentAvailability, settings.lastSyncFailure, health.everObservedSample),
        )
    }

    val uiState: StateFlow<TodayUiState> = combine(
        baseUiState,
        repository.observePendingCount(),
        motionDiagnosticsStore.snapshot,
    ) { state, pendingCount, motionDiagnostics ->
        state.copy(pendingValidationCount = pendingCount, motionDiagnostics = motionDiagnostics)
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
