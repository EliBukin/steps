package com.example.stepsplit.data.local.bout

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The cached AUTO classification result for one detected walking bout. This table is fully
 * regenerated (inside a transaction) every time the classifier reruns over the raw step
 * buckets - it is a derived cache, never a source of truth, so recomputing it can never corrupt
 * raw data. Manual overrides live in a separate table keyed by [startEpochSecond] and are
 * re-applied on top after every regeneration.
 */
@Entity(
    tableName = "walk_bouts",
    indices = [Index(value = ["startEpochSecond"], unique = true)],
)
data class WalkBoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
    val activeMinutes: Int,
    val elapsedMinutes: Int,
    val cadence: Double,
    val autoClassification: String,
    val autoConfidence: Double,
    val autoReasonCode: String,
    val classifierVersion: Int,
    val computedAtEpochSecond: Long,
)
