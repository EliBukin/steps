package com.example.stepsplit.ui.today

import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.domain.model.SyncFailure
import java.time.Instant

data class TodayUiState(
    val isLoading: Boolean = true,
    val today: DateStepBreakdown? = null,
    val dailyGoal: Long = 0,
    val weeklyTotalSteps: Long = 0,
    val weeklyGoal: Long = 0,
    val lastSuccessfulSync: Instant? = null,
    val availability: StepSourceAvailability? = null,
    /** The most recent sync failure, if any hasn't since been cleared by a successful sync - a separate concern from [availability]. */
    val syncFailure: SyncFailure? = null,
    /** See [StepCollectionHealth] - distinguishes a source that has never observed a step from one that has (historical evidence only - never a claim that collection is working *right now*, see that enum's own doc comment). */
    val collectionHealth: StepCollectionHealth? = null,
) {
    val dailyProgress: GoalProgress
        get() = GoalProgress(today?.totalSteps ?: 0, dailyGoal)

    val weeklyProgress: GoalProgress
        get() = GoalProgress(weeklyTotalSteps, weeklyGoal)
}
