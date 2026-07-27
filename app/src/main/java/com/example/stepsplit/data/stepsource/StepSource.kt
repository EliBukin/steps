package com.example.stepsplit.data.stepsource

import java.time.Instant

/** One raw, source-reported step interval before it is normalized into one-minute buckets. */
data class RawStepInterval(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
)

/**
 * Explicit, user-facing collection states. The app must never invent steps or silently report
 * zero as though collection were working - every non-[Available] state is surfaced in the UI.
 */
sealed interface StepSourceAvailability {
    data object Available : StepSourceAvailability
    data object PermissionNotGranted : StepSourceAvailability
    data object PlayServicesUpdateRequired : StepSourceAvailability
    data object ApiUnavailable : StepSourceAvailability
    data class Error(val message: String) : StepSourceAvailability
}

/**
 * A pluggable source of step data. The MVP ships exactly one production implementation
 * ([LocalRecordingStepSource], backed by the accountless Recording API's local-only client) plus
 * [FakeStepSource] for tests and debug builds - but repository code depends only on this
 * interface, so a future Health Connect or on-device sensor provider can be added later without
 * touching the classification, aggregation, or UI layers.
 */
interface StepSource {
    val id: String

    suspend fun checkAvailability(): StepSourceAvailability

    /** Idempotent: safe to call on every app start. Must not be paired with routine unsubscribe. */
    suspend fun ensureSubscribed(): Boolean

    suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval>
}
