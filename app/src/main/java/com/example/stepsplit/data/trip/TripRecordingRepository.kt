package com.example.stepsplit.data.trip

/**
 * The narrow slice of [TripRepository] that [com.example.stepsplit.trip.service
 * .TripRecordingCommandController] actually depends on - extracted purely so a test can substitute a
 * delegating wrapper around a real [TripRepository] (forwarding every call unchanged except the one
 * under test) to deterministically pause a handler mid-flight at an exact suspend boundary, without
 * needing a full fake Room-backed implementation or a sleep. Every method here that accepts a
 * `token` is part of this codebase's ownership-token contract - see [TripRepository.beginRecording]
 * and [TripRepository.markTripInterruptedIfStillOwned]'s own doc comments for what that guarantees.
 */
interface TripRecordingRepository {
    suspend fun startTrip(): Long
    suspend fun resumeInterruptedTrip(tripId: Long): Boolean
    suspend fun getActiveTripId(): Long?
    suspend fun beginFinish(tripId: Long, token: Long, cutoffEpochSecond: Long)
    suspend fun finishTripIfOwner(tripId: Long, endEpochSecond: Long, token: Long): Boolean
    suspend fun cancelFinish(token: Long)
    suspend fun beginRecording(tripId: Long, token: Long)
    suspend fun markTripInterruptedIfStillOwned(tripId: Long, token: Long, isCurrent: () -> Boolean): Boolean
}
