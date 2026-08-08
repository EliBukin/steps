package com.example.stepsplit.data.local.motion

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface TemporalContinuityStateDao {

    @Upsert
    suspend fun upsert(state: TemporalContinuityStateEntity)

    @Query("SELECT * FROM temporal_continuity_state WHERE id = 0")
    suspend fun get(): TemporalContinuityStateEntity?
}
