package com.example.stepsplit.data.local.override

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A manual reclassification of an auto-detected bout, keyed by that bout's stable
 * [boutStartEpochSecond] anchor. Stored separately from [com.example.stepsplit.data.local.bout.WalkBoutEntity]
 * so regenerating the AUTO classification cache never touches (or loses) a user's manual
 * correction. Manual overrides always take precedence over the automatic result.
 */
@Entity(tableName = "session_overrides")
data class SessionOverrideEntity(
    @PrimaryKey val boutStartEpochSecond: Long,
    val classification: String,
    val overriddenAtEpochSecond: Long,
)
