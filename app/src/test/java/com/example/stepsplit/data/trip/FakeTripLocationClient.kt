package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
 * Every collection gets an id, counting up from 1 ([latestSubscriptionId], [activeSubscriptionIds]),
 * so a test can tell the collection a restart started from the one it replaced. The coordinator
 * subscribes and unsubscribes on its own background scope, so right after a restart the old
 * collection, both, or neither may be active for a moment; a plain "one is active" check can pass on
 * the old collection just before it goes. Collections come and go on those background threads while
 * a test reads them from its own, so they are kept in a thread-safe list.
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
    private class Subscription(val id: Int, val channel: SendChannel<List<RawLocationSample>>)

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val lastSubscriptionId = AtomicInteger(0)

    val activeSubscriptionCount: Int get() = subscriptions.size

    /** The ids of the collections active right now. */
    val activeSubscriptionIds: List<Int> get() = subscriptions.map { it.id }

    /** The id of the newest collection started so far; 0 before the first. */
    val latestSubscriptionId: Int get() = lastSubscriptionId.get()

    /** Set synchronously as the very first action of [flush], *before* [neverCompletingFlush] can suspend it - lets a test deterministically await "flush has been entered" without a sleep. */
    @Volatile var flushInvoked: Boolean = false
        private set

    /** What [currentLocation] answers - a test can make it wait, or never answer, to drive [TripSpotMarker]'s timing. */
    @Volatile var currentLocationAnswer: suspend () -> RawLocationSample? = { null }

    /** How many times [currentLocation] was asked. */
    @Volatile var currentLocationRequests: Int = 0
        private set

    override suspend fun currentLocation(): RawLocationSample? {
        currentLocationRequests++
        return currentLocationAnswer()
    }

    fun emit(batch: List<RawLocationSample>) {
        subscriptions.forEach { it.channel.trySend(batch) }
    }

    /** Closes every currently active collector with [exception], the same way [FusedTripLocationClient] closes its flow when an actual exception terminates an already-active registration. */
    fun failActiveCollection(exception: Throwable) {
        subscriptions.forEach { it.channel.close(exception) }
    }

    override fun locationUpdates(): Flow<List<RawLocationSample>> = callbackFlow {
        if (registrationFailure != null) {
            close(registrationFailure)
            return@callbackFlow
        }
        val subscription = Subscription(lastSubscriptionId.incrementAndGet(), this)
        subscriptions.add(subscription)
        awaitClose { subscriptions.remove(subscription) }
    }

    /** Suspends forever when [neverCompletingFlush] is set, exercising a caller's own bounded timeout around this call; otherwise a normal no-op, like the production default. */
    override suspend fun flush() {
        flushInvoked = true
        if (neverCompletingFlush) awaitCancellation()
    }
}
