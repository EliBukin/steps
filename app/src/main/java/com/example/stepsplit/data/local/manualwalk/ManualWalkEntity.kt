package com.example.stepsplit.data.local.manualwalk

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Deprecated: backed the removed explicit "Start walk / Finish walk" feature. No product code
 * reads or writes this entity anymore - step data comes exclusively from automatic retrospective
 * detection (see [com.example.stepsplit.domain.model.SessionMerger]). The entity, its DAO, and the
 * version 1->2 migration that added [autoCompleted]/[autoCompletionMessageShown] are kept as-is
 * (not deleted) so the `manual_walks` table and any rows an earlier app version wrote to it are
 * never dropped by an opportunistic schema change; see [com.example.stepsplit.data.local.StepSplitDatabase]
 * for the compatibility rationale. Dropping this table is left to a future, dedicated migration.
 */
@Entity(tableName = "manual_walks")
data class ManualWalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val steps: Long?,
    val createdAtEpochSecond: Long,
    val autoCompleted: Boolean = false,
    val autoCompletionMessageShown: Boolean = false,
)
