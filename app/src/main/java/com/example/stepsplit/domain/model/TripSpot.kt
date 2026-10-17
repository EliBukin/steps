package com.example.stepsplit.domain.model

/**
 * A spot marked during a trip - see [com.example.stepsplit.data.local.trip.TripSpotEntity] for
 * what each value means. [name] is null for an unnamed spot, which the app shows as "Spot [number]".
 */
data class TripSpot(
    val id: Long,
    val number: Int,
    val capturedAtEpochSecond: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val name: String?,
)
