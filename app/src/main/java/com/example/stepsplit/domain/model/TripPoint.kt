package com.example.stepsplit.domain.model

/** One accepted route point as shown to the UI - decoupled from [com.example.stepsplit.data.local.trip.TripPointEntity]'s Room-specific shape. */
data class TripPoint(
    val capturedAtEpochSecond: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    /**
     * Read time only, never stored: [com.example.stepsplit.domain.trip.RouteSanitizer] kept this
     * point as the start of a new stretch after a gap nobody walked (a drive before a Resume, a bus
     * ride with no fix - see [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy]'s "After
     * a gap"). The jump to it from the point before is drawn as a gap and counts in no distance or
     * moving time.
     */
    val startsStretch: Boolean = false,
)
