package com.example.stepsplit.data.local.trip

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One GPS fix already accepted by [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] -
 * rejected samples are never persisted. [id] is the stable per-row key; because acceptance
 * requires a strictly later [capturedAtEpochSecond] than the previous accepted point (see the
 * policy), ordering by [capturedAtEpochSecond] is always equivalent to insertion order for a given
 * trip. `onDelete = CASCADE` means deleting a [TripEntity] row removes every point with it in one
 * step - see [com.example.stepsplit.data.local.trip.TripDao.deleteById].
 */
@Entity(
    tableName = "trip_points",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "capturedAtEpochSecond"])],
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val capturedAtEpochSecond: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
)
