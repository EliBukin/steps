package com.example.stepsplit.data.local.motion

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw piece of motion evidence exactly as received - either one Activity Recognition
 * Transition API event, or one flattened `DetectedActivity` row from one Sampling API result.
 * Kept forever available for diagnostics/audit (compacted after
 * [com.example.stepsplit.domain.validation.ValidationConstants.motionEvidenceRetentionDays] - see
 * [MotionEvidenceDao.deleteOlderThan] - but never the source of truth for "what is currently
 * happening"; that is [ActivityIntervalEntity], permanently materialized from these rows and never
 * compacted).
 *
 * [eventElapsedRealtimeMillis] is the event's own original timestamp - never the time StepSplit
 * happened to receive it, which [receivedAtEpochMilli] records separately. [derivedWallClockEpochMilli]
 * is computed once, at receipt, from `(wallClockAtReceipt - elapsedRealtimeAtReceipt) + eventElapsedRealtimeMillis`
 * - valid because Google Play services does not redeliver pending Transition/Sampling events across
 * a reboot (registration itself is invalidated by one). [bootSessionId] is `Settings.Global.BOOT_COUNT`
 * at receipt time, kept for audit/diagnostics; [dedupeKey] is what actually makes replayed/duplicate
 * delivery idempotent (see [MotionEvidenceDao.insertIgnoringDuplicates]).
 */
@Entity(
    tableName = "motion_evidence",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["derivedWallClockEpochMilli"]),
        Index(value = ["bootSessionId"]),
    ],
)
data class MotionEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TRANSITION_ENTER | TRANSITION_EXIT | SAMPLED - see [com.example.stepsplit.domain.validation.MotionEvidenceKind]. */
    val kind: String,
    /** See [com.example.stepsplit.domain.validation.MotionActivityType]. */
    val activityType: String,
    /** 0-100; null for a TRANSITION row (the Transition API reports no confidence). */
    val confidence: Int?,
    val eventElapsedRealtimeMillis: Long,
    val bootSessionId: Long,
    val derivedWallClockEpochMilli: Long,
    /** The temporal-continuity epoch active in `temporal_continuity_state` when this row was ingested - see [com.example.stepsplit.domain.validation.StrictStepValidationPolicy]'s own doc comment for why sampled positive evidence from a stale epoch must never count toward coverage. */
    val temporalContinuityEpoch: Long,
    val receivedAtEpochMilli: Long,
    /**
     * Stable per-event identity so a replayed/retried broadcast can never duplicate a row -
     * `"$kind:$activityType:$eventElapsedRealtimeMillis:$bootSessionId"` for a transition,
     * `"$batchId:$activityType"` for a sampled row (see [batchId]).
     */
    val dedupeKey: String,
    /**
     * Null for TRANSITION rows. For a SAMPLED row: `"$bootSessionId:$elapsedRealtimeMillisOfResult"`
     * - deterministic from the result's own [com.google.android.gms.location.ActivityRecognitionResult.getElapsedRealtimeMillis],
     * never a randomly generated id, so a replayed/retried identical result produces the identical
     * batchId rather than masquerading as a second batch. Every `DetectedActivity` flattened from
     * one result shares the same batchId - see [MotionEvidenceDao.getTopActivityForBatch].
     */
    val batchId: String?,
)
