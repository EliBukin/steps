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

    /** REPLACE on the unique (source, startEpochSecond) index makes re-imports idempotent updates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(buckets: List<StepBucketEntity>)

    @Query("SELECT * FROM step_buckets WHERE steps > 0 ORDER BY startEpochSecond")
    suspend fun getAllActive(): List<StepBucketEntity>

    @Query("SELECT * FROM step_buckets WHERE steps > 0 ORDER BY startEpochSecond")
    fun observeAllActive(): Flow<List<StepBucketEntity>>

    @Query("SELECT * FROM step_buckets WHERE localDate IN (:localDates) AND steps > 0 ORDER BY startEpochSecond")
    fun observeForDates(localDates: List<String>): Flow<List<StepBucketEntity>>

    @Query("SELECT MAX(endEpochSecond) FROM step_buckets WHERE source = :source")
    suspend fun latestBucketEnd(source: String): Long?

    /** Rows touched by a sync's read window, used to carry forward each minute's original zoneId/localDate. */
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
     * Lifetime total steps and count of distinct active days for one source, aggregated over the
     * ENTIRE `step_buckets` table - no date filter, no LIMIT, so this can never be bounded by the
     * Local Recording API's retention window (see
     * [com.example.stepsplit.data.repository.StepRepository.RETENTION_WINDOW]) or by any
     * UI-visible date range (e.g. History's rolling 7 days). A normal sync, an empty overlap read,
     * or data aging past that retention window can therefore never reduce these totals - buckets
     * are only ever upserted (added or corrected in place), never deleted, by [upsertAll]. Filter
     * [source] to the real production step source's id to exclude debug/sample-data rows (see
     * [countBySource]'s own doc comment for the same convention).
     */
    @Query(
        "SELECT COALESCE(SUM(steps), 0) AS totalSteps, COUNT(DISTINCT localDate) AS activeDays " +
            "FROM step_buckets WHERE source = :source AND steps > 0",
    )
    fun observeLifetimeAggregate(source: String): Flow<LifetimeStepsAggregate>

    /**
     * The single calendar day with the highest total steps for one source - ties broken in favor
     * of the more recent day. Same unbounded-history guarantee as [observeLifetimeAggregate]: a
     * plain aggregate over every stored row, never date-bounded.
     */
    @Query(
        "SELECT localDate, SUM(steps) AS totalSteps FROM step_buckets " +
            "WHERE source = :source AND steps > 0 " +
            "GROUP BY localDate ORDER BY totalSteps DESC, localDate DESC LIMIT 1",
    )
    fun observeBestDay(source: String): Flow<DailyStepTotal?>
}
