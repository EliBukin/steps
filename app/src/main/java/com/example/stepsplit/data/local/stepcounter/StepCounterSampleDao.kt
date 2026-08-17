package com.example.stepsplit.data.local.stepcounter

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StepCounterSampleDao {

    /** -1 marks a true duplicate (same boot session + same sensor elapsed-realtime timestamp) - see [StepCounterSampleEntity]'s own doc comment. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicate(sample: StepCounterSampleEntity): Long

    /**
     * Every stored sample, ordered so consecutive rows within the same [StepCounterSampleEntity.bootSessionId]
     * are safe to diff pairwise - see [com.example.stepsplit.data.stepsource.SensorStepCounterSource].
     * The table is small in practice (bounded by real movement events, further bounded by
     * [deleteOlderThan]), so a plain full read is simpler and safer than a windowed query that could
     * miss the one baseline sample immediately before a requested window.
     */
    @Query("SELECT * FROM step_counter_samples ORDER BY bootSessionId ASC, elapsedRealtimeMillisAtSample ASC")
    suspend fun getAllOrdered(): List<StepCounterSampleEntity>

    /** The single most recent sample - used by [com.example.stepsplit.data.stepsource.CompositeStepSource] to decide whether the sensor has produced any data at all yet. */
    @Query("SELECT * FROM step_counter_samples ORDER BY bootSessionId DESC, elapsedRealtimeMillisAtSample DESC LIMIT 1")
    suspend fun getLatest(): StepCounterSampleEntity?

    /**
     * The earliest sample this device has ever recorded, permanently protected from [deleteOlderThan]
     * - the stable cutover point [com.example.stepsplit.data.stepsource.CompositeStepSource] uses to
     * decide which slice of [com.example.stepsplit.data.stepsource.LocalRecordingStepSource]'s own
     * history is still needed as backfill. Must never shift later purely because of compaction, or a
     * later sync could re-offer Local Recording data for a span the sensor already authoritatively
     * covered on an earlier sync - see that class's own doc comment.
     */
    @Query("SELECT MIN(wallClockEpochMilli) FROM step_counter_samples")
    suspend fun getEarliestWallClockEpochMilli(): Long?

    /** Drives [com.example.stepsplit.domain.model.deriveStepCollectionHealth]'s staleness check - see that function's own doc comment. */
    @Query("SELECT MAX(wallClockEpochMilli) / 1000 FROM step_counter_samples")
    fun observeLatestWallClockEpochSecond(): Flow<Long?>

    /**
     * Compaction: deletes samples older than [cutoffEpochMilli], but always protects both the single
     * latest row (the next delta's baseline - see [com.example.stepsplit.data.stepsource.SensorStepCounterSource])
     * and the single earliest row (the stable cutover point - see [getEarliestWallClockEpochMilli]'s
     * own doc comment), regardless of how old either one is.
     */
    @Query(
        "DELETE FROM step_counter_samples WHERE wallClockEpochMilli < :cutoffEpochMilli " +
            "AND id NOT IN (SELECT id FROM step_counter_samples ORDER BY bootSessionId DESC, elapsedRealtimeMillisAtSample DESC LIMIT 1) " +
            "AND id NOT IN (SELECT id FROM step_counter_samples ORDER BY wallClockEpochMilli ASC LIMIT 1)",
    )
    suspend fun deleteOlderThan(cutoffEpochMilli: Long): Int

    @Query("SELECT COUNT(*) FROM step_counter_samples")
    suspend fun count(): Int
}
