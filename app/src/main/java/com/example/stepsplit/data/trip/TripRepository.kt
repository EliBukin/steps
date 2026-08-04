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
) : TripRecordingRepository {
    private val tripMutex = Mutex()

    // In-memory only, deliberately not a Room column (no new schema for this MVP revision): at most
    // one trip is ever mid-Finish at a time, and [token] (an opaque, caller-chosen id - in practice
    // TripRecordingCommandController's own command generation, process-unique across every
    // controller/service instance - see CommandGenerationGate's own doc comment) is what makes this
    // cutoff owned rather than a shared, freely-overwritable field - see beginFinish/cancelFinish's
    // own doc comments for why that ownership matters. Finishing is a short-lived, single-process
    // operation; if the process dies mid-finish the trip is simply recovered like any other
    // mid-recording death, via the ordinary reconcileActiveTripOnLaunch -> INTERRUPTED path.
    private data class FinishCutoff(val tripId: Long, val token: Long, val cutoffEpochSecond: Long)

    private var finishCutoff: FinishCutoff? = null

    // In-memory only, same rationale as [finishCutoff] above: which token most recently registered
    // itself (via [beginRecording]) as the one actually driving live collection for a trip. [token]
    // values are process-unique *and* monotonically increasing (see CommandGenerationGate), which is
    // what lets [beginRecording] and [markTripInterruptedIfStillOwned] stay correct under arbitrary
    // suspend-induced reordering using nothing but a numeric comparison - see both methods' own doc
    // comments.
    private data class RecordingOwner(val tripId: Long, val token: Long)

    private var recordingOwner: RecordingOwner? = null

    /**
     * Idempotent: if a trip is already ACTIVE, its existing id is returned and no new row is
     * created - this is what makes a duplicate Start command (a double-tap, or the OS redelivering
     * a start command) safe, and is exactly the same path an OS-triggered service restart after
     * process death uses to recover the existing trip rather than creating a duplicate.
     */
    override suspend fun startTrip(): Long = tripMutex.withLock {
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
        finishTripLocked(tripId, endEpochSecond)
    }

    /**
     * The ownership-checked sibling [com.example.stepsplit.trip.service
     * .TripRecordingCommandController.handleFinish] actually calls, atomically combining the owner
     * comparison with the same mutation [finishTrip] performs - never a caller-side currency check
     * followed by an unguarded [finishTrip] call. [token] must still be the exact token that owns the
     * outstanding [FinishCutoff] for [tripId] (installed by [beginFinish]) at the instant this
     * acquires [tripMutex], not merely at some earlier point before the caller's own bounded
     * flush/grace wait: a newer command superseding this one during that wait either installs its own
     * newer Finish cutoff (a token mismatch below) or starts a fresh collector, whose own
     * [beginRecording] call clears an *older* cutoff outright (see that method's doc comment) - either
     * way this becomes a safe no-op instead of finishing a trip a newer collector has since taken back
     * over. Returns whether it actually finished the trip.
     */
    override suspend fun finishTripIfOwner(tripId: Long, endEpochSecond: Long, token: Long): Boolean = tripMutex.withLock {
        if (finishCutoff?.let { it.tripId == tripId && it.token == token } != true) return@withLock false
        finishTripLocked(tripId, endEpochSecond)
        true
    }

    private suspend fun finishTripLocked(tripId: Long, endEpochSecond: Long) {
        val trip = database.tripDao().getById(tripId) ?: return
        if (trip.state != TripState.ACTIVE.name) return
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
    override suspend fun beginFinish(tripId: Long, token: Long, cutoffEpochSecond: Long) = tripMutex.withLock {
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
    override suspend fun cancelFinish(token: Long) = tripMutex.withLock {
        if (finishCutoff?.token == token) finishCutoff = null
    }

    /**
     * Atomically registers [token] as the owner of live recording for [tripId] - called once by
     * [com.example.stepsplit.trip.service.TripRecordingCommandController.startCollecting], for every
     * one of Start, Resume, and restart recovery, immediately before it (re)starts a collector. A
     * monotonic compare-and-set, not an unconditional overwrite: [token] only takes ownership if it is
     * numerically greater than whatever token (if any) currently owns [recordingOwner], so a stale
     * call that only reaches this point *after* a genuinely newer registration already ran - exactly
     * what can happen once its own caller resumes from a suspend point after being superseded - cannot
     * clobber that newer registration back to itself. Because every token in this app is drawn from
     * the same process-wide, strictly increasing source (see
     * [com.example.stepsplit.trip.service.CommandGenerationGate]'s own doc
     * comment on generation identity), "numerically greater" and "issued later" are the same thing
     * here, so this ordering check is sound regardless of which thread/dispatcher actually executes
     * first. This is the sole ownership state [markTripInterruptedIfStillOwned] reads.
     *
     * Also atomically supersedes an *older* outstanding Finish cutoff for the same [tripId] as part of
     * the same locked step: a fresh collector registration inherently means the previous Finish
     * attempt for this trip, whatever it was, is no longer the live state, so any cutoff installed by
     * a token strictly older than this one is abandoned and must not silently reject this collector's
     * first points. A cutoff installed by a token *newer* than [token] is left completely untouched -
     * it belongs to a genuinely more current Finish this (by definition, older) registration must
     * never interfere with. This replaces the previous unscoped `clearAbandonedFinishCutoff(tripId)`
     * API, which decided "abandoned" purely from a trip id match under a caller-side currency check
     * that was not atomic with the clear itself, and could therefore wipe out a legitimately newer
     * Finish's cutoff installed in the gap between that check and this call actually running.
     */
    override suspend fun beginRecording(tripId: Long, token: Long) = tripMutex.withLock {
        if ((recordingOwner?.token ?: NO_OWNER_TOKEN) >= token) return@withLock
        recordingOwner = RecordingOwner(tripId, token)
        val abandonedCutoff = finishCutoff
        if (abandonedCutoff != null && abandonedCutoff.tripId == tripId && abandonedCutoff.token < token) {
            finishCutoff = null
        }
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
     * Atomically: transitions [tripId] from ACTIVE to INTERRUPTED only if (a) [isCurrent] - a fast,
     * synchronous, non-suspending predicate, evaluated as the very first action *inside* this call's
     * [tripMutex] hold - still says so, and (b) no recording registered via [beginRecording] with a
     * token *newer* than [token] currently owns [tripId]. Two distinct callers rely on this, both
     * from [com.example.stepsplit.trip.service.TripRecordingCommandController]:
     * - `handleRecordingFailure`, passing its own failing collector's generation as [token] and
     *   `{ gate.isCurrent(generation) }` as [isCurrent] - the normal case is `token` matching
     *   [recordingOwner] exactly (this collector still owns the trip) and [isCurrent] still true,
     *   neither of which is rejected below, so the interrupt proceeds.
     * - `handleForegroundPromotionFailure`, the same way, even though it never started a collector of
     *   its own (promotion fails before any command-specific work runs) and so has no owner token of
     *   its own to match exactly - it passes its own generation purely as a "no collector newer than
     *   this has taken over since" threshold for (b), honestly reconciling a trip an *earlier* command
     *   left ACTIVE once this command's own attempt to keep recording it alive has failed.
     * - [com.example.stepsplit.trip.service.TripRecordingCommandController.shutdown]'s reconciliation,
     *   which passes `{ true }` for [isCurrent]: by the time it runs, the gate is *always* permanently
     *   closed (that is the point of calling it), so gate currency is meaningless there and (b) - the
     *   token comparison alone - is what protects a genuinely newer controller/service instance that
     *   has since taken over the same trip.
     *
     * Both checks close a distinct gap, and neither is sufficient alone:
     * - (a) alone misses a newer collector belonging to a *different* controller/service instance -
     *   [isCurrent] as passed by `handleRecordingFailure`/`handleForegroundPromotionFailure` only
     *   reflects *this* instance's own gate, which has no way to know about a different instance's
     *   generations at all (see [com.example.stepsplit.trip.service.CommandGenerationGate]'s own doc
     *   comment on generation identity).
     * - (b) alone misses the case where a newer command has merely been *dispatched* (its generation
     *   reserved) but has not yet reached the point of registering a [beginRecording] call for
     *   anything - [recordingOwner] would still show the older token as owner, even though the older
     *   command is no longer actually current, because nothing has told [TripRecordingRepository]
     *   about the newer one yet.
     * Evaluating (a) as the very first statement inside this call's own [tripMutex] hold - not before
     * it, and not via a separate suspending call - is what makes it safe: [tripMutex] serializes every
     * trip mutation across its *entire* suspend duration (unlike a plain `synchronized` block, which
     * only covers synchronous code), so no other command's own [tripMutex]-guarded mutation - in
     * particular a newer [beginRecording] or [TripRepository.startTrip] call - can run between this
     * check and the [database.tripDao().update] below, regardless of how many real suspension points
     * (Room's own background executor included) separate them. A caller-side check performed *before*
     * calling this function at all would not have that property - see the class doc comment on this
     * pattern generally, and [com.example.stepsplit.trip.service.TripRecordingCommandController]'s own
     * historical regression test for `handleRecordingFailure` for the concrete race this specifically
     * has to survive (a newer command merely reserved, not yet doing anything else, at the moment of
     * the failure).
     *
     * A no-op (`false`) if the trip is not currently ACTIVE regardless (already finished/interrupted,
     * or a stale/duplicate failure), so this is safe to call more than once. On a successful
     * interrupt, also releases [recordingOwner] if it still names [tripId] - the trip is no longer
     * being recorded by anyone, by definition.
     */
    override suspend fun markTripInterruptedIfStillOwned(tripId: Long, token: Long, isCurrent: () -> Boolean): Boolean = tripMutex.withLock {
        if (!isCurrent()) return@withLock false
        val owner = recordingOwner
        if (owner != null && owner.tripId == tripId && owner.token > token) return@withLock false
        val trip = database.tripDao().getById(tripId) ?: return@withLock false
        if (trip.state != TripState.ACTIVE.name) return@withLock false
        database.tripDao().update(trip.copy(state = TripState.INTERRUPTED.name))
        if (owner != null && owner.tripId == tripId) recordingOwner = null
        true
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
    override suspend fun resumeInterruptedTrip(tripId: Long): Boolean = tripMutex.withLock {
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
    override suspend fun getActiveTripId(): Long? = database.tripDao().getByState(TripState.ACTIVE.name)?.id

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

        /** Lower than every real token (see [com.example.stepsplit.trip.service.CommandGenerationGate]'s counter, which starts at 1) so the very first [beginRecording] call always wins its compare-and-set. */
        const val NO_OWNER_TOKEN = -1L
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
