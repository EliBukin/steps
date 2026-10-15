package com.example.stepsplit.data.local.trip

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What is left of a finished trip deleted while "Keep deleted trips in Stats" was on: when it
 * started, how long it took and its displayed distance, so the Stats screen's trip totals keep
 * counting it. No route and no location - the trip's points are deleted with it. Written only by
 * [com.example.stepsplit.data.trip.TripRepository.deleteTrip].
 */
@Entity(tableName = "deleted_trip_totals")
data class DeletedTripTotalsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochSecond: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
)
