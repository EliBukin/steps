package com.example.stepsplit.data.local.trip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripPointDao {

    @Insert
    suspend fun insert(point: TripPointEntity): Long

    /** The most recently accepted point, used to compute the next segment's distance - see [com.example.stepsplit.data.trip.TripRepository.recordAcceptedBatch]. */
    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY capturedAtEpochSecond DESC LIMIT 1")
    suspend fun getLastPoint(tripId: Long): TripPointEntity?

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY capturedAtEpochSecond ASC")
    suspend fun getAllForTrip(tripId: Long): List<TripPointEntity>

    @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY capturedAtEpochSecond ASC")
    fun observeForTrip(tripId: Long): Flow<List<TripPointEntity>>

    @Query("SELECT COUNT(*) FROM trip_points WHERE tripId = :tripId")
    fun observePointCount(tripId: Long): Flow<Int>
}
