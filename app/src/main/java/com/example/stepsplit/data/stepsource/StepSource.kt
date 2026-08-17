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
 * A pluggable source of step data. Production ships exactly one implementation
 * ([HealthConnectStepSource], backed by Health Connect's on-device step counting) plus
 * [FakeStepSource] for tests and debug builds - repository code depends only on this interface,
 * and production must never combine or merge several simultaneously active sources (see
 * [HealthConnectStepSource]'s own doc comment).
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
     * steps", or a caller like [com.example.stepsplit.data.repository.StepRepository] could
     * wrongly record a lost source as a successful, genuinely-empty sync - masking the real
     * unavailability behind what looks like ordinary "nothing happened" collection health.
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

/**
 * Thrown by [StepSource.readSteps] when the underlying source reports a genuine read failure
 * (not an availability problem - see [StepSourceUnavailableException] for that). Reported by
 * [com.example.stepsplit.data.repository.StepRepository] as [com.example.stepsplit.data.repository.SyncResult.Failed]
 * without touching previously stored data. [apiFailure] is the sanitized category behind the
 * failure, when known - never exposed as raw text in release UI; [cause] is kept for debug-only
 * logging, never surfaced to the user.
 */
class StepSourceReadException(
    message: String,
    val apiFailure: ApiFailure? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
