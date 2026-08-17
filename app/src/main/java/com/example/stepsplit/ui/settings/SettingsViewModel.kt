package com.example.stepsplit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.repository.SyncResult
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceHealthSnapshot
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.debug.DeviceDiagnosticsSnapshot
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.domain.model.StepGoals
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.deriveStepCollectionHealth
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoading: Boolean = true,
    val goals: StepGoals = StepGoals(StepGoals.DEFAULT_DAILY_GOAL),
    val thresholds: ClassificationThresholds = ClassificationThresholds.DEFAULT,
    val availability: StepSourceAvailability? = null,
    /** The most recent sync failure, if any hasn't since been cleared by a successful sync - a separate concern from [availability]. */
    val syncFailure: SyncFailure? = null,
    val goalInputError: Boolean = false,
    /** See [StepCollectionHealth] - distinguishes a source that has never observed a step from one that has (historical evidence only - never a claim that collection is working *right now*, see that enum's own doc comment). */
    val collectionHealth: StepCollectionHealth? = null,
    /** Raw production acquisition diagnostics for the debug-only panel - see [StepSourceHealthStore]. Never shown outside debug builds. */
    val healthSnapshot: StepSourceHealthSnapshot = StepSourceHealthSnapshot(),
    /** Result text of the last debug "Run step source check" action, if any - see [SettingsViewModel.runStepSourceCheck]. Debug-only. */
    val debugStepSourceCheckResult: String? = null,
    /** Raw bucket row count for the real production step source only - see [StepRepository.debugStoredBucketCount]. Debug-only. */
    val storedBucketCount: Int? = null,
    /** Device/Health-Connect environment snapshot - see [DeviceDiagnosticsSnapshot]. Debug-only. */
    val deviceDiagnostics: DeviceDiagnosticsSnapshot? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val stepRepository: StepRepository,
    private val stepSourceHealthStore: StepSourceHealthStore,
    private val deviceDiagnostics: DeviceDiagnosticsSnapshot?,
    private val clock: Clock,
) : ViewModel() {

    private val availability = MutableStateFlow<StepSourceAvailability?>(null)
    private val goalInputError = MutableStateFlow(false)
    private val debugStepSourceCheckResult = MutableStateFlow<String?>(null)
    private val storedBucketCount = MutableStateFlow<Int?>(null)

    private val baseUiState: Flow<SettingsUiState> = combine(
        settingsRepository.settings,
        availability,
        goalInputError,
        stepSourceHealthStore.snapshot,
        debugStepSourceCheckResult,
    ) { settings, currentAvailability, hasGoalError, health, debugResult ->
        SettingsUiState(
            isLoading = false,
            goals = settings.goals,
            thresholds = settings.thresholds,
            availability = currentAvailability,
            syncFailure = settings.lastSyncFailure,
            goalInputError = hasGoalError,
            collectionHealth = deriveStepCollectionHealth(
                currentAvailability,
                settings.lastSyncFailure,
                health.everObservedSample,
                latestSampleEpochSecond = health.latestSampleEpochSecond,
                nowEpochSecond = clock.instant().epochSecond,
            ),
            healthSnapshot = health,
            debugStepSourceCheckResult = debugResult,
            deviceDiagnostics = deviceDiagnostics,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseUiState,
        storedBucketCount,
    ) { state, count ->
        state.copy(storedBucketCount = count)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshAvailability()
        refreshStoredBucketCount()
    }

    fun refreshAvailability() {
        viewModelScope.launch { availability.value = stepRepository.checkAvailability() }
    }

    /** Debug-only diagnostic - see [SettingsUiState.storedBucketCount]. */
    private fun refreshStoredBucketCount() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch { storedBucketCount.value = stepRepository.debugStoredBucketCount() }
    }

    fun setDailyGoal(steps: Long) {
        viewModelScope.launch {
            val accepted = settingsRepository.setDailyGoal(steps)
            goalInputError.value = !accepted
        }
    }

    fun setThresholds(thresholds: ClassificationThresholds) {
        // Routed through stepRepository (not settingsRepository directly) so existing sessions
        // are reclassified immediately under the new thresholds, not just on the next sync.
        viewModelScope.launch { stepRepository.applyThresholds(thresholds) }
    }

    fun resetThresholds() {
        viewModelScope.launch { stepRepository.resetThresholds() }
    }

    /**
     * Debug-only "Run step source check" action (see the Settings debug section) - deliberately
     * reuses [StepRepository.syncNow] rather than talking to the step source directly, so this
     * exercises the exact same subscribe-then-read production code path a real sync does (and the
     * exact same [com.example.stepsplit.data.stepsource.StepSourceHealthStore] recording), never a
     * second, parallel acquisition path that could mask a real problem in the first one.
     */
    fun runStepSourceCheck() {
        viewModelScope.launch {
            val result = stepRepository.syncNow()
            availability.value = stepRepository.checkAvailability()
            storedBucketCount.value = stepRepository.debugStoredBucketCount()
            debugStepSourceCheckResult.value = when (result) {
                is SyncResult.Success -> "Success: ${result.bucketsWritten} bucket(s) written"
                is SyncResult.Unavailable -> "Unavailable: ${result.availability}"
                is SyncResult.Failed -> "Failed: ${result.category} - ${result.message}"
            }
        }
    }
}
