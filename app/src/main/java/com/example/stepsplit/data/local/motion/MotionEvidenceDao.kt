package com.example.stepsplit.data.local.motion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MotionEvidenceDao {

    /** IGNORE on the unique [MotionEvidenceEntity.dedupeKey] index - a replayed/retried identical event or batch row is a pure no-op, never a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(rows: List<MotionEvidenceEntity>): List<Long>

    /** -1 marks a row Room actually ignored (a true duplicate) - see [insertIgnoringDuplicates]'s own doc comment; callers use this to skip re-applying interval-mutation side effects for a replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOneIgnoringDuplicate(row: MotionEvidenceEntity): Long

    /** Transition-API rows of the given types within a window - used both for the bounded reordering/reconciliation check and for reconstructing nearby SampledInterrupt-equivalent context. */
    @Query(
        "SELECT * FROM motion_evidence WHERE kind IN ('TRANSITION_ENTER', 'TRANSITION_EXIT') " +
            "AND activityType IN (:activityTypes) " +
            "AND derivedWallClockEpochMilli BETWEEN :fromEpochMilli AND :toEpochMilli " +
            "ORDER BY derivedWallClockEpochMilli",
    )
    suspend fun getTransitionsWithin(activityTypes: List<String>, fromEpochMilli: Long, toEpochMilli: Long): List<MotionEvidenceEntity>

    /** Every sampled row (any activity type) within a window - the caller groups by [MotionEvidenceEntity.batchId] to determine each batch's own top activity. */
    @Query(
        "SELECT * FROM motion_evidence WHERE kind = 'SAMPLED' " +
            "AND derivedWallClockEpochMilli BETWEEN :fromEpochMilli AND :toEpochMilli " +
            "ORDER BY derivedWallClockEpochMilli",
    )
    suspend fun getSampledWithin(fromEpochMilli: Long, toEpochMilli: Long): List<MotionEvidenceEntity>

    /** Every already-persisted row for one batch - used to compute a batch's own top (highest-confidence) activity exactly once, atomically, per received `ActivityRecognitionResult`. */
    @Query("SELECT * FROM motion_evidence WHERE batchId = :batchId")
    suspend fun getBatch(batchId: String): List<MotionEvidenceEntity>

    /**
     * The most recent Transition-API event's own timestamp for any of the given types - a plain
     * unbounded `MAX(...)`, never bounded by a reconciliation window. Used to correctly detect "is
     * this newly-received signal unusually late relative to what's already known for this group,"
     * which requires comparing against the group's TRUE last-known signal, not a value drawn from
     * the very narrow window that comparison is meant to widen past.
     */
    @Query(
        "SELECT MAX(derivedWallClockEpochMilli) FROM motion_evidence " +
            "WHERE kind IN ('TRANSITION_ENTER', 'TRANSITION_EXIT') AND activityType IN (:activityTypes)",
    )
    suspend fun getLatestTransitionTimestamp(activityTypes: List<String>): Long?

    /** The most recent sampled result's own timestamp, any activity type - same unbounded reasoning as [getLatestTransitionTimestamp], for the positive group's own "last known signal" check. */
    @Query("SELECT MAX(derivedWallClockEpochMilli) FROM motion_evidence WHERE kind = 'SAMPLED'")
    suspend fun getLatestSampledTimestamp(): Long?

    /** Debug/diagnostics only - the most recently received raw events, regardless of kind. */
    @Query("SELECT * FROM motion_evidence ORDER BY receivedAtEpochMilli DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MotionEvidenceEntity>

    /**
     * Compaction (see [com.example.stepsplit.domain.validation.ValidationConstants.motionEvidenceRetentionDays]) -
     * never touches [ActivityIntervalEntity] (permanent, already materialized independently of this
     * raw log) or `step_buckets` (permanent). The caller is responsible for never passing a cutoff
     * newer than any still-`PENDING` bucket's own evidence needs - see the repository's compaction
     * entry point.
     */
    @Query("DELETE FROM motion_evidence WHERE receivedAtEpochMilli < :cutoffEpochMilli")
    suspend fun deleteOlderThan(cutoffEpochMilli: Long): Int

    @Query("SELECT COUNT(*) FROM motion_evidence")
    suspend fun count(): Int
}
