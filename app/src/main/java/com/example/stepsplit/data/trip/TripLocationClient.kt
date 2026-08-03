package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
import kotlinx.coroutines.flow.Flow

/**
 * A pluggable source of live GPS fixes while a trip is recording. Cold: [locationUpdates] only
 * requests platform location updates for as long as something is actively collecting the flow,
 * and stops the moment collection is cancelled - see [FusedTripLocationClient]'s own doc comment.
 * [com.example.stepsplit.data.trip.TripRecordingCoordinator] is the only production caller, and it
 * only ever collects this while a trip is ACTIVE, so no location request is ever made while idle.
 */
interface TripLocationClient {
    /** Each emission is one batch of fixes as delivered by the platform; callers must still sort/validate before persisting - see [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy]. */
    fun locationUpdates(): Flow<List<RawLocationSample>>

    /**
     * Best-effort request to deliver any already-batched fixes now, used when finishing a trip so
     * a just-recorded but not-yet-delivered fix isn't unnecessarily lost - see
     * [com.example.stepsplit.trip.service.TripRecordingCommandController.handleFinish]. Default
     * no-op so a fake without special behavior doesn't need to implement it. This call itself is
     * **not** guaranteed to return promptly - a real flush is backed by a Play Services `Task` that
     * can in principle never complete - so callers must wrap it in their own bounded timeout rather
     * than assuming it. Delivery of any flushed fixes is separately asynchronous, arriving (if at
     * all) through a still-active [locationUpdates] collection.
     */
    suspend fun flush() {}
}
