package com.example.stepsplit.ui.today

import com.example.stepsplit.data.motion.MotionDiagnosticsSnapshot
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
    /** Raw step observations imported but not yet validated one way or the other - see [com.example.stepsplit.data.repository.StepRepository.observePendingCount]. Never part of [today]'s own totals. */
    val pendingValidationCount: Int = 0,
    val motionDiagnostics: MotionDiagnosticsSnapshot = MotionDiagnosticsSnapshot(),
) {
    /**
     * True only once BOTH the Transition and Sampling API registrations have been actively
     * attempted and have both failed - never true merely because neither has registered yet
     * (e.g. cold start, before [com.example.stepsplit.data.motion.MotionEvidenceRegistrar] has
     * run once), which would otherwise falsely alarm on every app launch. With no trustworthy
     * evidence source at all, newly-imported steps can never be verified - shown honestly rather
     * than silently falling back to unverified counting, per the product requirement.
     */
    val validationAccuracyUnavailable: Boolean
        get() = motionDiagnostics.latestTransitionRegistrationSucceeded == false &&
            motionDiagnostics.latestSamplingRegistrationSucceeded == false

    val dailyProgress: GoalProgress
        get() = GoalProgress(today?.totalSteps ?: 0, dailyGoal)

    val weeklyProgress: GoalProgress
        get() = GoalProgress(weeklyTotalSteps, weeklyGoal)
}
