package com.example.stepsplit.ui.today

import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.model.GoalProgress
import java.time.Instant

data class TodayUiState(
    val isLoading: Boolean = true,
    val today: DateStepBreakdown? = null,
    val dailyGoal: Long = 0,
    val weeklyTotalSteps: Long = 0,
    val weeklyGoal: Long = 0,
    val lastSuccessfulSync: Instant? = null,
    val availability: StepSourceAvailability? = null,
) {
    val dailyProgress: GoalProgress
        get() = GoalProgress(today?.totalSteps ?: 0, dailyGoal)

    val weeklyProgress: GoalProgress
        get() = GoalProgress(weeklyTotalSteps, weeklyGoal)
}
