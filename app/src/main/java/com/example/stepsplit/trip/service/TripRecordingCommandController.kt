package com.example.stepsplit.trip.service

import com.example.stepsplit.data.trip.TripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRecordingRepository
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Every `handleXxx` function checks [isCurrent] as its very first action, *before* touching
 * [repository] at all - a stale command exits immediately rather than doing pointless work. This is
 * an optimization only, though, not the safety mechanism: any repository mutation whose correctness
 * actually depends on this command still being "the" current one - as opposed to a mutation that is
 * safe unconditionally, like the idempotent [TripRecordingRepository.startTrip] - goes through an
 * atomic, token-checked repository operation instead ([TripRecordingRepository.beginRecording],
 * [TripRecordingRepository.markTripInterruptedIfStillOwned], [TripRecordingRepository
 * .finishTripIfOwner]), so ownership is validated at the exact moment of the mutation, never merely
 * before the suspending call that leads to it. A caller-side [isCurrent] check followed by an
 * unguarded suspending mutation has a real gap: arbitrary time - a dispatcher hop, Room's own
 * background executor, a bounded wait - can pass between the check and the mutation actually
 * committing, during which a newer command can be accepted and even complete its own conflicting
 * mutation first.
 *
 * A currency check alone is also not sufficient for the actual collector/service side effects:
 * [gate] (a [CommandGenerationGate]) makes the *final* currency check and the synchronous act
 * (starting/stopping the coordinator, requesting service teardown) one atomic operation - see that
 * class's own doc comment for the two-step race this closes. [startCollecting] and [stopIfCurrent]
 * are the only two places that ever touch [coordinator] or call [onStopRequested], and both go
 * through [gate]. [gate] also carries this controller's terminal shutdown state - see [shutdown].
 */
