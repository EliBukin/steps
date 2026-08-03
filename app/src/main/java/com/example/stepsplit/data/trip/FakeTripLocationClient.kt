package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Deterministic [TripLocationClient] for tests. [emit] pushes a batch to every currently active
 * collector - there is deliberately no buffering/replay, mirroring a live GPS feed where a fix
 * missed while nothing was collecting is simply gone. [activeSubscriptionCount] proves collection
 * only happens while something is actually gathering updates, used to assert no location request
 * occurs while idle.
 *
 * Also simulates the distinct ways production behavior can diverge from "recording proceeds
 * normally" (see [FusedTripLocationClient]'s doc comment for why these are treated differently, not
 * interchangeably):
 * - [registrationFailure] mirrors the platform rejecting registration itself - the flow fails as
 *   soon as something starts collecting it. A *genuine* failure, correctly interrupts the trip.
 * - [failActiveCollection] mirrors an actual, unexpected exception terminating an already-active
 *   collection (e.g. a permission revoked mid-trip). Also a genuine failure.
 * - [neverCompletingFlush] mirrors a Play Services flush `Task` that never completes - *not* a
 *   failure at all, just something the caller (see `TripRecordingCommandController.handleFinish`)
 *   must not wait on indefinitely.
 *
 * None of these model "GPS toggled off"/transient provider unavailability - that is not a Flow
 * failure in production (see [FusedTripLocationClient]) and has no equivalent here on purpose.
 */
class FakeTripLocationClient(
    private val registrationFailure: Throwable? = null,
    private val neverCompletingFlush: Boolean = false,
) : TripLocationClient {
    private val channels = mutableListOf<SendChannel<List<RawLocationSample>>>()

    val activeSubscriptionCount: Int get() = channels.size

    fun emit(batch: List<RawLocationSample>) {
        channels.toList().forEach { it.trySend(batch) }
    }

    /** Closes every currently active collector with [exception], the same way [FusedTripLocationClient] closes its flow when an actual exception terminates an already-active registration. */
    fun failActiveCollection(exception: Throwable) {
        channels.toList().forEach { it.close(exception) }
    }

    override fun locationUpdates(): Flow<List<RawLocationSample>> = callbackFlow {
        if (registrationFailure != null) {
            close(registrationFailure)
            return@callbackFlow
        }
        val channel = this
        channels.add(channel)
        awaitClose { channels.remove(channel) }
    }

    /** Suspends forever when [neverCompletingFlush] is set, exercising a caller's own bounded timeout around this call; otherwise a normal no-op, like the production default. */
    override suspend fun flush() {
        if (neverCompletingFlush) awaitCancellation()
    }
}
