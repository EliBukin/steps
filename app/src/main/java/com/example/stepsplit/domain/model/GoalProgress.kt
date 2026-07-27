package com.example.stepsplit.domain.model

/**
 * Progress toward a step goal. `percent` is intentionally never capped - 18,000 of a 15,000 goal
 * reports 120.0, not 100.0. Only the visual indicator (a Compose progress bar) clamps to 100%;
 * the numeric label above it must keep showing the true value.
 */
data class GoalProgress(
    val achievedSteps: Long,
    val goalSteps: Long,
) {
    /** Uncapped percentage, e.g. 120.0 for 18,000/15,000. Zero when the goal is invalid (<= 0). */
    val percent: Double
        get() = if (goalSteps > 0) achievedSteps.toDouble() / goalSteps.toDouble() * 100.0 else 0.0

    /** Clamped 0..1 fraction, safe to feed directly into a Compose progress indicator. */
    val clampedFraction: Float
        get() = (percent / 100.0).toFloat().coerceIn(0f, 1f)

    val isGoalValid: Boolean
        get() = goalSteps > 0
}

/**
 * User-configurable goals. The weekly goal is always derived from the daily goal (dailyGoal * 7)
 * so the two never drift apart.
 */
data class StepGoals(val dailyGoalSteps: Long) {
    val weeklyGoalSteps: Long get() = dailyGoalSteps * 7

    companion object {
        const val DEFAULT_DAILY_GOAL = 15_000L
        const val MIN_DAILY_GOAL = 1L
        const val MAX_DAILY_GOAL = 1_000_000L

        fun isValidDailyGoal(steps: Long): Boolean = steps in MIN_DAILY_GOAL..MAX_DAILY_GOAL
    }
}
