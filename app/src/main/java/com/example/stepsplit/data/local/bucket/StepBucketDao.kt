package com.example.stepsplit.data.local.bucket

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One source's lifetime total steps and count of distinct active days - see
 * [StepBucketDao.observeLifetimeAggregate] for why this is never bounded by any date range or
 * retention window.
 */
data class LifetimeStepsAggregate(
    val totalSteps: Long,
    val activeDays: Int,
)

/** One calendar day's total steps for a source - see [StepBucketDao.observeBestDay]. */
data class DailyStepTotal(
    val localDate: String,
    val totalSteps: Long,
)

/** One validation state's row count for a source - debug diagnostics only. */
data class ValidationStateCount(
    val validationState: String,
    val count: Int,
)

private const val ACCEPTED_STATES = "('ACCEPTED_WALKING', 'ACCEPTED_RUNNING')"

@Dao
interface StepBucketDao {

    /** REPLACE on the unique (source, startEpochSecond) index makes re-imports idempotent updates - see [com.example.stepsplit.data.repository.StepRepository]'s `normalizeToEntities` for the exact merge rules governing validation-column carry-forward on a re-import. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buckets: List<StepBucketEntity>)

    @Update
    suspend fun updateAll(buckets: List<StepBucketEntity>)

    @Query("SELECT * FROM step_buckets WHERE steps > 0 ORDER BY startEpochSecond")
    suspend fun getAllActive(): List<StepBucketEntity>

    @Query("SELECT * FROM step_buckets WHERE steps > 0 ORDER BY startEpochSecond")
    fun observeAllActive(): Flow<List<StepBucketEntity>>

    /**
     * Verified-only input for [com.example.stepsplit.domain.classification.WalkClassifier] - see
     * that DAO method's own doc comment on why this (deliberately, like [getAllActive]) has no
     * source filter, only a validation-state one. A pending or rejected minute contributes nothing
     * to Sessions/Today/History until (and unless) it is actually accepted.
     */
    @Query("SELECT * FROM step_buckets WHERE validationState IN $ACCEPTED_STATES ORDER BY startEpochSecond")
    suspend fun getAllAccepted(): List<StepBucketEntity>

    @Query("SELECT * FROM step_buckets WHERE localDate IN (:localDates) AND validationState IN $ACCEPTED_STATES ORDER BY startEpochSecond")
    fun observeForDates(localDates: List<String>): Flow<List<StepBucketEntity>>

    @Query("SELECT MAX(endEpochSecond) FROM step_buckets WHERE source = :source")
    suspend fun latestBucketEnd(source: String): Long?

    /** Rows touched by a sync's read window, used to carry forward each minute's original zoneId/localDate/validation state. */
    @Query("SELECT * FROM step_buckets WHERE source = :source AND startEpochSecond >= :fromInclusive")
    suspend fun getFrom(source: String, fromInclusive: Long): List<StepBucketEntity>

    @Query("SELECT COUNT(*) FROM step_buckets")
    suspend fun count(): Int

    /** Row count for one specific source - see StepRepository.debugStoredBucketCount's own doc comment for why this must not be conflated with [count]'s all-sources total. */
    @Query("SELECT COUNT(*) FROM step_buckets WHERE source = :source")
    suspend fun countBySource(source: String): Int

    @Query("DELETE FROM step_buckets")
    suspend fun clearAll()

    /**
     * Lifetime total ACCEPTED steps and count of distinct active days for one source, aggregated
     * over the ENTIRE `step_buckets` table - no date filter, no LIMIT, so this can never be bounded
     * by the Local Recording API's retention window (see
     * [com.example.stepsplit.data.repository.StepRepository.RETENTION_WINDOW]) or by any
     * UI-visible date range (e.g. History's rolling 7 days). A normal sync, an empty overlap read,
     * or data aging past that retention window can therefore never reduce these totals - buckets
     * are only ever upserted (added or corrected in place), never deleted, by [upsertAll]. Filter
     * [source] to the real production step source's id to exclude debug/sample-data rows (see
     * [countBySource]'s own doc comment for the same convention). Sums [StepBucketEntity.acceptedSteps],
     * never the raw [StepBucketEntity.steps] - pending/rejected minutes must never contribute.
     */
    @Query(
        "SELECT COALESCE(SUM(acceptedSteps), 0) AS totalSteps, COUNT(DISTINCT localDate) AS activeDays " +
            "FROM step_buckets WHERE source = :source AND validationState IN $ACCEPTED_STATES",
    )
    fun observeLifetimeAggregate(source: String): Flow<LifetimeStepsAggregate>

