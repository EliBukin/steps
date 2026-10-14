package com.example.stepsplit.data.local.bout

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** How many walks are labelled workouts, and their total elapsed minutes - see [WalkBoutDao.observeWorkoutTotals]. */
data class WorkoutTotals(
    val count: Int,
    val minutes: Long,
)

@Dao
interface WalkBoutDao {

    @Insert
    suspend fun insertAll(bouts: List<WalkBoutEntity>)

    @Query("DELETE FROM walk_bouts")
    suspend fun clearAll()

    @Query("DELETE FROM walk_bouts WHERE startEpochSecond >= :fromInclusive")
    suspend fun deleteStartingFrom(fromInclusive: Long)

    /** The earliest start among bouts ending after [epochSecond], or null if none does - see StepRepository's `incrementalRecomputeStart`. */
    @Query("SELECT MIN(startEpochSecond) FROM walk_bouts WHERE endEpochSecond > :epochSecond")
    suspend fun earliestStartOfBoutEndingAfter(epochSecond: Long): Long?

    @Query("SELECT MAX(endEpochSecond) FROM walk_bouts")
    suspend fun latestEnd(): Long?

    /** Only rows computed by [classifierVersion] count - a stale row is never treated as current (see CLASSIFIER_VERSION). */
    @Query(
        "SELECT COUNT(*) AS count, COALESCE(SUM(elapsedMinutes), 0) AS minutes FROM walk_bouts " +
            "WHERE autoClassification = :classification AND classifierVersion = :classifierVersion",
    )
    fun observeWorkoutTotals(classification: String, classifierVersion: Int): Flow<WorkoutTotals>

    @Query("SELECT * FROM walk_bouts ORDER BY startEpochSecond DESC")
    fun observeAll(): Flow<List<WalkBoutEntity>>

    @Query("SELECT * FROM walk_bouts ORDER BY startEpochSecond DESC")
    suspend fun getAll(): List<WalkBoutEntity>

    /** True if any cached row was computed by a classifier version other than [currentVersion] - see [com.example.stepsplit.domain.classification.CLASSIFIER_VERSION]. */
    @Query("SELECT EXISTS(SELECT 1 FROM walk_bouts WHERE classifierVersion != :currentVersion)")
    suspend fun hasRowsWithOtherClassifierVersion(currentVersion: Int): Boolean
}
