package com.example.stepsplit.data.trip

/**
 * The narrow slice of [TripRepository] that [com.example.stepsplit.trip.service
 * .TripRecordingCommandController] actually depends on - extracted purely so a test can substitute a
 * delegating wrapper around a real [TripRepository] (forwarding every call unchanged except the one
 * under test) to deterministically pause a handler mid-flight at an exact suspend boundary, without
 * needing a full fake Room-backed implementation or a sleep. Every method here that accepts a
 * `token` is part of this codebase's ownership-token contract - see [TripRepository.claimTripForStart]
 * and [TripRepository.markTripInterruptedIfStillOwned]'s own doc comments for what that guarantees.
 *
 * Deliberately has no separate "resolve/create a trip" step followed by a later "register ownership"
 * step: [claimTripForStart], [claimTripForResume], and [claimActiveTripForRestart] each do both
 * atomically, in one call, so there is no suspend gap in which a concurrent reconciliation (see
 * [markTripInterruptedIfStillOwned]) could observe a trip that is ACTIVE but not yet owned - see
 * [TripRepository]'s own doc comment on why that gap used to be real.
 *
 * Each `claim*` method also takes an [isCurrent] callback, evaluated atomically at the exact mutation
 * point (see [TripRepository.claimTripForStart]'s own doc comment) for the same reason
 * [markTripInterruptedIfStillOwned] does: the monotonic token compare-and-set alone cannot see a
 * newer generation that has merely been *reserved* on the caller's own gate but has not yet completed
 * a claim of its own - only the caller's own gate knows that. Both checks are required; neither is
 * sufficient alone.
 */
interface TripRecordingRepository {
    suspend fun claimTripForStart(token: Long, isCurrent: () -> Boolean): Long?
    suspend fun claimTripForResume(tripId: Long, token: Long, isCurrent: () -> Boolean): Long?
    suspend fun claimActiveTripForRestart(token: Long, isCurrent: () -> Boolean): Long?
    suspend fun getActiveTripId(): Long?
    suspend fun beginFinish(tripId: Long, token: Long, cutoffEpochSecond: Long)
    suspend fun finishTripIfOwner(tripId: Long, endEpochSecond: Long, token: Long): Boolean
    suspend fun cancelFinish(token: Long)
    suspend fun markTripInterruptedIfStillOwned(token: Long, isCurrent: () -> Boolean): Boolean
}
