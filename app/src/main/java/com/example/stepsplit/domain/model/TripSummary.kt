package com.example.stepsplit.domain.model

/** A trip as shown in lists/detail screens - decoupled from [com.example.stepsplit.data.local.trip.TripEntity]'s Room-specific shape. */
data class TripSummary(
    val id: Long,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val startZoneId: String,
    val state: TripState,
    val distanceMeters: Double,
    val lastAcceptedPointEpochSecond: Long?,
)