class TripRecordingCommandController(
    private val repository: TripRecordingRepository,
    private val coordinator: TripRecordingCoordinator,
    private val locationClient: TripLocationClient,
    private val clock: Clock,
    private val onStopRequested: (startId: Int) -> Unit,
    private val reconciliationScope: CoroutineScope,
) {
    private val gate = CommandGenerationGate()

    /** Call synchronously, once per `onStartCommand` invocation, before launching any suspend work. */
    fun beginCommand(): Long = gate.begin()

    private fun isCurrent(generation: Long): Boolean = gate.isCurrent(generation)

    /** An explicit, user-initiated Start (or its idempotent redelivery) - may create a brand-new trip via [TripRecordingRepository.startTrip]. */
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
     * [TripRecordingRepository.startTrip] - only [TripRecordingRepository.resumeInterruptedTrip],
     * which atomically verifies [tripId] is still INTERRUPTED before transitioning it, so this can
     * never resume a trip twice or resurrect one that has since finished elsewhere.
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
     * death. May only *recover* an already-ACTIVE trip ([TripRecordingRepository.getActiveTripId]) -
     * never calls [TripRecordingRepository.startTrip] or [TripRecordingRepository
     * .resumeInterruptedTrip]. If no trip is ACTIVE (e.g. the app's own launch-time reconciliation
     * already marked it INTERRUPTED before this delayed restart arrived), this stops the service
     * without creating or changing anything.
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
     * and used for two things that must agree: it is handed to [TripRecordingRepository.beginFinish]
     * so `recordAcceptedBatch` rejects a live fix newly *captured* during the wait (not merely
     * delivered late), and it is the exact value persisted as `endEpochSecond` via
     * [TripRecordingRepository.finishTripIfOwner] - never `clock.instant()` taken again after the
     * wait, which would silently pad the trip's duration by however long the wait actually took.
     *
     * The cutoff is owned by this call's own [generation] (see [TripRecordingRepository.beginFinish]/
     * [TripRecordingRepository.cancelFinish]): the `finally` block below releases it unconditionally,
     * from a [NonCancellable] context so it still runs even if this coroutine is itself being
     * cancelled by a newer command superseding it (exactly what the service does - see the class doc
     * comment on cancellation not being sufficient on its own). This guarantees an abandoned Finish
     * can never leave a permanent, unowned cutoff behind that would reject every future point for
     * this trip id forever - only ever its own cutoff, and only for as long as it is genuinely still
     * in flight.
     *
     * [repository.getActiveTripId] resolves *which* trip to finish rather than taking one as a
     * parameter (see that function's own doc comment) - which means a delayed/stale Finish could
     * otherwise resolve to a completely different, newer trip that has become ACTIVE since this
     * command was dispatched. The actual [TripRecordingRepository.finishTripIfOwner] mutation, at the
     * end of the bounded wait, is gated by [generation] still owning the outstanding Finish cutoff
     * *at that exact instant*, atomically - not by an [isCurrent] check performed before the wait
     * (which could be stale by the time the wait actually finishes; see the class doc comment).
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
            repository.finishTripIfOwner(tripId, cutoffEpochSecond, generation)
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
     * ran - no collector was ever started on its behalf, so it has no ownership token of its own
     * registered via [TripRecordingRepository.beginRecording]. But an already-ACTIVE trip left over
     * from an earlier command has, as of this failure, lost its live recording: this service is
     * about to stop, and nothing is collecting for it anymore. Rather than leave Room (and an
     * already-open UI) claiming that trip is still ACTIVE with nothing behind it, it is honestly
     * reconciled to INTERRUPTED here immediately via [TripRecordingRepository
     * .markTripInterruptedIfStillOwned] - passing [generation] purely as a "no collector newer than
     * this has taken over since" threshold (see that method's own doc comment for why it accepts
     * both an exact owner and this no-newer-owner case) - the same terminal state
     * [com.example.stepsplit.data.trip.TripRepository.reconcileActiveTripOnLaunch] would eventually
     * reach on the next app launch, just applied right away instead of waiting for that. Never
     * creates or resumes a trip, and never fabricates a successful Finish - only ever this same
     * honest reconciliation a live recording failure also uses.
     */
    suspend fun handleForegroundPromotionFailure(generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        val tripId = repository.getActiveTripId()
        if (tripId != null) {
            repository.markTripInterruptedIfStillOwned(tripId, generation) { isCurrent(generation) }
        }
        stopIfCurrent(generation, startId)
    }

    private suspend fun startCollecting(tripId: Long, generation: Long, startId: Int) {
        if (!isCurrent(generation)) return
        // Registers this generation as the owner of tripId's live recording, and atomically
        // supersedes an older, abandoned Finish cutoff still outstanding for it - see
        // beginRecording's own doc comment. A monotonic compare-and-set, so a stale call reaching
        // this point after a genuinely newer registration already ran is a safe no-op, never a
        // clobber - no isCurrent check is relied on here for that safety.
        repository.beginRecording(tripId, generation)
        gate.runIfCurrent(generation) {
            // Guarantee a clean subscription for `tripId` regardless of what an about-to-be-superseded
            // older command may still have running - coordinator.stop() is idempotent either way.
            coordinator.stop()
            coordinator.start(tripId) { throwable -> handleRecordingFailure(tripId, generation, startId, throwable) }
        }
    }

    /**
     * A stale collector's failure must never mutate a trip a newer generation has since taken back
     * over (idempotently, via `startTrip()`'s trip reuse) - see the class doc comment. Deliberately
     * *not* guarded by an upfront `if (!isCurrent(generation)) return`: that pattern - check, then
     * make a separate suspending call - leaves a real gap between the check and the mutation actually
     * committing. Currency is instead threaded into [TripRecordingRepository
     * .markTripInterruptedIfStillOwned] itself as a lambda, evaluated atomically at the exact mutation
     * point (inside that call's own lock, immediately before it touches Room) rather than before the
     * call - see that method's own doc comment for why both that currency check and its token-based
     * ownership comparison are necessary, and why neither alone is sufficient. [stopIfCurrent]
     * independently re-validates via [gate] before touching the coordinator or service, too - so this
     * whole function stays correct even if this callback was paused for an arbitrary length of time
     * between the failure occurring and this function actually running.
     */
    private suspend fun handleRecordingFailure(tripId: Long, generation: Long, startId: Int, @Suppress("UNUSED_PARAMETER") throwable: Throwable) {
        repository.markTripInterruptedIfStillOwned(tripId, generation) { isCurrent(generation) }
        stopIfCurrent(generation, startId)
    }

    private fun stopIfCurrent(generation: Long, startId: Int) {
        gate.runIfCurrent(generation) {
            coordinator.stop()
            onStopRequested(startId)
        }
    }

    /**
     * Terminal, idempotent service-destruction hook - call synchronously and exactly once from
     * `TripRecordingService.onDestroy`, never from a suspend context. Atomically (see [gate])
     * permanently closes [gate] - so every existing or future command generation's attempt to start,
     * stop, or replace a collector, or invoke [onStopRequested], is rejected from this instant on,
     * even one already genuinely in flight (shutdown simply waits for it to finish first, then
     * performs the final stop, exactly as [CommandGenerationGate.shutdown] documents) - and stops
     * whatever collector is currently running as the same atomic step.
     *
     * The trip-reconciliation half is necessarily suspending (it is a Room mutation), so it cannot be
     * part of that synchronous step without blocking the caller - the Android main thread, for the
     * real `onDestroy`. Instead it is launched fire-and-forget on [reconciliationScope], a scope that
     * outlives this service instance (`serviceScope`, which the real service cancels right after this
     * call, would not). It uses the generation that was current at the moment [gate] closed as an
     * ownership threshold: [TripRecordingRepository.markTripInterruptedIfStillOwned] only interrupts
     * the trip if no *newer* recording registration exists, so a different, genuinely newer
     * controller/service instance that has already taken over the same (idempotently reused) ACTIVE
     * trip by the time this reconciliation actually runs is left completely untouched - only a trip
     * this instance's own shutdown genuinely left with no collector behind it is reconciled.
     */
    fun shutdown() {
        val closedAtGeneration = gate.shutdown { coordinator.stop() }
        reconciliationScope.launch {
            val tripId = repository.getActiveTripId() ?: return@launch
            // Gate currency is meaningless here - it is already permanently closed by definition at
            // this point - so this relies purely on the token comparison (b) inside
            // markTripInterruptedIfStillOwned to protect a genuinely newer controller/service
            // instance that has since taken over the same trip.
            repository.markTripInterruptedIfStillOwned(tripId, closedAtGeneration) { true }
        }
    }

    companion object {
        /** Documented, bounded cap on the flush Task itself - see [handleFinish]'s doc comment. */
        const val FLUSH_TIMEOUT_MILLIS = 3_000L
        const val FINISH_GRACE_PERIOD_MILLIS = 2_000L
    }
}
