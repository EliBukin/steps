package com.example.stepsplit.data.local.bucket

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

@Dao
interface StepBucketDao {

    /** REPLACE on the unique (source, startEpochSecond) index makes re-imports idempotent updates - see [com.example.stepsplit.data.repository.StepRepository]'s `normalizeToEntities` for the exact merge rules. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buckets: List<StepBucketEntity>)

    /** Every stored positive-step minute across every source - the canonical raw step history [com.example.stepsplit.domain.classification.WalkClassifier] classifies retrospectively. No validation gate: every positive imported minute counts, per the product requirement that a classification failure must never remove steps from the total. */
    @Query("SELECT * FROM step_buckets WHERE steps > 0 ORDER BY startEpochSecond")
    suspend fun getAllActive(): List<StepBucketEntity>

    @Query("SELECT * FROM step_buckets WHERE localDate IN (:localDates) ORDER BY startEpochSecond")
    fun observeForDates(localDates: List<String>): Flow<List<StepBucketEntity>>

    @Query("SELECT MAX(endEpochSecond) FROM step_buckets WHERE source = :source")
    suspend fun latestBucketEnd(source: String): Long?

    /** Rows touched by a sync's read window, used to carry forward each minute's original zoneId/localDate on a re-import. */
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
     * Lifetime total raw steps and count of distinct active days for one source, aggregated over
     * the ENTIRE `step_buckets` table - no date filter, no LIMIT, so this can never be bounded by
     * any UI-visible date range (e.g. History's rolling 7 days). A normal sync or an empty overlap
     * read can therefore never reduce these totals - buckets are only ever upserted (added or
     * corrected in place), never deleted. Filter [source] to the real production step source's id
     * to exclude debug/sample-data rows (see [countBySource]'s own doc comment for the same
     * convention). Sums the canonical raw [StepBucketEntity.steps] directly - there is no
     * validation gate between import and this total.
     */
    @Query("SELECT COALESCE(SUM(steps), 0) AS totalSteps, COUNT(DISTINCT localDate) AS activeDays FROM step_buckets WHERE source = :source")
    fun observeLifetimeAggregate(source: String): Flow<LifetimeStepsAggregate>

    /** The single calendar day with the highest total steps for one source - ties broken in favor of the more recent day. Same unbounded-history guarantee as [observeLifetimeAggregate]. */
    @Query(
        "SELECT localDate, SUM(steps) AS totalSteps FROM step_buckets " +
            "WHERE source = :source GROUP BY localDate ORDER BY totalSteps DESC, localDate DESC LIMIT 1",
    )
    fun observeBestDay(source: String): Flow<DailyStepTotal?>
}
