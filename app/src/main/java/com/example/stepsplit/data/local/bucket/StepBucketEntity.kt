package com.example.stepsplit.data.local.bucket

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One normalized, minute-aligned raw step reading. Only minutes with steps > 0 are stored.
 * [steps] is the canonical, durable step count - it is the single source of truth for totals and
 * for [com.example.stepsplit.domain.classification.WalkClassifier]'s retrospective workout/
 * incidental labeling. There is no validation gate between import and [steps]: every positive
 * value imported from the step source counts, always.
 *
 * The unique (source, startEpochSecond) index is the stable key re-imports upsert against, so
 * reading the same interval from the step source repeatedly can never create duplicates -
 * [androidx.room.OnConflictStrategy.REPLACE] on insert turns re-imports into idempotent updates.
 *
 * [zoneId] and [localDate] are captured at import time using the device's zone *then*, so that
 * later device timezone changes never retroactively change which calendar day a past step
 * belongs to.
 *
 * The columns below this point are inert compatibility columns from a removed strict
 * vehicle-aware validation architecture. Production code no longer reads or writes them
 * meaningfully (every new row is inserted with their plain defaults) - they are kept physically
 * present, unset, purely so an in-place update never needs a destructive migration. A future,
 * dedicated migration may drop them; this one deliberately does not.
 */
@Entity(
    tableName = "step_buckets",
    indices = [
        Index(value = ["source", "startEpochSecond"], unique = true),
        Index(value = ["source", "validationState"]),
    ],
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
    val validationState: String = "PENDING",
    val acceptedSteps: Long = 0,
    val rejectionReason: String? = null,
    val policyVersion: Int? = null,
    val validatedAtEpochSecond: Long? = null,
    val observationStartEpochSecond: Long = startEpochSecond,
    val observationEndEpochSecond: Long = endEpochSecond,
)
