package com.example.stepsplit.domain.classification

/**
 * Tunable heuristic thresholds used to decide whether a contiguous walking bout looks like a
 * deliberate workout rather than incidental everyday movement. These are a starting heuristic,
 * not ground truth - a phone cannot know user intent with certainty, so the thresholds are
 * user-adjustable in Settings and every classification carries a confidence + reason.
 */
data class ClassificationThresholds(
    /** A gap of up to this many idle minutes between two active minutes stays inside the same bout. */
    val maxGapMinutes: Int = 2,
    /**
     * How long the most recent bout must sit idle before it is finalized (classified and
     * surfaced as a session) at all - see [com.example.stepsplit.domain.classification.WalkClassifier].
     * Always greater than [maxGapMinutes] (enforced by [isValid]): a short internal pause merges
     * into the same bout, but a much longer one is required before treating that bout as *done*,
     * since the Local Recording API is not a real-time stream and more of the same bout could
     * still arrive on a later sync.
     */
    val idleFinalizeMinutes: Int = 3,
    val minBoutDurationMinutes: Int = 10,
    val minActiveMinutes: Int = 8,
    val minSteps: Long = 600L,
    val minCadenceStepsPerMinute: Double = 60.0,
) {
    fun isValid(): Boolean =
        maxGapMinutes > 0 &&
            idleFinalizeMinutes > maxGapMinutes &&
            minBoutDurationMinutes > 0 &&
            minActiveMinutes > 0 &&
            minSteps > 0 &&
            minCadenceStepsPerMinute > 0.0

    companion object {
        val DEFAULT = ClassificationThresholds()
    }
}

/** One normalized, minute-aligned bucket of raw step data. [startEpochSecond] is minute-aligned. */
data class MinuteBucket(
    val startEpochSecond: Long,
    val steps: Long,
)

enum class BoutClassification {
    WORKOUT,
    INCIDENTAL,
}

/** Which checks a bout failed (or that it passed all of them), kept structured for localized display. */
enum class ClassificationReasonCode {
    MEETS_ALL_THRESHOLDS,
    DURATION_TOO_SHORT,
    TOO_FEW_ACTIVE_MINUTES,
    TOO_FEW_STEPS,
    CADENCE_TOO_LOW,
    MULTIPLE_THRESHOLDS_NOT_MET,
}

/** The result of automatically classifying one contiguous walking bout. */
data class ClassifiedBout(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
    val activeMinutes: Int,
    val elapsedMinutes: Int,
    val cadence: Double,
    val classification: BoutClassification,
    val confidence: Double,
    val reasonCode: ClassificationReasonCode,
)

/**
 * Bump when the classification algorithm itself changes, so stored [com.example.stepsplit.data.local.bout.WalkBoutEntity]
 * rows produced by an older version can be identified as stale and never treated as current
 * results (see [com.example.stepsplit.data.repository.StepRepository]). Version 2: trailing-bout
 * idle finalization ([WalkClassifier]'s `nowEpochSecond` parameter) changed which bouts a given
 * raw history produces without changing the raw data itself, so cached results computed under
 * version 1 are no longer trustworthy and must be recomputed, not just left in place.
 */
const val CLASSIFIER_VERSION = 2