    /**
     * The single calendar day with the highest total ACCEPTED steps for one source - ties broken in
     * favor of the more recent day. Same unbounded-history guarantee as [observeLifetimeAggregate].
     */
    @Query(
        "SELECT localDate, SUM(acceptedSteps) AS totalSteps FROM step_buckets " +
            "WHERE source = :source AND validationState IN $ACCEPTED_STATES " +
            "GROUP BY localDate ORDER BY totalSteps DESC, localDate DESC LIMIT 1",
    )
    fun observeBestDay(source: String): Flow<DailyStepTotal?>

    /**
     * Lifetime total steps still carrying [com.example.stepsplit.domain.validation.ValidationState.LEGACY_UNVERIFIED]
     * for one source - pre-existing history imported before strict validation existed, permanently
     * preserved but never counted toward verified totals. Feeds the Stats tab's own separate
     * "earlier recorded steps" card - see that screen's own doc comment for why this must never be
     * merged into [observeLifetimeAggregate]'s verified total.
     */
    @Query(
        "SELECT COALESCE(SUM(steps), 0) AS totalSteps, COUNT(DISTINCT localDate) AS activeDays " +
            "FROM step_buckets WHERE source = :source AND validationState = 'LEGACY_UNVERIFIED'",
    )
    fun observeLegacyAggregate(source: String): Flow<LifetimeStepsAggregate>

    /** Diagnostics only - pending/accepted/rejected/legacy row counts for the debug panel. */
    @Query("SELECT validationState, COUNT(*) AS count FROM step_buckets WHERE source = :source GROUP BY validationState")
    fun observeValidationStateCounts(source: String): Flow<List<ValidationStateCount>>

    @Query("SELECT COUNT(*) FROM step_buckets WHERE source = :source AND validationState = 'PENDING'")
    fun observePendingCount(source: String): Flow<Int>

    /**
     * Buckets in `PENDING` or `ACCEPTED_*` whose observation span overlaps `[fromInclusive,
     * toExclusive)` - the scoped-revalidation target set (see
     * [com.example.stepsplit.domain.validation.StrictStepValidationPolicy]'s own state-machine doc
     * comment for why `REJECTED_*`/`LEGACY_UNVERIFIED` are deliberately excluded - they are terminal
     * and never revisited by routine evidence-triggered revalidation).
     */
    @Query(
        "SELECT * FROM step_buckets WHERE source = :source " +
            "AND validationState IN ('PENDING', 'ACCEPTED_WALKING', 'ACCEPTED_RUNNING') " +
            "AND observationStartEpochSecond < :toExclusive AND observationEndEpochSecond > :fromInclusive",
    )
    suspend fun getRevalidationCandidates(source: String, fromInclusive: Long, toExclusive: Long): List<StepBucketEntity>

    /** `PENDING` buckets whose finalization deadline (observationEnd + the configured delay) has already passed - see `StepRepository.finalizeDuePendingBuckets`. */
    @Query(
        "SELECT * FROM step_buckets WHERE source = :source AND validationState = 'PENDING' " +
            "AND (observationEndEpochSecond + :delaySeconds) <= :nowEpochSecond",
    )
    suspend fun getDuePending(source: String, nowEpochSecond: Long, delaySeconds: Long): List<StepBucketEntity>

    /** The earliest still-outstanding finalization deadline for one source, or null if nothing is `PENDING` - see `PendingBucketFinalizationScheduler`. */
    @Query("SELECT MIN(observationEndEpochSecond) FROM step_buckets WHERE source = :source AND validationState = 'PENDING'")
    suspend fun earliestPendingObservationEnd(source: String): Long?

    /** The oldest still-`PENDING` bucket's own observation start, or null if nothing is `PENDING` - bounds `motion_evidence` compaction so it can never delete evidence a genuinely-pending bucket might still need. */
    @Query("SELECT MIN(observationStartEpochSecond) FROM step_buckets WHERE source = :source AND validationState = 'PENDING'")
    suspend fun oldestPendingObservationStart(source: String): Long?

    /** `PENDING`/`REJECTED_UNVERIFIED` rows not on the current policy version - the rare, explicit re-evaluation pass, never routine revalidation. `ACCEPTED_*`/`REJECTED_VEHICLE`/`REJECTED_BICYCLE`/`LEGACY_UNVERIFIED` are deliberately excluded - see `StrictStepValidationPolicy`'s state-machine doc comment. */
    @Query(
        "SELECT * FROM step_buckets WHERE source = :source " +
            "AND validationState IN ('PENDING', 'REJECTED_UNVERIFIED') AND (policyVersion IS NULL OR policyVersion != :currentPolicyVersion)",
    )
    suspend fun getStaleForPolicyVersion(source: String, currentPolicyVersion: Int): List<StepBucketEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM step_buckets WHERE source = :source AND validationState IN ('PENDING', 'REJECTED_UNVERIFIED') AND (policyVersion IS NULL OR policyVersion != :currentPolicyVersion))")
    suspend fun hasRowsWithStalePolicyVersion(source: String, currentPolicyVersion: Int): Boolean
}
