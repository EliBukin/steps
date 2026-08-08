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
 * See [com.example.stepsplit.data.repository.StepRepository]'s own doc comment on `normalizeToEntities`
 * for the exact merge rules governing which of the columns below a re-import is allowed to change.
 *
 * [zoneId] and [localDate] are captured at import time using the device's zone *then*, so that
 * later device timezone changes never retroactively change which calendar day a past step
 * belongs to.
 *
 * The strict vehicle-validation columns below keep the raw measurement ([steps]) permanently
 * separate from the verified one ([acceptedSteps]) - see
 * [com.example.stepsplit.domain.validation.StrictStepValidationPolicy] for the full decision logic
 * these columns record the outcome of. [steps] is never mutated to "correct" a rejection; only
 * [acceptedSteps] (and the state/reason columns) change as validation runs.
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
    /** One of [com.example.stepsplit.domain.validation.ValidationState]'s `name`s. */
    val validationState: String = "PENDING",
    /** 0 unless [validationState] is `ACCEPTED_WALKING`/`ACCEPTED_RUNNING`, in which case it always equals [steps] - validation is all-or-nothing per bucket, never partial. */
    val acceptedSteps: Long = 0,
    /** One of [com.example.stepsplit.domain.validation.RejectionReason]'s `name`s, or null unless `validationState` is a `REJECTED_*` value. */
    val rejectionReason: String? = null,
    /** [com.example.stepsplit.domain.validation.StrictStepValidationPolicy.POLICY_VERSION] this row was last validated against - null only for `LEGACY_UNVERIFIED` rows, which were never evaluated by the real policy at all. */
    val policyVersion: Int? = null,
    val validatedAtEpochSecond: Long? = null,
    /**
     * The ORIGINAL raw interval's own `[start, end)` bounds this minute was normalized/split from -
     * see [com.example.stepsplit.domain.aggregation.BucketNormalizer]. Validation runs once per
     * distinct observation span (grouping by these two columns), never per-minute independently, so
     * a raw observation spanning several minutes is accepted or rejected as a single whole - see
     * the product requirement's "reject the entire observation rather than guessing which steps
     * were walking." Equal to [startEpochSecond]/[endEpochSecond] for the current production source,
     * which already reports 1-minute-aligned intervals.
     */
    val observationStartEpochSecond: Long = startEpochSecond,
    val observationEndEpochSecond: Long = endEpochSecond,
)
