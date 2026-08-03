package com.example.stepsplit.data.local.trip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    /** Used by [com.example.stepsplit.data.trip.TripRepository.startTrip] to make Start idempotent - at most one row is ever in this state. */
    @Query("SELECT * FROM trips WHERE state = :state LIMIT 1")
    suspend fun getByState(state: String): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeById(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips ORDER BY startEpochSecond DESC")
    fun observeAll(): Flow<List<TripEntity>>

    /** Cascades to every [TripPointEntity] for this trip - see that entity's foreign key. */
    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)
}
