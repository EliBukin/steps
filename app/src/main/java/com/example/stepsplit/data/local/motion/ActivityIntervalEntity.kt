package com.example.stepsplit.data.local.motion

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One permanently-materialized interval of a tracked activity type (`IN_VEHICLE`, `ON_BICYCLE`,
 * `WALKING`, or `RUNNING`) - open ([endWallClockEpochMilli] null) or closed. This table is never
 * compacted, unlike the raw [MotionEvidenceEntity] log: it only grows on genuine activity
 * *changes* (realistically tens of rows/day), and it is the single source of truth validation
 * queries against - with a plain indexed overlap condition, never a time-bounded lookback. A
 * multi-hour vehicle ride that both started and ended long before a bucket is ever validated is
 * just an ordinary closed row here, found the same way regardless of its age - see
 * [com.example.stepsplit.domain.validation.IntervalReconstructor]'s own doc comment for why that
 * property specifically is what makes reconstruction safe.
 *
 * Created and closed exclusively by [com.example.stepsplit.domain.validation.IntervalReconstructor]'s
 * output, applied by the repository - see that object's own doc comment for the exact, narrow rules
 * governing when a row may be opened vs. only ever closed (a sampled result may never open one).
 */
@Entity(
    tableName = "activity_intervals",
    indices = [
        Index(value = ["activityType", "endWallClockEpochMilli"]),
        Index(value = ["activityType", "startWallClockEpochMilli"]),
    ],
)
data class ActivityIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityType: String,
    val startWallClockEpochMilli: Long,
    /** Null = still open/unresolved. */
    val endWallClockEpochMilli: Long?,
    /** See `temporal_continuity_state` - the epoch active when this interval was opened; an interval never spans an epoch boundary (a discontinuity force-closes whatever is open first). */
    val temporalContinuityEpoch: Long,
    /** OPEN | OWN_EXIT | INTERRUPTED_BY_CONFLICT | CLOSED_AT_DISCONTINUITY - see [com.example.stepsplit.domain.validation.IntervalReconstructor.ClosedReason]. */
    val closedReason: String,
)
