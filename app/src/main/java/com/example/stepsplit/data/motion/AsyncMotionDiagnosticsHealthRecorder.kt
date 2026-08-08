package com.example.stepsplit.data.motion

import android.util.Log
import com.example.stepsplit.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private sealed interface DiagnosticsEvent {
    data class TransitionSuccess(val atEpochSecond: Long) : DiagnosticsEvent
    data class TransitionFailure(val failure: MotionRegistrationFailure, val atEpochSecond: Long) : DiagnosticsEvent
    data class SamplingSuccess(val atEpochSecond: Long) : DiagnosticsEvent
    data class SamplingFailure(val failure: MotionRegistrationFailure, val atEpochSecond: Long) : DiagnosticsEvent
    data class SuccessfulValidation(val atEpochSecond: Long) : DiagnosticsEvent
}

/**
 * The non-blocking, order-preserving telemetry boundary between motion-evidence acquisition
 * (registration, ingestion) and real diagnostic persistence - a direct structural copy of
 * [com.example.stepsplit.data.stepsource.AsyncStepSourceHealthRecorder], which has the full
 * reasoning for every design choice here (why a plain non-`suspend` [MotionDiagnosticsHealthSink] is
 * the actual safeguard, why a single consumer coroutine preserves event order, and why every event
 * is bounded by [eventTimeoutMillis] so one wedged write can never permanently stall every
 * diagnostic write queued behind it). This satisfies the explicit requirement that "diagnostic
 * recording must never suspend, block, or stop step acquisition."
 */
class AsyncMotionDiagnosticsHealthRecorder(
    private val delegate: MotionDiagnosticsHealthRecorder,
    scope: CoroutineScope,
    channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val eventTimeoutMillis: Long = DEFAULT_EVENT_TIMEOUT_MILLIS,
) : MotionDiagnosticsHealthSink {

    private val channel = Channel<DiagnosticsEvent>(capacity = channelCapacity)

    init {
        scope.launch {
            for (event in channel) processSafely(event)
        }
    }

    private suspend fun processSafely(event: DiagnosticsEvent) {
        try {
            withTimeout(eventTimeoutMillis) {
                when (event) {
                    is DiagnosticsEvent.TransitionSuccess -> delegate.recordTransitionRegistrationSuccess(event.atEpochSecond)
                    is DiagnosticsEvent.TransitionFailure -> delegate.recordTransitionRegistrationFailure(event.failure, event.atEpochSecond)
                    is DiagnosticsEvent.SamplingSuccess -> delegate.recordSamplingRegistrationSuccess(event.atEpochSecond)
                    is DiagnosticsEvent.SamplingFailure -> delegate.recordSamplingRegistrationFailure(event.failure, event.atEpochSecond)
                    is DiagnosticsEvent.SuccessfulValidation -> delegate.recordSuccessfulValidation(event.atEpochSecond)
                }
            }
        } catch (e: TimeoutCancellationException) {
            debugLog { "diagnostic event write timed out after ${eventTimeoutMillis}ms (ignored): ${event::class.simpleName}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog { "diagnostic event write failed (ignored): ${event::class.simpleName}: ${e.message}" }
        }
    }

    private fun enqueue(event: DiagnosticsEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) debugLog { "diagnostic event enqueue failed (ignored): ${event::class.simpleName}" }
    }

    override fun recordTransitionRegistrationSuccess(atEpochSecond: Long) = enqueue(DiagnosticsEvent.TransitionSuccess(atEpochSecond))
    override fun recordTransitionRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) =
        enqueue(DiagnosticsEvent.TransitionFailure(failure, atEpochSecond))
    override fun recordSamplingRegistrationSuccess(atEpochSecond: Long) = enqueue(DiagnosticsEvent.SamplingSuccess(atEpochSecond))
    override fun recordSamplingRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) =
        enqueue(DiagnosticsEvent.SamplingFailure(failure, atEpochSecond))
    override fun recordSuccessfulValidation(atEpochSecond: Long) = enqueue(DiagnosticsEvent.SuccessfulValidation(atEpochSecond))

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    private companion object {
        const val TAG = "MotionDiagnosticsProcessor"
        const val DEFAULT_CHANNEL_CAPACITY = 256
        const val DEFAULT_EVENT_TIMEOUT_MILLIS = 5_000L
    }
}
