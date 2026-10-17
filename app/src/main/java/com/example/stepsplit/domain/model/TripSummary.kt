package com.example.stepsplit.domain.model

/**
 * A trip as shown in lists/detail screens - decoupled from [com.example.stepsplit.data.local.trip.TripEntity]'s Room-specific shape.
 *
 * [name] is the owner's optional name for it (see [com.example.stepsplit.domain.trip.TripName]);
 * null shows the date instead. [typeOverride] is the owner's Walk/Drive choice, null when they
 * left it to the automatic suggestion (see [com.example.stepsplit.domain.trip.TripTypeRule]),
 * which is never stored: it is worked out from the route wherever the route is shown.
 */
data class TripSummary(
    val id: Long,
    val startEpochSecond: Long,
    val endEpochSecond: Long?,
    val startZoneId: String,
    val state: TripState,
    val distanceMeters: Double,
    val lastAcceptedPointEpochSecond: Long?,
    val name: String? = null,
    val typeOverride: TripType? = null,
)
