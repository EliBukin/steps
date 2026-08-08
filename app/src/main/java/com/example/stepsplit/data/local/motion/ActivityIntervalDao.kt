package com.example.stepsplit.data.local.motion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ActivityIntervalDao {

    @Insert
    suspend fun insert(interval: ActivityIntervalEntity): Long

    /** Targeted, single-row close by id - never a range/bulk update. See [com.example.stepsplit.domain.validation.IntervalReconstructor]'s own doc comment on why. */
    @Query("UPDATE activity_intervals SET endWallClockEpochMilli = :endWallClockEpochMilli, closedReason = :closedReason WHERE id = :id")
    suspend fun close(id: Long, endWallClockEpochMilli: Long, closedReason: String)

    /** The unbounded §1.2-style seed - at most one row per type, however old its own start is. */
    @Query("SELECT * FROM activity_intervals WHERE activityType IN (:activityTypes) AND endWallClockEpochMilli IS NULL")
    suspend fun getOpen(activityTypes: List<String>): List<ActivityIntervalEntity>

    @Query("SELECT * FROM activity_intervals WHERE endWallClockEpochMilli IS NULL")
    suspend fun getAllOpen(): List<ActivityIntervalEntity>

    /**
     * Every interval (open or closed) that could possibly overlap `[afterEpochMilli, beforeEpochMilli)`
     * - a plain indexed range condition, never bounded by how long ago an interval started. This is
     * the query [com.example.stepsplit.domain.validation.StrictStepValidationPolicy] is fed from -
     * see [ActivityIntervalEntity]'s own doc comment for why this must never be time-bounded on the
     * "how old" axis (only on the "does it overlap" axis, which is a correctness condition, not a
     * performance shortcut).
     */
    @Query(
        "SELECT * FROM activity_intervals WHERE activityType IN (:activityTypes) " +
            "AND startWallClockEpochMilli < :beforeEpochMilli " +
            "AND (endWallClockEpochMilli IS NULL OR endWallClockEpochMilli > :afterEpochMilli)",
    )
    suspend fun getOverlapping(activityTypes: List<String>, afterEpochMilli: Long, beforeEpochMilli: Long): List<ActivityIntervalEntity>

    /** Force-closes every currently-open row (both groups) at a reboot/clock-discontinuity boundary - still one targeted `UPDATE` per open row conceptually, just applied to the (always small, at most 4) open set at once. */
    @Query("UPDATE activity_intervals SET endWallClockEpochMilli = :atEpochMilli, closedReason = 'CLOSED_AT_DISCONTINUITY' WHERE endWallClockEpochMilli IS NULL")
    suspend fun forceCloseAllOpen(atEpochMilli: Long): Int

    @Query("SELECT COUNT(*) FROM activity_intervals")
    suspend fun count(): Int
}
