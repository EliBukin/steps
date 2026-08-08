package com.example.stepsplit.data.motion

import com.example.stepsplit.domain.validation.MotionActivityType

/** One already-converted Transition API event - wall-clock time and boot session already derived, see [MotionEvidenceConverter]. */
data class ConvertedTransitionEvent(
    val activityType: MotionActivityType,
    val isEnter: Boolean,
    val eventElapsedRealtimeMillis: Long,
    val bootSessionId: Long,
    /** `(wallClock - elapsedRealtime)` as measured at receipt - see [MotionEvidenceConverter.ReceiptContext]. Compared against previously-stored state to detect a mid-boot clock discontinuity. */
    val bootEpochOffsetMillis: Long,
    val derivedWallClockEpochMilli: Long,
    val receivedAtEpochMilli: Long,
    val dedupeKey: String,
)

data class ConvertedSampledActivity(val activityType: MotionActivityType, val confidence: Int)

/** One already-converted Sampling API result, carrying every probable activity - see the product requirement to store all of them, never just the most-probable one. */
data class ConvertedSampledBatch(
    val activities: List<ConvertedSampledActivity>,
    val eventElapsedRealtimeMillis: Long,
    val bootSessionId: Long,
    val bootEpochOffsetMillis: Long,
    val derivedWallClockEpochMilli: Long,
    val receivedAtEpochMilli: Long,
    val batchId: String,
)

/**
 * What [MotionEvidenceReceiver] depends on to actually process a received result - implemented by
 * [com.example.stepsplit.data.repository.StepRepository], kept as a narrow interface taking
 * already-converted, Context-free domain data (never raw GMS SDK types - those stay confined to
 * `data/motion`) so the repository/domain layers never need an Android [android.content.Context]
 * of their own for this.
 */
interface MotionEvidenceIngestor {
    /** One Transition API delivery - see `StepRepository`'s own doc comment for the exact per-event ingestion pipeline (dedupe, discontinuity check, reconcile, scoped revalidation). */
    suspend fun ingestTransitionEvents(events: List<ConvertedTransitionEvent>)

    /** One Sampling API delivery - processed as one atomic batch, never once per flattened `DetectedActivity` row. */
    suspend fun ingestSampledBatch(batch: ConvertedSampledBatch)

    /** Proactively closes any stale open interval and bumps the temporal-continuity epoch if [newBootSessionId] is actually newer than what's stored - called from [BootAndUpdateReceiver] before any fresh evidence necessarily arrives. */
    suspend fun handleTemporalDiscontinuity(newBootSessionId: Long)
}
