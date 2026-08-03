package com.example.stepsplit.trip.service

import com.example.stepsplit.data.trip.TripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
 * The one deliberate exception is [handleRecordingFailure]: a stale generation's recording failure
 * still honestly marks its own specific trip [TripRepository.markTripInterrupted] regardless of
 * currency - that mutation is scoped to a trip id this exact collector owned, is itself idempotent/
 * self-guarding, and is correct no matter what a newer command is doing elsewhere. Only the
 * resulting coordinator/service teardown is generation-guarded there.
 */
class TripRecordingCommandController(
    private val repository: TripRepository,
    private val coordinator: TripRecordingCoordinator,
    private val locationClient: TripLocationClient,
    private val clock: Clock,
    private val onStopRequested: (startId: Int) -> Unit,
) {
    @Volatile private var latestGeneration = 0L
    private val generationLock = Any()

    /** Call synchronously, once per `onStartCommand` invocation, before launching any suspend work. */
    fun beginCommand(): Long = synchronized(generationLock) {
        latestGeneration += 1
        latestGeneration
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(generationLock) { generation == latestGeneration }

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
     * the trip is marked finished. [TripRepository.markFinishRequested] captures the cutoff instant
     * *before* either wait begins, so a live fix newly captured during that wait - not merely
     * delivered late - is never appended after the user already asked to stop.
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
        if (tripId != null) {
            repository.markFinishRequested(tripId, clock.instant().epochSecond)
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
            if (!isCurrent(generation)) return
            repository.finishTrip(tripId)
        }
        stopIfCurrent(generation, startId)
    }

    private fun startCollecting(tripId: Long, generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        // Guarantee a clean subscription for `tripId` regardless of what an about-to-be-superseded
        // older command may still have running - coordinator.stop() is idempotent either way.
        coordinator.stop()
        coordinator.start(tripId) { throwable -> handleRecordingFailure(tripId, generation, startId, throwable) }
    }

    private suspend fun handleRecordingFailure(tripId: Long, generation: Long, startId: Int, @Suppress("UNUSED_PARAMETER") throwable: Throwable) {
        // Always honest about this specific trip, regardless of generation staleness - see the class doc comment.
        repository.markTripInterrupted(tripId)
        stopIfCurrent(generation, startId)
    }

    private fun stopIfCurrent(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        coordinator.stop()
        onStopRequested(startId)
    }

    companion object {
        /** Documented, bounded cap on the flush Task itself - see [handleFinish]'s doc comment. */
        const val FLUSH_TIMEOUT_MILLIS = 3_000L
        const val FINISH_GRACE_PERIOD_MILLIS = 2_000L
    }
}
