package com.example.stepsplit.domain.model

/** One accepted route point as shown to the UI - decoupled from [com.example.stepsplit.data.local.trip.TripPointEntity]'s Room-specific shape. */
data class TripPoint(
    val capturedAtEpochSecond: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
)
