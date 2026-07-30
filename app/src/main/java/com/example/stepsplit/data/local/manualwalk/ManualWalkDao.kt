package com.example.stepsplit.data.local.manualwalk

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualWalkDao {

    @Insert
    suspend fun insert(walk: ManualWalkEntity): Long

    @Update
    suspend fun update(walk: ManualWalkEntity)

    @Query("SELECT * FROM manual_walks WHERE endEpochSecond IS NULL LIMIT 1")
    suspend fun getOngoing(): ManualWalkEntity?

    @Query("SELECT * FROM manual_walks WHERE endEpochSecond IS NULL LIMIT 1")
    fun observeOngoing(): Flow<ManualWalkEntity?>

    @Query("SELECT * FROM manual_walks WHERE endEpochSecond IS NOT NULL ORDER BY startEpochSecond DESC")
    fun observeFinished(): Flow<List<ManualWalkEntity>>

    @Query("SELECT * FROM manual_walks WHERE id = :id")
    suspend fun getById(id: Long): ManualWalkEntity?

    @Query("DELETE FROM manual_walks WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Auto-completed walks whose one-shot "ended automatically" message hasn't been shown yet, oldest first. */
    @Query("SELECT * FROM manual_walks WHERE autoCompleted = 1 AND autoCompletionMessageShown = 0 ORDER BY endEpochSecond ASC")
    fun observeUnacknowledgedAutoCompletions(): Flow<List<ManualWalkEntity>>
}
