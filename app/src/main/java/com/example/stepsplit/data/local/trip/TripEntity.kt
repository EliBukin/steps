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
 * [com.example.stepsplit.data.local.bout.WalkBoutEntity.autoClassification]).
 * [lastAcceptedPointEpochSecond] is updated atomically together with each newly accepted
 * [TripPointEntity] insert - see [com.example.stepsplit.data.trip.TripRepository.recordAcceptedBatch].
 * [distanceMeters] is no longer kept up (0 for a trip recorded since): every screen, the GPX file
 * and Stats work the distance out from the points. The column stays for the schema.
 *
 * [name] and [typeOverride] are the owner's own edits (schema v9), null until they make one:
 * [name] is already normalized by [com.example.stepsplit.domain.trip.TripName], and
 * [typeOverride] is a [com.example.stepsplit.domain.model.TripType.name]. The automatic type is
 * never stored - see [com.example.stepsplit.domain.trip.TripTypeRule]. Only
 * [com.example.stepsplit.data.trip.TripRepository.updateTripDetails] writes them, with a targeted
 * update; every other write copies the row it read, so they are carried along.
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
    val name: String? = null,
    val typeOverride: String? = null,
)
