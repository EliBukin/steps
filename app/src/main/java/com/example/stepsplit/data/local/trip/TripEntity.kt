package com.example.stepsplit.data.local.trip

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A manually started/finished GPS trip - see [com.example.stepsplit.data.trip.TripRepository],
 * which is the only writer. Entirely separate from automatic step-classification data
 * ([com.example.stepsplit.data.local.bout.WalkBoutEntity]); a trip never reads or mutates that
 * table, or vice versa.
 *
 * [state] is [com.example.stepsplit.domain.model.TripState.name] - stored as a string, matching
 * this codebase's existing convention for enum columns (see
 * [com.example.stepsplit.data.local.override.SessionOverrideEntity.classification]).
 * [distanceMeters] and [lastAcceptedPointEpochSecond] are updated atomically together with each
 * newly accepted [TripPointEntity] insert - see [com.example.stepsplit.data.trip.TripRepository.recordAcceptedBatch].
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val startZoneId: String,
    val state: String,
    val distanceMeters: Double,
    val lastAcceptedPointEpochSecond: Long?,
    val createdAtEpochSecond: Long,
)
