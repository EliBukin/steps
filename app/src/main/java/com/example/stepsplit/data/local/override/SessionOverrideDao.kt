package com.example.stepsplit.data.local.override

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: SessionOverrideEntity)

    @Query("SELECT * FROM session_overrides")
    suspend fun getAll(): List<SessionOverrideEntity>

    @Query("SELECT * FROM session_overrides")
    fun observeAll(): Flow<List<SessionOverrideEntity>>
}
