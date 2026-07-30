package com.example.stepsplit.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.AutoCompletedWalk
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

    /** Set only right after a failed [finishManualWalk]; cleared by [consumeFinishWalkFailure]. */
    private val finishWalkFailed = MutableStateFlow(false)

    /**
     * The oldest not-yet-acknowledged auto-completion, if any. Driven by a Room query (not
     * transient state) so an auto-completion that happened during a background WorkManager sync
     * still reliably surfaces the "ended automatically" message the next time this screen is open.
     */
    private val unacknowledgedAutoCompletion: StateFlow<AutoCompletedWalk?> =
        repository.observeUnacknowledgedAutoCompletions()
            .map { it.firstOrNull() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        repository.observeOngoingManualWalk(),
        availability,
    ) { (today, breakdowns), settings, lastSync, ongoingWalk, currentAvailability ->
        val weekDates = weekDatesToDate(today)
        TodayUiState(
            isLoading = false,
            today = breakdowns[today],
            dailyGoal = settings.goals.dailyGoalSteps,
            weeklyTotalSteps = weekDates.sumOf { breakdowns[it]?.totalSteps ?: 0L },
            weeklyGoal = settings.goals.weeklyGoalSteps,
            lastSuccessfulSync = lastSync,
            availability = currentAvailability,
            hasOngoingManualWalk = ongoingWalk != null,
        )
    }
        .combine(finishWalkFailed) { state, failed -> state.copy(finishWalkFailed = failed) }
        .combine(unacknowledgedAutoCompletion) { state, autoCompleted ->
            state.copy(showAutoCompletedWalkMessage = autoCompleted != null)
        }
        .combine(repository.observeOngoingManualWalkStatus()) { state, status ->
            val staleStart = status?.startEpochSecond?.takeIf { start ->
                !status.hasRecordedSteps &&
                    clock.instant().epochSecond - start >= StepRepository.ZERO_STEP_STALE_THRESHOLD.seconds
            }
            state.copy(staleZeroStepWalkStartEpochSecond = staleStart)
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

    fun startManualWalk() {
        viewModelScope.launch { repository.startManualWalk() }
    }

    fun finishManualWalk() {
        viewModelScope.launch {
            val finished = repository.finishManualWalk()
            if (!finished) finishWalkFailed.value = true
        }
    }

    /** Called by the UI once the failure message has actually been shown, so it isn't re-shown on the next recomposition. */
    fun consumeFinishWalkFailure() {
        finishWalkFailed.value = false
    }

    /** Called by the UI once the "ended automatically" message has actually been shown. */
    fun consumeAutoCompletedWalkMessage() {
        viewModelScope.launch {
            unacknowledgedAutoCompletion.value?.let { repository.acknowledgeAutoCompletion(it.id) }
        }
    }

    // ---- Stale zero-step walk recovery ----

    fun cancelStaleWalk() {
        viewModelScope.launch { repository.cancelOngoingManualWalk() }
    }

    fun finishStaleWalkNow() {
        viewModelScope.launch { repository.finishOngoingManualWalkAt(clock.instant().epochSecond) }
    }
}
