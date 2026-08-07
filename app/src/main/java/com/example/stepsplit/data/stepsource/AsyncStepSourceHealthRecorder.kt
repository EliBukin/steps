package com.example.stepsplit.data.stepsource

import android.util.Log
import com.example.stepsplit.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One already-fully-computed diagnostic write - carries its own timestamp/params exactly as they
 * were at the moment the real subscribe/read outcome happened, not recomputed later when the event
 * is actually processed. This is what makes event ORDER meaningful independent of when (or whether)
 * processing gets around to each one: [AsyncStepSourceHealthRecorder] only needs to apply these to
 * a [StepSourceHealthRecorder] delegate in the order they were enqueued, never needing to know
 * anything about "now" itself.
 */
private sealed interface HealthEvent {
    data class SubscriptionSuccess(val atEpochSecond: Long) : HealthEvent
    data class SubscriptionFailure(val failure: ApiFailure, val atEpochSecond: Long) : HealthEvent
    data class ReadAttempt(val atEpochSecond: Long, val windowStartEpochSecond: Long, val windowEndEpochSecond: Long) : HealthEvent
    data class ReadSuccess(val intervalCount: Int, val latestSampleEpochSecond: Long?, val atEpochSecond: Long) : HealthEvent
    data class ReadFailure(val failure: ApiFailure, val atEpochSecond: Long) : HealthEvent
}

/**
 * The non-blocking, order-preserving telemetry boundary between [LocalRecordingStepSource] and real
 * diagnostic persistence (typically [StepSourceHealthStore], which suspends on a Preferences
 * DataStore write). The only production implementation of [StepSourceHealthSink] - see that
 * interface's own doc comment for why [LocalRecordingStepSource] depends on that non-suspending
 * contract specifically, rather than on [StepSourceHealthRecorder] (this class's own `delegate`)
 * directly.
 *
 * This exists because a `recordSafely { ... }`-style try/catch alone - however complete - cannot
 * protect acquisition from a write that simply never *returns*: `try`/`catch` only ever intercepts
 * an exception, never a suspension that just never completes. If a caller awaits a recorder method
 * directly, a wedged DataStore write (disk contention, a stuck internal lock, anything) blocks that
 * caller right along with it - which is exactly the bug: a diagnostic side-channel able to gate
 * `gateway.subscribe()`/`gateway.readData()` themselves, or delay delivering their already-decided
 * real result back to [com.example.stepsplit.data.repository.StepRepository].
 *
 * The fix is structural, not another exception handler: every `recordX()` method here does exactly
 * one non-suspending thing - build a [HealthEvent] and [Channel.trySend] it. `trySend` **never
 * suspends**, regardless of whether [delegate] (or the single background consumer coroutine that
 * eventually calls it) is fast, slow, or wedged forever - so a caller invoking one of these methods
 * only ever pays for that instant, synchronous enqueue, never the real persistence behind it. The
 * methods aren't even `suspend` - see [StepSourceHealthSink] - so there is no suspension point here
 * at all for structured cancellation to interact with either way.
 *
 * A single consumer coroutine (launched once, in [scope]) drains [channel] strictly in FIFO order
 * and applies each event to [delegate] sequentially - deliberately NOT `scope.launch { delegate.recordX(...) }`
 * independently per call site, which would let concurrent writes race the underlying DataStore and
 * land in whatever order the dispatcher happened to schedule them, not the order they actually
 * happened in production. A [Channel] guarantees FIFO delivery for however many senders enqueue
 * into it; draining with exactly one consumer coroutine is what turns that into a guarantee about
 * the order [delegate] actually observes.
 *
 * Three failure modes are handled explicitly, none ever left to propagate or to stall the queue:
 * - **Enqueue failure** ([Channel.trySend] returning a failed result - e.g. [channelCapacity] is
 *   exceeded because the consumer has fallen far behind, or [channel] was already closed): logged
 *   and the event is dropped. A dropped diagnostic write is a debug-panel inconvenience, never a
 *   reason to fail (or slow down) the real acquisition call that triggered it.
 * - **Write failure** ([delegate]'s own suspend call throwing once the consumer gets to it): caught
 *   and logged per-event inside [processSafely] so one bad write can never crash the consumer loop
 *   or block every event queued behind it - the next event still gets its turn.
 * - **Write that never completes at all** (throws nothing, returns nothing - a genuinely wedged
 *   suspension inside [delegate], not merely a slow one): the whole point of this class is that this
 *   can never reach back to gate acquisition, but left unbounded it would still permanently stall
 *   *this processor's own consumer loop* - a real, separate problem, since the loop would never move
 *   on to any event enqueued after the wedged one, silently freezing every future diagnostic write
 *   even though acquisition itself stays healthy. [processSafely] therefore bounds every single
 *   event's processing in [withTimeout] at [eventTimeoutMillis]: a [TimeoutCancellationException]
 *   from that specific event is caught, logged, and dropped exactly like an ordinary write failure,
 *   and the loop moves on to drain whatever comes next.
 */
