package com.example.stepsplit.data.trip

import kotlinx.coroutines.flow.Flow

/**
 * Where the phone is, for the "you are here" dot on the Open map screen - nothing else. Kept apart
 * from [TripLocationClient], which only trip recording uses, so the recording's own guarantees stay
 * as they are. Cold: [locationUpdates] asks for location only while something collects it, and
 * [com.example.stepsplit.ui.map.OpenMapViewModel] collects it only while the map is on screen with
 * the app in the foreground. No fix from here is ever stored, or added to a trip.
 */
interface MapLocationClient {
    /** The phone's last known location, possibly old; null when there is none. Never throws. */
    suspend fun lastLocation(): MapFix?

    /**
     * Fixes as they come, while collected. Closes with an exception only when the request itself is
     * refused (no location permission, say); a phone with location turned off just sends nothing.
     */
    fun locationUpdates(): Flow<MapFix>
}

/** One location fix for the map's dot. */
data class MapFix(
    val latitude: Double,
    val longitude: Double,
    /** The radius around the fix the phone is fairly sure of, in meters; null when it doesn't say. */
    val accuracyMeters: Float?,
    /** The direction of travel, in degrees clockwise from north - only while moving (see [FusedMapLocationClient]). */
    val bearingDegrees: Float?,
    /** When the fix was taken, on Android's elapsed-realtime clock, so its age is right even if the phone's clock changes. */
    val elapsedRealtimeNanos: Long,
)
