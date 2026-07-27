package com.example.stepsplit.data.local.bucket

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One normalized, minute-aligned raw step reading. Only minutes with steps > 0 are stored.
 *
 * The unique (source, startEpochSecond) index is the stable key re-imports upsert against, so
 * reading the same interval from the step source repeatedly can never create duplicates -
 * [androidx.room.OnConflictStrategy.REPLACE] on insert turns re-imports into idempotent updates.
 *
 * [zoneId] and [localDate] are captured at import time using the device's zone *then*, so that
 * later device timezone changes never retroactively change which calendar day a past step
 * belongs to.
 */
@Entity(
    tableName = "step_buckets",
    indices = [Index(value = ["source", "startEpochSecond"], unique = true)],
)
data class StepBucketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
    val zoneId: String,
    val localDate: String,
    val importedAtEpochSecond: Long,
)
