package com.example.stepsplit.data.local.bucket

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
}