class AsyncStepSourceHealthRecorder(
    private val delegate: StepSourceHealthRecorder,
    scope: CoroutineScope,
    channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val eventTimeoutMillis: Long = DEFAULT_EVENT_TIMEOUT_MILLIS,
) : StepSourceHealthSink {

    private val channel = Channel<HealthEvent>(capacity = channelCapacity)

    init {
        scope.launch {
            for (event in channel) {
                processSafely(event)
            }
        }
    }

    private suspend fun processSafely(event: HealthEvent) {
        try {
            withTimeout(eventTimeoutMillis) {
                when (event) {
                    is HealthEvent.SubscriptionSuccess -> delegate.recordSubscriptionSuccess(event.atEpochSecond)
                    is HealthEvent.SubscriptionFailure -> delegate.recordSubscriptionFailure(event.failure, event.atEpochSecond)
                    is HealthEvent.ReadAttempt -> delegate.recordReadAttempt(event.atEpochSecond, event.windowStartEpochSecond, event.windowEndEpochSecond)
                    is HealthEvent.ReadSuccess -> delegate.recordReadSuccess(event.intervalCount, event.latestSampleEpochSecond, event.atEpochSecond)
                    is HealthEvent.ReadFailure -> delegate.recordReadFailure(event.failure, event.atEpochSecond)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A single wedged event, not a real external cancellation - see this class's own doc
            // comment. Must be caught ahead of the plain CancellationException clause below, since
            // TimeoutCancellationException is itself a CancellationException subtype and would
            // otherwise be caught (and rethrown, ending the loop) by that branch instead.
            debugLog { "diagnostic event write timed out after ${eventTimeoutMillis}ms (ignored): ${event::class.simpleName}" }
        } catch (e: CancellationException) {
            // The consumer coroutine's own scope was cancelled (process/app-lifecycle shutdown) -
            // let the loop end normally rather than logging this as an ordinary write failure.
            throw e
        } catch (e: Exception) {
            debugLog { "diagnostic event write failed (ignored): ${event::class.simpleName}: ${e.message}" }
        }
    }

    private fun enqueue(event: HealthEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            debugLog { "diagnostic event enqueue failed (ignored): ${event::class.simpleName}" }
        }
    }

    override fun recordSubscriptionSuccess(atEpochSecond: Long) =
        enqueue(HealthEvent.SubscriptionSuccess(atEpochSecond))

    override fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) =
        enqueue(HealthEvent.SubscriptionFailure(failure, atEpochSecond))

    override fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) =
        enqueue(HealthEvent.ReadAttempt(atEpochSecond, windowStartEpochSecond, windowEndEpochSecond))

    override fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) =
        enqueue(HealthEvent.ReadSuccess(intervalCount, latestSampleEpochSecond, atEpochSecond))

    override fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) =
        enqueue(HealthEvent.ReadFailure(failure, atEpochSecond))

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private companion object {
        const val TAG = "HealthEventProcessor"

        /**
         * Generous relative to actual traffic (at most a handful of events per sync, syncs are
         * minutes apart) - bounds worst-case memory if the consumer somehow falls far behind
         * without ever silently blocking a sender: [Channel.trySend] never suspends regardless of
         * capacity, so a full channel simply fails the enqueue (handled in [enqueue]) rather than
         * stalling the caller.
         */
        const val DEFAULT_CHANNEL_CAPACITY = 256

        /**
         * Generous relative to how long a healthy Preferences DataStore write actually takes
         * (milliseconds) - this only exists to eventually give up on a write that is genuinely
         * wedged, not to police ordinary slowness under real device I/O contention.
         */
        const val DEFAULT_EVENT_TIMEOUT_MILLIS = 5_000L
    }
}
