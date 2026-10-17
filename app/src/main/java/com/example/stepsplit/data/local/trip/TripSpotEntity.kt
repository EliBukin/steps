package com.example.stepsplit.data.local.trip

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spot the owner marked while a trip was recording - a spring, a viewpoint, a tricky passage
 * (schema v10). [com.example.stepsplit.data.trip.TripRepository] is the only writer.
 *
 * - [number] is the spot's number within its trip, 1, 2, 3... in the order they were marked. It
 *   is stored, not worked out from the order: "Spot 3" stays Spot 3 after Spot 2 is deleted, so it
 *   keeps matching what the confirmation said when it was marked. Unique per trip.
 * - [capturedAtEpochSecond], [latitude], [longitude] and [altitudeMeters] are those of the GPS fix
 *   the spot was placed at (see [com.example.stepsplit.domain.trip.SpotFixRule]), so the time and
 *   the place always belong together. [altitudeMeters] is above the WGS84 ellipsoid, like
 *   [TripPointEntity.altitudeMeters], and null when the fix had none.
 * - [name] is optional, set later on trip detail; it follows the same rules as a trip's name
 *   ([com.example.stepsplit.domain.trip.TripName]). Null shows "Spot N".
 *
 * `onDelete = CASCADE`: deleting a trip deletes its spots, as it does its points. "Keep deleted
 * trips in Stats" keeps none of them.
 */
@Entity(
    tableName = "trip_spots",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tripId", "number"], unique = true)],
)
data class TripSpotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val number: Int,
    val capturedAtEpochSecond: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val name: String? = null,
)
