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

    /**
     * A caller normally checks [checkAvailability] once, then calls this moments later - but
     * availability can be lost in that exact window (permission revoked, Play services stopped,
     * etc.) or mid-read. Implementations MUST throw [StepSourceUnavailableException] instead of
     * silently returning an empty list when they detect this - never let a "we can no longer trust
     * this window contains no steps" outcome look identical to "this window genuinely has no
     * steps", or a caller like [com.example.stepsplit.data.repository.StepRepository] could treat
     * a lost source as a successful empty read and delete previously stored data during its own
     * reconciliation.
     */
    suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval>
}

/**
 * Thrown by [StepSource.readSteps] when the source's availability is lost between being checked
 * and the read actually completing - see that function's own doc comment. [availability] is
 * whatever the source observed at the moment it detected the loss, so the caller can report the
 * same structured state ([StepSourceAvailability]) it would have reported had it been caught by an
 * upfront check instead.
 */
class StepSourceUnavailableException(
    val availability: StepSourceAvailability,
    message: String = "Step source became unavailable during read: $availability",
) : Exception(message)
