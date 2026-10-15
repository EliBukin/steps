package com.example.stepsplit.data.local.trip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedTripTotalsDao {

    @Insert
    suspend fun insert(totals: DeletedTripTotalsEntity): Long

    @Query("SELECT * FROM deleted_trip_totals ORDER BY startEpochSecond DESC")
    fun observeAll(): Flow<List<DeletedTripTotalsEntity>>
}
