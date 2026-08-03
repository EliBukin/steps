package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Deterministic [TripLocationClient] for tests. [emit] pushes a batch to every currently active
 * collector - there is deliberately no buffering/replay, mirroring a live GPS feed where a fix
 * missed while nothing was collecting is simply gone. [activeSubscriptionCount] proves collection
 * only happens while something is actually gathering updates, used to assert no location request
 * occurs while idle.
 */
class FakeTripLocationClient : TripLocationClient {
    private val listeners = mutableListOf<(List<RawLocationSample>) -> Unit>()

    val activeSubscriptionCount: Int get() = listeners.size

    fun emit(batch: List<RawLocationSample>) {
        listeners.toList().forEach { it(batch) }
    }

    override fun locationUpdates(): Flow<List<RawLocationSample>> = callbackFlow {
        val listener: (List<RawLocationSample>) -> Unit = { batch -> trySend(batch) }
        listeners.add(listener)
        awaitClose { listeners.remove(listener) }
    }
}
