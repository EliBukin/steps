package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
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
 * Also simulates the two ways a real registration can fail (see [FusedTripLocationClient]'s doc
 * comment): [registrationFailure] mirrors the platform rejecting registration itself (the flow
 * fails as soon as something starts collecting it), and [failActiveCollection] mirrors a failure
 * arriving asynchronously after collection has already begun.
 */
class FakeTripLocationClient(
    private val registrationFailure: Throwable? = null,
) : TripLocationClient {
    private val channels = mutableListOf<SendChannel<List<RawLocationSample>>>()

    val activeSubscriptionCount: Int get() = channels.size

    fun emit(batch: List<RawLocationSample>) {
        channels.toList().forEach { it.trySend(batch) }
    }

    /** Closes every currently active collector with [exception], the same way [FusedTripLocationClient] closes its flow when the platform rejects/terminates an already-active registration. */
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
}
