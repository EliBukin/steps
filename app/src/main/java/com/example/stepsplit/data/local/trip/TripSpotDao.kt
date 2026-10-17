package com.example.stepsplit.data.local.trip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripSpotDao {

    @Insert
    suspend fun insert(spot: TripSpotEntity): Long

    /** [tripId]'s spot with the highest number - the newest - or null before its first spot - see [com.example.stepsplit.data.trip.TripRepository.addSpot]. */
    @Query("SELECT * FROM trip_spots WHERE tripId = :tripId ORDER BY number DESC LIMIT 1")
    suspend fun newest(tripId: Long): TripSpotEntity?

    @Query("SELECT * FROM trip_spots WHERE tripId = :tripId ORDER BY number ASC")
    suspend fun getAllForTrip(tripId: Long): List<TripSpotEntity>

    @Query("SELECT * FROM trip_spots WHERE tripId = :tripId ORDER BY number ASC")
    fun observeForTrip(tripId: Long): Flow<List<TripSpotEntity>>

    /** Returns how many rows changed (0 for an unknown spot). */
    @Query("UPDATE trip_spots SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String?): Int

    @Query("DELETE FROM trip_spots WHERE id = :id")
    suspend fun deleteById(id: Long)
}
