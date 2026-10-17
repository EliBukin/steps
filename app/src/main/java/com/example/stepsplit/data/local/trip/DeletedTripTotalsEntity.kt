package com.example.stepsplit.data.local.trip

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What is left of a finished trip deleted while "Keep deleted trips in Stats" was on: when it
 * started, how long it took, its displayed distance and whether it was a walk or a drive, so the
 * Stats screen's trip totals keep counting it in the right group. No route, no name and no
 * location - the trip's points are deleted with it. Written only by
 * [com.example.stepsplit.data.trip.TripRepository.deleteTrip].
 *
 * [type] is a [com.example.stepsplit.domain.model.TripType.name]: the trip's type at the moment it
 * was deleted (the owner's choice, or the suggestion from its route). Rows kept before schema v9
 * got theirs from their average speed in the migration - see `MIGRATION_8_9`.
 */
@Entity(tableName = "deleted_trip_totals")
data class DeletedTripTotalsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    @ColumnInfo(defaultValue = "WALK") val type: String = "WALK",
)
