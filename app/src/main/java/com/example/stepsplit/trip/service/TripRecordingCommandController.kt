package com.example.stepsplit.trip.service

import com.example.stepsplit.data.trip.TripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the actual command-routing logic behind [TripRecordingService] - deliberately a plain Kotlin
 * class (not the [android.app.Service] itself) so every race this class exists to prevent is
 * directly, deterministically unit-testable with fakes, no Robolectric service lifecycle involved.
 * [TripRecordingService] is a thin shell: it owns only Android-specific mechanics (foreground
 * promotion, the notification, `stopSelfResult`) and forwards every command here.
 *
 * ## Generation safety
 *
 * [beginCommand] must be called synchronously from `onStartCommand`, before any suspend work is
 * dispatched, and its result ("this command's generation") threaded into whichever `handleXxx` call
 * follows. Cancelling the previous command's coroutine (which the service still does) is not enough
 * on its own: cancellation is cooperative and only takes effect at a suspension point, so an older
 * command that has already returned from its *last* suspend call keeps running its remaining,
 * purely synchronous code to completion even after being cancelled.
 *
 * Every `handleXxx` function therefore checks [isCurrent] as its very first action, *before*
 * touching [repository] at all - a stale command does nothing further, full stop, rather than
 * relying on a check only at the end (right before teardown) to catch a mutation that already
 * happened. [handleFinish] checks a *second* time, immediately before its own
 * [TripRepository.finishTrip] call, because time (the bounded flush + grace wait) passes between
 * its own initial check and that mutation, during which a newer command can still arrive.
 *
 * A currency check alone is not sufficient for the actual collector/service side effects, though:
 * [gate] (a [CommandGenerationGate]) makes the *final* currency check and the synchronous act
 * (starting/stopping the coordinator, requesting service teardown) one atomic operation - see that
 * class's own doc comment for the two-step race this closes. [startCollecting] and [stopIfCurrent]
 * are the only two places that ever touch [coordinator] or call [onStopRequested], and both go
 * through [gate].
 *
 * [handleRecordingFailure] is *not* a generation-independent exception anymore: a stale collector's
 * failure must never mutate a trip a newer generation has since taken back over (idempotently, via
 * `startTrip()`'s trip-reuse) - it is checked against [gate] exactly like every other handler, before
 * touching [repository] at all.
 */
class TripRecordingCommandController(
    private val repository: TripRepository,
    private val coordinator: TripRecordingCoordinator,
    private val locationClient: TripLocationClient,
    private val clock: Clock,
    private val onStopRequested: (startId: Int) -> Unit,
) {
    private val gate = CommandGenerationGate()

    /** Call synchronously, once per `onStartCommand` invocation, before launching any suspend work. */
    fun beginCommand(): Long = gate.begin()

    private fun isCurrent(generation: Long): Boolean = gate.isCurrent(generation)

    /** An explicit, user-initiated Start (or its idempotent redelivery) - may create a brand-new trip via [TripRepository.startTrip]. */
    suspend fun handleStart(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        val tripId = repository.startTrip()
        startCollecting(tripId, generation, startId)
    }

    /**
     * An explicit, user-initiated Resume of a specific [tripId] - the UI has already re-validated
     * precise-location permission and system location being enabled before sending this (see
     * `TripsScreen.kt`), so by the time this runs the only remaining failure modes are a stale/
     * duplicate command or a genuine registration failure, both handled below. Never calls
     * [TripRepository.startTrip] - only [TripRepository.resumeInterruptedTrip], which atomically
     * verifies [tripId] is still INTERRUPTED before transitioning it, so this can never resume a
     * trip twice or resurrect one that has since finished elsewhere.
     */
    suspend fun handleResume(tripId: Long, generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        if (tripId < 0 || !repository.resumeInterruptedTrip(tripId)) {
            stopIfCurrent(generation, startId)
            return
        }
        startCollecting(tripId, generation, startId)
    }

    /**
     * Android redelivering a **null** Intent to restart this `START_STICKY` service after process
     * death. May only *recover* an already-ACTIVE trip ([TripRepository.getActiveTripId]) - never
     * calls [TripRepository.startTrip] or [TripRepository.resumeInterruptedTrip]. If no trip is
     * ACTIVE (e.g. the app's own launch-time reconciliation already marked it INTERRUPTED before
     * this delayed restart arrived), this stops the service without creating or changing anything.
     */
    suspend fun handleRestart(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        val tripId = repository.getActiveTripId()
        if (tripId == null) {
            stopIfCurrent(generation, startId)
            return
        }
        startCollecting(tripId, generation, startId)
    }

    /**
     * Bounded end-to-end: [FLUSH_TIMEOUT_MILLIS] caps the flush itself (a Play Services `Task` that
     * never completes must not hang Finish forever - see [TripLocationClient.flush]'s doc comment),
     * then [FINISH_GRACE_PERIOD_MILLIS] gives any already-batched fixes delivered by that flush a
     * short, separately-bounded window to actually arrive through the still-active collector before
     * the trip is marked finished. [cutoffEpochSecond] is captured *once*, before either wait begins,
     * and used for two things that must agree: it is handed to [TripRepository.beginFinish] so
     * [TripRepository.recordAcceptedBatch] rejects a live fix newly *captured* during the wait (not
     * merely delivered late), and it is the exact value persisted as `endEpochSecond` via
     * [TripRepository.finishTrip] - never `clock.instant()` taken again after the wait, which would
     * silently pad the trip's duration by however long the wait actually took.
     *
     * The cutoff is owned by this call's own [generation] (see [TripRepository.beginFinish]/
     * [TripRepository.cancelFinish]): the `finally` block below releases it unconditionally, from a
     * [NonCancellable] context so it still runs even if this coroutine is itself being cancelled by a
     * newer command superseding it (exactly what the service does - see the class doc comment on
     * cancellation not being sufficient on its own). This guarantees an abandoned Finish can never
     * leave a permanent, unowned cutoff behind that would reject every future point for this trip id
     * forever - only ever its own cutoff, and only for as long as it is genuinely still in flight.
     *
     * [repository.getActiveTripId] resolves *which* trip to finish rather than taking one as a
     * parameter (see that function's own doc comment) - which means a delayed/stale Finish could
     * otherwise resolve to a completely different, newer trip that has become ACTIVE since this
     * command was dispatched. [isCurrent] is checked *twice*: once up front (skips resolving/
     * touching anything at all if already stale when this runs) and again immediately before the
     * actual [TripRepository.finishTrip] mutation, since a newer command can just as easily arrive
     * *during* the flush/grace wait.
     */
    suspend fun handleFinish(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        val tripId = repository.getActiveTripId()
        if (tripId == null) {
            stopIfCurrent(generation, startId)
            return
        }
        val cutoffEpochSecond = clock.instant().epochSecond
        repository.beginFinish(tripId, generation, cutoffEpochSecond)
        try {
            withTimeoutOrNull(FLUSH_TIMEOUT_MILLIS) {
                try {
                    locationClient.flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort only, same contract as flush() itself - a flush failure must never block Finish.
                }
            }
            delay(FINISH_GRACE_PERIOD_MILLIS)
            if (isCurrent(generation)) {
                repository.finishTrip(tripId, cutoffEpochSecond)
            }
        } finally {
            // NonCancellable: this cleanup must run to completion even when this coroutine is being
            // cancelled right now by a newer command - see the doc comment above.
            withContext(NonCancellable) { repository.cancelFinish(generation) }
        }
        stopIfCurrent(generation, startId)
    }

    /**
     * Foreground promotion (see `TripRecordingService.promoteToForeground`) happens before any
     * command-specific work, so a promotion failure means [generation]'s own command never actually
     * ran - no collector was ever started on its behalf, so there is nothing of *its own* to stop.
     * But an already-ACTIVE trip left over from an earlier command has, as of this failure, lost its
     * live recording: this service is about to stop, and nothing is collecting for it anymore.
     * Rather than leave Room (and an already-open UI) claiming that trip is still ACTIVE with nothing
     * behind it, it is honestly reconciled to INTERRUPTED here immediately - the same terminal state
     * [TripRepository.reconcileActiveTripOnLaunch] would eventually reach on the next app launch,
     * just applied right away instead of waiting for that. Never creates or resumes a trip, and never
     * fabricates a successful Finish - only [TripRepository.markTripInterrupted], the same honest
     * state a live recording failure uses.
     */
    suspend fun handleForegroundPromotionFailure(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        val tripId = repository.getActiveTripId()
        if (tripId != null) {
            repository.markTripInterrupted(tripId)
        }
        stopIfCurrent(generation, startId)
    }

    private suspend fun startCollecting(tripId: Long, generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        // Any Finish cutoff still outstanding for this exact trip at this point cannot belong to a
        // still-current command (see clearAbandonedFinishCutoff's own doc comment) - clearing it here
        // closes the transient window between a cancelled Finish's own eventual cleanup and this
        // fresh collector's very first accepted point.
        repository.clearAbandonedFinishCutoff(tripId)
        gate.runIfCurrent(generation) {
            // Guarantee a clean subscription for `tripId` regardless of what an about-to-be-superseded
            // older command may still have running - coordinator.stop() is idempotent either way.
            coordinator.stop()
            coordinator.start(tripId) { throwable -> handleRecordingFailure(tripId, generation, startId, throwable) }
        }
    }

    private suspend fun handleRecordingFailure(tripId: Long, generation: Long, startId: Int, @Suppress("UNUSED_PARAMETER") throwable: Throwable) {
        // A stale collector's failure must never mutate a trip a newer generation has since taken
        // back over (idempotently, via startTrip()'s trip reuse) - see the class doc comment.
        if (!isCurrent(generation)) return
        repository.markTripInterrupted(tripId)
        stopIfCurrent(generation, startId)
    }

    private fun stopIfCurrent(generation: Long, startId: Int) {
        gate.runIfCurrent(generation) {
            coordinator.stop()
            onStopRequested(startId)
        }
    }

    companion object {
        /** Documented, bounded cap on the flush Task itself - see [handleFinish]'s doc comment. */
        const val FLUSH_TIMEOUT_MILLIS = 3_000L
        const val FINISH_GRACE_PERIOD_MILLIS = 2_000L
    }
}
