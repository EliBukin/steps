package com.example.stepsplit.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepGoals
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
    val goalInputError: Boolean = false,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val stepRepository: StepRepository,
) : ViewModel() {

    private val availability = MutableStateFlow<StepSourceAvailability?>(null)
    private val goalInputError = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        availability,
        goalInputError,
    ) { settings, currentAvailability, hasGoalError ->
        SettingsUiState(
            isLoading = false,
            goals = settings.goals,
            thresholds = settings.thresholds,
            availability = currentAvailability,
            goalInputError = hasGoalError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshAvailability()
    }

    fun refreshAvailability() {
        viewModelScope.launch { availability.value = stepRepository.checkAvailability() }
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
}
