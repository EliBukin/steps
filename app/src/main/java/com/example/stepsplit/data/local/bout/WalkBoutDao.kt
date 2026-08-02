package com.example.stepsplit.data.local.bout

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkBoutDao {

    @Insert
    suspend fun insertAll(bouts: List<WalkBoutEntity>)

    @Query("DELETE FROM walk_bouts")
    suspend fun clearAll()

    @Query("SELECT * FROM walk_bouts ORDER BY startEpochSecond DESC")
    fun observeAll(): Flow<List<WalkBoutEntity>>

    @Query("SELECT * FROM walk_bouts ORDER BY startEpochSecond DESC")
    suspend fun getAll(): List<WalkBoutEntity>

    /** True if any cached row was computed by a classifier version other than [currentVersion] - see [com.example.stepsplit.domain.classification.CLASSIFIER_VERSION]. */
    @Query("SELECT EXISTS(SELECT 1 FROM walk_bouts WHERE classifierVersion != :currentVersion)")
    suspend fun hasRowsWithOtherClassifierVersion(currentVersion: Int): Boolean
}
