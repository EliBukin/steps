package com.example.stepsplit.domain.validation

/**
 * Mirrors Google's [com.google.android.gms.location.DetectedActivity] constants one-to-one, kept
 * as our own enum so every pure type in this package (and every test) stays independent of the
 * GMS API surface - only the acquisition layer (`data/motion`) ever touches the real SDK types.
 */
enum class MotionActivityType { IN_VEHICLE, ON_BICYCLE, ON_FOOT, WALKING, RUNNING, STILL, TILTING, UNKNOWN }

/**
 * Where a raw [com.example.stepsplit.data.local.bucket.StepBucketEntity] row currently stands in
 * the validation pipeline - see [StrictStepValidationPolicy]'s own doc comment for the full state
 * machine (which states are terminal, which are revisited, and why).
 */
enum class ValidationState { PENDING, ACCEPTED_WALKING, ACCEPTED_RUNNING, REJECTED_VEHICLE, REJECTED_BICYCLE, REJECTED_UNVERIFIED, LEGACY_UNVERIFIED }

/** Diagnostic detail behind a `REJECTED_*` (or previously-`REVOKED_*`) [ValidationState]. */
enum class RejectionReason {
    VEHICLE_VETO,
    BICYCLE_VETO,
    NO_POSITIVE_EVIDENCE,
    PARTIAL_COVERAGE,
    AWAITING_EVIDENCE_TIMEOUT,
    REVOKED_BY_DELAYED_VEHICLE_EVIDENCE,
    REVOKED_BY_DELAYED_BICYCLE_EVIDENCE,
    /** An already-`ACCEPTED_*` bucket lost its positive-coverage justification to later evidence (a delayed EXIT/interrupt/discontinuity) - distinct from [AWAITING_EVIDENCE_TIMEOUT], which is a bucket that never found evidence in time. */
    REVOKED_NO_POSITIVE_COVERAGE,
}

/** How a piece of raw evidence was produced - mirrors the Activity Recognition Transition API vs. Sampling API distinction. */
enum class MotionEvidenceKind { TRANSITION_ENTER, TRANSITION_EXIT, SAMPLED }

/**
 * One piece of sampled evidence, already converted to boot-independent wall-clock time (see
 * `data/motion/MotionEvidenceConverter`) - fed to [StrictStepValidationPolicy.evaluate] for its own
 * "≥2 consecutive samples" positive-evidence rule and its sampled vehicle/bicycle point-veto rule.
 * Never used to open/close a permanent [com.example.stepsplit.data.local.motion.ActivityIntervalEntity] -
 * see [IntervalReconstructor]'s own doc comment for why sampled evidence and Transition-API evidence
 * are deliberately different types, not interchangeable.
 */
data class MotionEvidenceEvent(
    val kind: MotionEvidenceKind,
    val activityType: MotionActivityType,
    val confidence: Int?,
    val wallClockEpochMilli: Long,
    val temporalContinuityEpoch: Long,
    val batchId: String?,
)

/** One raw, source-independent step observation awaiting a validation decision. */
data class RawObservation(val startEpochSecond: Long, val endEpochSecondExclusive: Long, val rawSteps: Long)

/**
 * A materialized (open or closed) [com.example.stepsplit.data.local.motion.ActivityIntervalEntity],
 * as read by the repository's unbounded overlap query and handed to
 * [StrictStepValidationPolicy.evaluate] - see that table's own doc comment for why this query is
 * never time-bounded.
 */
data class MaterializedInterval(
    val activityType: MotionActivityType,
    val startWallClockEpochMilli: Long,
    /** Null = still open - extends toward "now", subject to the epoch check in [StrictStepValidationPolicy]. */
    val endWallClockEpochMilli: Long?,
    val temporalContinuityEpoch: Long,
)

data class ValidationDecision(
    val state: ValidationState,
    val acceptedSteps: Long,
    val rejectionReason: RejectionReason?,
)

/**
 * Every conservative, tunable constant this feature depends on, centralized in one place per the
 * product requirement ("keep these values centralized so physical A20/A55 testing can tune them") -
 * shared by both [IntervalReconstructor] (reconciliation-window/discontinuity fields) and
 * [StrictStepValidationPolicy] (stability/guard/coverage fields). These are explicitly starting
 * points, not proven-final values - see the class-level doc comment on [StrictStepValidationPolicy].
 */
data class ValidationConstants(
    val samplingIntervalSeconds: Int = 15,
    /** 12-15s range per the product requirement; defaults to the more conservative (slower-to-accept) end. */
    val walkingStabilitySeconds: Int = 15,
    val guardBeforeVehicleSeconds: Int = 30,
    val guardAfterVehicleSeconds: Int = 30,
    val pendingFinalizationDelaySeconds: Int = 120,
    val sampledPositiveMinConsecutive: Int = 2,
    /** ~2x the sampling interval - tolerates exactly one missed sample without losing a run. */
    val sampledPositiveMaxGapSeconds: Int = 30,
    val sampledPositiveMinConfidence: Int = 60,
    val vehicleSampledVetoMinConfidence: Int = 50,
    /**
     * How far apart two signals for the same interrupt-group may be before [IntervalReconstructor]
     * treats a new one as "delivered very late" and switches to the wider/fail-closed handling in
     * its own doc comment, rather than the ordinary short-range reordering check.
     */
    val reconciliationWindowSeconds: Int = 2700,
    /** Tolerates ordinary measurement jitter; catches a real NTP correction or manual clock change. */
    val clockDiscontinuityToleranceMillis: Long = 5_000L,
    /** How long raw `motion_evidence` rows are kept before compaction - never applied to `activity_intervals` or `step_buckets`, both permanent. */
    val motionEvidenceRetentionDays: Int = 7,
) {
    companion object {
        val DEFAULT = ValidationConstants()
    }
}
