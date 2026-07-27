package com.example.stepsplit.data.local.manualwalk

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An explicit "Start walk / Finish walk" session. [endEpochSecond] and [steps] are null while the
 * walk is ongoing; persisting the ongoing row immediately (on Start) means process death or
 * rotation never silently loses an in-progress manual walk. At most one row with a null
 * [endEpochSecond] should exist at a time - enforced by the repository, not the schema, since
 * Room has no direct "at most one null" constraint.
 */
@Entity(tableName = "manual_walks")
data class ManualWalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val steps: Long?,
    val createdAtEpochSecond: Long,
)
