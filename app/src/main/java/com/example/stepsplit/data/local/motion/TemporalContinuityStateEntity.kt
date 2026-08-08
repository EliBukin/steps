package com.example.stepsplit.data.local.motion

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton row (always `id = 0`) generalizing "boot session" to also cover mid-boot clock
 * discontinuities - see [com.example.stepsplit.domain.validation.IntervalReconstructor]'s own doc
 * comment. [temporalContinuityEpoch] is the value interval logic actually compares (never
 * [bootSessionId] directly): it increments whenever either the OS boot count increases (a real
 * reboot) or [bootEpochOffsetMillis] shifts by more than
 * [com.example.stepsplit.domain.validation.ValidationConstants.clockDiscontinuityToleranceMillis]
 * without a boot change (a real NTP correction or manual clock change, ordinary jitter tolerated).
 * Every open interval is force-closed at the moment either kind of discontinuity is detected, so an
 * interval can never span an epoch boundary by construction.
 */
@Entity(tableName = "temporal_continuity_state")
data class TemporalContinuityStateEntity(
    @PrimaryKey val id: Int = 0,
    val bootSessionId: Long,
    val bootEpochOffsetMillis: Long,
    val temporalContinuityEpoch: Long,
)
