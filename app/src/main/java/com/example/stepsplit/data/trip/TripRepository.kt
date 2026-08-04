package com.example.stepsplit.data.trip

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.RouteSampleDecision
import com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every read/write path over manually recorded GPS trips - entirely separate from
 * [com.example.stepsplit.data.repository.StepRepository]: a trip never inserts or edits
 * `walk_bouts`, creates a session override, or changes daily step totals, and never reads
 * `step_buckets` either. This MVP only persists a trip's own timestamps/route/distance; a
 * read-only association with step data is left for a future version.
 *
 * A single [tripMutex] serializes start/finish/point-recording/recovery the same way
 * [com.example.stepsplit.data.repository.StepRepository.syncMutex] does for step sync, so a
 * location callback racing a Finish tap (or a duplicate service start) can never interleave writes.
 */
class TripRepository(
    private val database: StepSplitDatabase,
    private val clock: Clock,
) {
    private val tripMutex = Mutex()

    // In-memory only, deliberately not a Room column (no new schema for this MVP revision): at most
    // one trip is ever mid-Finish at a time, and [token] (an opaque, caller-chosen id - in practice
    // TripRecordingCommandController's own command generation) is what makes this cutoff owned rather
    // than a shared, freely-overwritable field - see beginFinish/cancelFinish's own doc comments for
    // why that ownership matters. Finishing is a short-lived, single-process operation; if the
    // process dies mid-finish the trip is simply recovered like any other mid-recording death, via
    // the ordinary reconcileActiveTripOnLaunch -> INTERRUPTED path.
    private data class FinishCutoff(val tripId: Long, val token: Long, val cutoffEpochSecond: Long)

    private var finishCutoff: FinishCutoff? = null

    /**
     * Idempotent: if a trip is already ACTIVE, its existing id is returned and no new row is
     * created - this is what makes a duplicate Start command (a double-tap, or the OS redelivering
     * a start command) safe, and is exactly the same path an OS-triggered service restart after
     * process death uses to recover the existing trip rather than creating a duplicate.
     */
    suspend fun startTrip(): Long = tripMutex.withLock {
        database.tripDao().getByState(TripState.ACTIVE.name)?.let { return@withLock it.id }
        val now = clock.instant()
        val trip = TripEntity(
            startEpochSecond = now.epochSecond,
            endEpochSecond = null,
            startZoneId = clock.zone.id,
            state = TripState.ACTIVE.name,
            distanceMeters = 0.0,
            lastAcceptedPointEpochSecond = null,
            createdAtEpochSecond = now.epochSecond,
        )
        database.tripDao().insert(trip)
    }

    /**
     * Transitions [tripId] to FINISHED at exactly [endEpochSecond] - the *same* instant the caller
     * captured as its Finish cutoff (see [beginFinish]), not whenever this call happens to run. A
     * bounded flush plus grace period (see
     * [com.example.stepsplit.trip.service.TripRecordingCommandController.handleFinish]) can add
     * several real seconds between "the user asked to stop" and this call actually executing; using
     * `clock.instant()` here instead would silently pad the stored trip duration by that same amount,
     * even though [recordAcceptedBatch] is simultaneously rejecting any point captured after the
     * cutoff - the persisted duration must not claim more than the accepted points can back up.
     * [endEpochSecond] is defensively clamped to never precede the trip's own start time. Idempotent:
     * a trip that is not currently ACTIVE (already finished, interrupted, or unknown) is left
     * untouched. Does not itself touch [finishCutoff] - see [cancelFinish].
     */
    suspend fun finishTrip(tripId: Long, endEpochSecond: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.ACTIVE.name) return@withLock
        val safeEnd = maxOf(endEpochSecond, trip.startEpochSecond)
        database.tripDao().update(trip.copy(state = TripState.FINISHED.name, endEpochSecond = safeEnd))
    }

    /**
     * Begins a Finish for [tripId], owned by [token] (an opaque caller-chosen id -
     * [com.example.stepsplit.trip.service.TripRecordingCommandController] passes its own command
     * generation), capturing [cutoffEpochSecond] once, *before* the service's bounded flush waits for
     * any already-batched fixes to arrive. [recordAcceptedBatch] then rejects any sample *captured*
     * after [cutoffEpochSecond] even though the trip is technically still ACTIVE during that wait - a
     * live fix the GPS chip happens to produce while the service is merely waiting out the flush
     * window must not be silently appended after the user already asked to stop. Fixes captured
     * *before* the cutoff but only *delivered* during the wait (exactly what flush() is for) are
     * unaffected. Unconditionally overwrites any previous cutoff: a fresh Finish always owns the
     * cutoff going forward, regardless of what an older, already-abandoned one left behind.
     */
    suspend fun beginFinish(tripId: Long, token: Long, cutoffEpochSecond: Long) = tripMutex.withLock {
        finishCutoff = FinishCutoff(tripId, token, cutoffEpochSecond)
    }

    /**
     * Releases [token]'s ownership of the Finish cutoff it began via [beginFinish], if it still owns
     * one - a no-op otherwise. This is the *cancellation-safe* half of the begin/cancel pair: a
     * cancelled or superseded Finish (see `TripRecordingCommandController.handleFinish`'s `finally`
     * block, invoked from a `NonCancellable` context so it still runs even mid-cancellation) must
     * release its own cutoff so a sample genuinely captured after it - by whatever recording
     * eventually replaces it - is never rejected against a Finish that was actually abandoned.
     * Scoped strictly to [token]: an older, already-superseded Finish can therefore never clear a
     * newer one's still-active cutoff, even for the same trip id.
     */
    suspend fun cancelFinish(token: Long) = tripMutex.withLock {
        if (finishCutoff?.token == token) finishCutoff = null
    }

    /**
     * Clears any Finish cutoff outstanding for [tripId], regardless of which token owns it. Safe to
     * call **only** from a caller that has already confirmed, via its own generation/currency check,
     * that it is itself the single current command: since at most one generation is ever current at a
     * time, any cutoff still outstanding for this exact trip at that point cannot belong to the
     * caller (a command never holds a cutoff of its own before it has begun one) and must therefore
     * already be abandoned. [TripRecordingCommandController.startCollecting] uses this immediately
     * before (re)starting live collection for [tripId] - Start, Resume, and restart recovery all funnel
     * through it - so a stale cutoff left behind by a cancelled Finish cannot reject the very first
     * points the *new* collector accepts while [cancelFinish]'s own cancellation-triggered cleanup is
     * still in flight. This is deliberately narrower than an unscoped "clear everything" API: it only
     * ever touches the cutoff for the one trip id the caller is itself about to take ownership of.
     */
    suspend fun clearAbandonedFinishCutoff(tripId: Long) = tripMutex.withLock {
        if (finishCutoff?.tripId == tripId) finishCutoff = null
    }

    /**
     * Filters [samples] through [RoutePointAcceptancePolicy] (processed in chronological order
     * regardless of delivery order) and persists every accepted one, accumulating distance only
     * between consecutive *accepted* points - the first accepted point of a trip contributes zero
     * distance. Each point's insert and the trip's updated distance/timestamp commit together in
     * one transaction, so a crash between them can never leave the two inconsistent.
     *
     * Re-reads the trip's own state before processing: a batch delivered after [finishTrip] has
     * already run (a stale/delayed callback) finds the trip no longer ACTIVE and is dropped
     * entirely - it can never append a point or increase distance after Finish.
     *
     * Two durable backstops run *before* a sample ever reaches [RoutePointAcceptancePolicy] (which
     * is intentionally trip-agnostic and knows nothing about a specific trip's start time or
     * Finish request - see that class's own doc comment):
     * - A sample captured before [com.example.stepsplit.data.local.trip.TripEntity.startEpochSecond]
     *   is rejected outright. Fused Location can deliver a cached, pre-Start fix immediately after
     *   registration; without this, such a fix could become the trip's very first "accepted" point.
     * - A sample captured unreasonably far in the *future* relative to now ([MAX_FUTURE_SKEW_SECONDS])
     *   is rejected outright, so a single bogus/corrupt timestamp can never become [lastAccepted] and
     *   make every subsequent genuine fix look non-monotonic (and therefore rejected) forever after.
     */
    suspend fun recordAcceptedBatch(tripId: Long, samples: List<RawLocationSample>) {
        if (samples.isEmpty()) return
        tripMutex.withLock {
            val trip = database.tripDao().getById(tripId) ?: return@withLock
            if (trip.state != TripState.ACTIVE.name) return@withLock

            var lastAccepted = database.tripPointDao().getLastPoint(tripId)?.toSample()
            var distance = trip.distanceMeters
            var latestAcceptedEpochSecond = trip.lastAcceptedPointEpochSecond
            val nowEpochSecond = clock.instant().epochSecond
            val cutoffEpochSecond = finishCutoff?.takeIf { it.tripId == tripId }?.cutoffEpochSecond

            for (sample in samples.sortedBy { it.capturedAtEpochSecond }) {
                if (sample.capturedAtEpochSecond < trip.startEpochSecond) continue
                if (sample.capturedAtEpochSecond > nowEpochSecond + MAX_FUTURE_SKEW_SECONDS) continue
                if (cutoffEpochSecond != null && sample.capturedAtEpochSecond > cutoffEpochSecond) continue

                val decision = RoutePointAcceptancePolicy.evaluate(sample, lastAccepted, nowEpochSecond)
                val accepted = (decision as? RouteSampleDecision.Accepted)?.sample ?: continue

                val segmentMeters = lastAccepted?.let {
                    RouteMath.haversineMeters(it.latitude, it.longitude, accepted.latitude, accepted.longitude)
                } ?: 0.0
                distance += segmentMeters
                latestAcceptedEpochSecond = accepted.capturedAtEpochSecond

                database.withTransaction {
                    database.tripPointDao().insert(accepted.toEntity(tripId))
                    database.tripDao().update(
                        trip.copy(distanceMeters = distance, lastAcceptedPointEpochSecond = latestAcceptedEpochSecond),
                    )
                }
                lastAccepted = accepted
            }
        }
    }

    /**
     * Called once per process start (see [com.example.stepsplit.ui.trips.TripsViewModel]) to
     * honestly reconcile an ACTIVE trip whose recording service cannot be confirmed running in
     * *this* process - most plausibly because the app was force-stopped, or Android chose not to
     * restart the service. [isServiceRunning] is evaluated by the caller (only it knows the
     * service's actual live state) immediately before this call; if true, the trip is left exactly
     * as it is - still genuinely recording. If false, the trip is marked [TripState.INTERRUPTED]
     * rather than silently left ACTIVE (which would let the UI keep claiming to record with nothing
     * behind it) or silently finished (which would fabricate an end time nobody actually observed).
     * The user resolves it explicitly via [resumeInterruptedTrip] or [finishInterruptedTripAtLastPoint].
     */
    suspend fun reconcileActiveTripOnLaunch(isServiceRunning: Boolean) = tripMutex.withLock {
        if (isServiceRunning) return@withLock
        val trip = database.tripDao().getByState(TripState.ACTIVE.name) ?: return@withLock
        database.tripDao().update(trip.copy(state = TripState.INTERRUPTED.name))
    }

    /**
     * Transitions [tripId] from ACTIVE to INTERRUPTED - the same terminal state
     * [reconcileActiveTripOnLaunch] uses, but triggered by a live recording failure (see
     * [TripRecordingCoordinator]'s `onFailure` callback) rather than an app-launch check. A no-op
     * if the trip is not currently ACTIVE (already finished/interrupted, or a stale/duplicate
     * failure callback), so this is safe to call more than once for the same failure.
     */
    suspend fun markTripInterrupted(tripId: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.ACTIVE.name) return@withLock
        database.tripDao().update(trip.copy(state = TripState.INTERRUPTED.name))
    }

    /**
     * The atomic core of resuming an [TripState.INTERRUPTED] trip, accepting the visible gap:
     * verifies [tripId] is still INTERRUPTED and transitions it to ACTIVE in one locked step, so a
     * stale/duplicate Resume command (see
     * [com.example.stepsplit.trip.service.TripRecordingCommandController]) can never resume a trip
     * twice or resurrect one that has since finished. Returns `true` only if it actually performed
     * the transition; `false` (a no-op) if [tripId] was not found or was not INTERRUPTED - callers
     * use that to decide whether to start collecting at all. Does not itself touch the recording
     * service or coordinator - the caller does that only after this returns `true`.
     */
    suspend fun resumeInterruptedTrip(tripId: Long): Boolean = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock false
        if (trip.state != TripState.INTERRUPTED.name) return@withLock false
        database.tripDao().update(trip.copy(state = TripState.ACTIVE.name))
        true
    }

    /** The user's choice to end an [TripState.INTERRUPTED] trip honestly at the last point actually recorded, rather than resuming it. */
    suspend fun finishInterruptedTripAtLastPoint(tripId: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.INTERRUPTED.name) return@withLock
        val endEpochSecond = trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
        database.tripDao().update(trip.copy(state = TripState.FINISHED.name, endEpochSecond = endEpochSecond))
    }

    suspend fun getTrip(tripId: Long): TripEntity? = database.tripDao().getById(tripId)

    /** Used by [com.example.stepsplit.trip.service.TripRecordingService] to resolve which trip a Finish command applies to without needing it passed through the triggering Intent - only one trip can ever be ACTIVE. */
    suspend fun getActiveTripId(): Long? = database.tripDao().getByState(TripState.ACTIVE.name)?.id

    fun observeTrip(tripId: Long): Flow<TripSummary?> = database.tripDao().observeById(tripId).map { it?.toSummary() }

    fun observeTrips(): Flow<List<TripSummary>> = database.tripDao().observeAll().map { trips -> trips.map { it.toSummary() } }

    fun observeTripPoints(tripId: Long): Flow<List<TripPoint>> =
        database.tripPointDao().observeForTrip(tripId).map { points -> points.map { it.toDomain() } }

    suspend fun getTripPoints(tripId: Long): List<TripPoint> =
        database.tripPointDao().getAllForTrip(tripId).map { it.toDomain() }

    /** Cascades to every point of this trip - see [TripPointEntity]'s foreign key. */
    suspend fun deleteTrip(tripId: Long) = database.tripDao().deleteById(tripId)

    private companion object {
        /** Generous enough to tolerate ordinary GPS-vs-device clock drift, tight enough that a corrupt/bogus far-future timestamp can never become the last-accepted point - see [recordAcceptedBatch]'s doc comment. */
        const val MAX_FUTURE_SKEW_SECONDS = 300L
    }
}

private fun TripEntity.toSummary() = TripSummary(
    id = id,
    startEpochSecond = startEpochSecond,
    endEpochSecond = endEpochSecond,
    startZoneId = startZoneId,
    state = TripState.valueOf(state),
    distanceMeters = distanceMeters,
    lastAcceptedPointEpochSecond = lastAcceptedPointEpochSecond,
)

private fun TripPointEntity.toDomain() = TripPoint(
    capturedAtEpochSecond = capturedAtEpochSecond,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)

private fun TripPointEntity.toSample() = RawLocationSample(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    capturedAtEpochSecond = capturedAtEpochSecond,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)

private fun RawLocationSample.toEntity(tripId: Long) = TripPointEntity(
    tripId = tripId,
    capturedAtEpochSecond = capturedAtEpochSecond,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)
