package com.example.stepsplit.domain.classification

/**
 * Tunable heuristic thresholds used to decide whether a contiguous walking bout looks like a
 * deliberate workout rather than incidental everyday movement. These are a starting heuristic,
 * not ground truth - a phone cannot know user intent with certainty, so the thresholds are
 * user-adjustable in Settings and every classification carries a confidence + reason.
 */
data class ClassificationThresholds(
    val maxGapMinutes: Int = 2,
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
    MANUALLY_RECLASSIFIED,
}

/**
 * The result of automatically classifying one contiguous walking bout. This is the AUTO/derived
 * result only - manual overrides are stored and applied separately so a classifier rerun can
 * never silently discard a user's correction.
 */
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

/** Bump when the classification algorithm itself changes, so stored results can be identified as stale. */
const val CLASSIFIER_VERSION = 1
