package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
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
            // failure recorded by any foreground sync (this screen's own periodic refresh, a
            // permission-grant refresh, or a prior ViewModel instance's) is visible the moment
            // this screen is next observed, not only right after a refresh() triggered from here.
            syncFailure = settings.lastSyncFailure,
            collectionHealth = deriveStepCollectionHealth(
                currentAvailability,
                settings.lastSyncFailure,
                health.everObservedSample,
                latestSampleEpochSecond = health.latestSampleEpochSecond,
                nowEpochSecond = clock.instant().epochSecond,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    /** The currently in-flight [refresh] coroutine, if any - see that function's own doc comment. */
    private var refreshJob: Job? = null

    /**
     * Performs a foreground Health Connect import - called whenever the app resumes (including
     * the very first resume right after this screen is first composed) and periodically (roughly
     * once a minute) while this screen is visibly active (see
     * [com.example.stepsplit.ui.today.TodayScreen]'s own resume effect), plus once more
     * immediately after permission is granted (see [com.example.stepsplit.ui.StepSplitApp]'s
     * `onGrantPermission` wiring). [TodayScreen]'s resume effect is this app's one deliberate
     * owner of the *initial* refresh - this ViewModel does not also trigger one from `init`, which
     * would otherwise race it as a redundant, near-simultaneous second import on every cold start.
     *
     * Granting permission itself pauses and resumes this Activity around the system consent
     * dialog, so the resume effect's own call and the explicit post-grant call above can *also*
     * land within moments of each other. Rather than trying to prove that can never happen, this
     * coalesces: if a refresh is already running, a new call is a no-op instead of starting a
     * second overlapping Health Connect read - the in-flight one already reflects whatever
     * permission/availability state was current when it started reading. [StepRepository.syncNow]
     * is separately mutex-protected against overlap too (see its own doc comment), so this is a
     * belt-and-suspenders guard against redundant reads, not a correctness requirement.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            availability.value = repository.checkAvailability()
            repository.syncNow()
            availability.value = repository.checkAvailability()
        }
    }
}
