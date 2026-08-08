package com.example.stepsplit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.data.local.motion.MotionEvidenceEntity
import com.example.stepsplit.data.motion.MotionDiagnosticsSnapshot
import com.example.stepsplit.data.motion.MotionDiagnosticsStore
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
import com.example.stepsplit.domain.validation.StrictStepValidationPolicy
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
    /** Device/Play-services environment snapshot - see [DeviceDiagnosticsSnapshot]. Debug-only. */
    val deviceDiagnostics: DeviceDiagnosticsSnapshot? = null,
    /** Strict vehicle-aware validation acquisition health - transition/sampling registration status+last error, last successful validation time. See [MotionDiagnosticsSnapshot]. Debug-only. */
    val motionDiagnostics: MotionDiagnosticsSnapshot = MotionDiagnosticsSnapshot(),
    /** Per-[com.example.stepsplit.domain.validation.ValidationState] row counts for the real production source. Debug-only. */
    val validationStateCounts: Map<String, Int> = emptyMap(),
    val currentValidationPolicyVersion: Int = StrictStepValidationPolicy.POLICY_VERSION,
    /** The most recently received raw motion-evidence rows, newest first - see [StepRepository.debugRecentMotionEvidence]. Debug-only. */
    val recentMotionEvidence: List<MotionEvidenceEntity> = emptyList(),
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val stepRepository: StepRepository,
    private val stepSourceHealthStore: StepSourceHealthStore,
    private val deviceDiagnostics: DeviceDiagnosticsSnapshot?,
    private val motionDiagnosticsStore: MotionDiagnosticsStore,
) : ViewModel() {

    private val availability = MutableStateFlow<StepSourceAvailability?>(null)
    private val goalInputError = MutableStateFlow(false)
    private val debugStepSourceCheckResult = MutableStateFlow<String?>(null)
    private val storedBucketCount = MutableStateFlow<Int?>(null)
    private val recentMotionEvidence = MutableStateFlow<List<MotionEvidenceEntity>>(emptyList())

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
            collectionHealth = deriveStepCollectionHealth(currentAvailability, settings.lastSyncFailure, health.everObservedSample),
            healthSnapshot = health,
            debugStepSourceCheckResult = debugResult,
            deviceDiagnostics = deviceDiagnostics,
        )
    }

    private val motionState: Flow<Pair<MotionDiagnosticsSnapshot, Map<String, Int>>> = combine(
        motionDiagnosticsStore.snapshot,
        stepRepository.observeValidationStateCounts(),
    ) { snapshot, counts -> snapshot to counts.associate { it.validationState to it.count } }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseUiState,
        storedBucketCount,
        motionState,
        recentMotionEvidence,
    ) { state, count, (motionSnapshot, validationCounts), recentEvidence ->
        state.copy(
            storedBucketCount = count,
            motionDiagnostics = motionSnapshot,
            validationStateCounts = validationCounts,
            recentMotionEvidence = recentEvidence,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshAvailability()
        refreshStoredBucketCount()
        refreshRecentMotionEvidence()
    }

    fun refreshAvailability() {
        viewModelScope.launch { availability.value = stepRepository.checkAvailability() }
    }

    /** Debug-only diagnostic - see [SettingsUiState.storedBucketCount]. */
    private fun refreshStoredBucketCount() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch { storedBucketCount.value = stepRepository.debugStoredBucketCount() }
    }

    /** Debug-only diagnostic - see [SettingsUiState.recentMotionEvidence]. */
    private fun refreshRecentMotionEvidence() {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch { recentMotionEvidence.value = stepRepository.debugRecentMotionEvidence() }
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
     * second, parallel acquisition path that could mask a real problem in the first one. The
     * result is a plain, non-localized diagnostic string (never shown outside debug builds), not a
     * candidate for [com.example.stepsplit.domain.model.SyncFailureCategory]'s user-facing text.
     */
    fun runStepSourceCheck() {
        viewModelScope.launch {
            val result = stepRepository.syncNow()
            availability.value = stepRepository.checkAvailability()
            storedBucketCount.value = stepRepository.debugStoredBucketCount()
            recentMotionEvidence.value = stepRepository.debugRecentMotionEvidence()
            debugStepSourceCheckResult.value = when (result) {
                is SyncResult.Success -> "Success: ${result.bucketsWritten} bucket(s) written"
                is SyncResult.Unavailable -> "Unavailable: ${result.availability}"
                is SyncResult.Failed -> "Failed: ${result.category} - ${result.message}"
            }
        }
    }
}
