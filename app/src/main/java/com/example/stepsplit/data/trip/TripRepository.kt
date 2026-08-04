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
 *
 * ## Atomic trip claiming
 *
 * [claimTripForStart], [claimTripForResume], and [claimActiveTripForRestart] are the only ways
 * [com.example.stepsplit.trip.service.TripRecordingCommandController] resolves/creates/transitions a
 * trip *and* registers itself as that trip's recording owner - always as one atomic step, inside a
 * single [tripMutex] hold, never a trip-resolving call followed later by a separate ownership
 * registration. An earlier revision split those two steps (`startTrip()`/`resumeInterruptedTrip()`
 * followed later by a `beginRecording()` call once the coordinator was about to actually start), which
 * left a real suspend gap in which the trip was ACTIVE but not yet owned - long enough for a
 * concurrent [markTripInterruptedIfStillOwned] reconciliation (from a *different*, e.g.
 * shutting-down, controller/service instance racing this one) to observe it as ACTIVE-with-no-owner
 * and interrupt it, even though a genuinely newer command was about to (or already had) taken it over.
 * [startTrip] and [resumeInterruptedTrip] still exist below as plain, ownership-agnostic primitives,
 * but only test fixtures call them now - the command controller never does - precisely so that gap
 * can no longer exist: nothing in production ever transitions a trip to ACTIVE without registering an
 * owner for it in that same atomic step.
 *
 * ## Why `isCurrent` cannot be the safety mechanism
 *
 * Every `claim*` method and [markTripInterruptedIfStillOwned] takes an `isCurrent: () -> Boolean`
 * parameter and evaluates it as the first action inside [tripMutex]. **This is not, and cannot be, an
 * atomic check against [com.example.stepsplit.trip.service.CommandGenerationGate.begin] - [tripMutex]
 * (a suspend-aware `Mutex`) and the gate's own lock (a plain `synchronized` monitor) are two entirely
 * independent locks, held by different owners, with no happens-before relationship between them.**
 * `isCurrent()` only ever reports a *snapshot* - true the instant it was called, with no guarantee it
 * stays true for the rest of this suspending function body. A concurrent `beginCommand()` reserving a
 * newer generation can be, and in practice is, accepted at any point after that snapshot is taken,
 * including *during* the very next suspending Room call - there is no way to prevent that with a lock
 * inside [tripMutex] alone, and holding a `synchronized` block across suspending Room work is both
 * disallowed by the Kotlin compiler and would risk blocking a real thread (the Android main thread, for
 * `onDestroy`'s own reconciliation path) on I/O. `isCurrent()` is therefore purely an optimization - a
 * cheap way to avoid pointless Room work for a command that is *already, provably* stale at the moment
 * it asks - never the reason a stale claim cannot corrupt state.
 *
 * ## Convergence, not linearization
 *
 * Because no shared lock can span both domains, correctness instead comes from making every `claim*`
 * mutation safe to have happened *at all*, no matter how stale the command that performed it turns out
 * to have been by the time it actually commits:
 * - **The monotonic token compare-and-set against [recordingOwner]** is always evaluated fresh, entirely
 *   inside [tripMutex] - unlike `isCurrent()`, there is no cross-lock staleness here, since
 *   [recordingOwner] is itself only ever read or written under [tripMutex]. This guarantees the
 *   ownership state this repository holds always reflects whichever real `claim*` call most recently
 *   committed - by token order, which is the same as real time order (see
 *   [com.example.stepsplit.trip.service.CommandGenerationGate]'s own doc comment) - never a stale one
 *   clobbering a newer one.
 * - **[claimTripForStart] and [claimActiveTripForRestart] are unconditionally idempotent**: they always
 *   operate on "whichever trip is currently ACTIVE, if any" rather than asserting a trip is in some
 *   specific state first, so a stale claim committing first can never leave the trip in a state a
 *   genuinely current, later claim cannot simply take over.
 * - **[claimTripForResume] converges the same way for the case that isn't trivially idempotent, and it
 *   must handle a stale claim landing on either the *same* target trip or a *different* one**: the
 *   token compare-and-set is against the single [recordingOwner] field, not against "whoever owns this
 *   specific trip" - so it already proves [claimTripForResume]'s own `token` is strictly newer than
 *   whoever owns *whichever* trip is currently `ACTIVE`, regardless of which trip that is. If it is the
 *   trip this call targets, that call simply takes over ownership; if it is a genuinely *different*
 *   trip, this call atomically supersedes it too - transitioning that other trip back to `INTERRUPTED`
 *   and its own target to `ACTIVE` in one Room transaction - rather than failing because its own target
 *   merely isn't the trip currently occupying the single `ACTIVE` slot. See that method's own doc
 *   comment for the full argument, including why this can never allow two trips to be simultaneously
 *   `ACTIVE`, even transiently.
 * - **The actual linearization point - the one true atomic check-and-act in this whole system - is
 *   [com.example.stepsplit.trip.service.CommandGenerationGate.runIfCurrent]**, called by
 *   `TripRecordingCommandController.startCollecting` only *after* a `claim*` call returns. It holds the
 *   gate's own single lock for both the currency check and the synchronous `coordinator.start()` call,
 *   so nothing can go stale between them. A stale claim can therefore succeed and mutate Room - that is
 *   harmless, by the convergence properties above - but it can *never* actually start a collector once
 *   superseded, because [CommandGenerationGate.runIfCurrent] rejects it unconditionally at that instant.
 *
 * Put together: a `claim*` call is *optimistic*, not authoritative. Whichever command is *genuinely* the
 * last one dispatched - the only one guaranteed to still be current when its own turn to call
 * [CommandGenerationGate.runIfCurrent] arrives, since by definition nothing supersedes it afterward -
 * always converges onto owning the trip and starting its collector, regardless of how many older,
 * stale claims committed in the meantime. [markTripInterruptedIfStillOwned] is audited against this same
 * race in its own doc comment; it does not need the same convergent-takeover treatment, for a different
 * reason explained there.
 */
class TripRepository(
    private val database: StepSplitDatabase,
    private val clock: Clock,
    /**
     * Test-only seam, a no-op in production (the default): invoked, still holding [tripMutex], with
     * [token] immediately after every `isCurrent()` check in [claimTripForStart], [claimTripForResume],
     * [claimActiveTripForRestart], and [markTripInterruptedIfStillOwned] - the exact point after which
     * that check's result can no longer be trusted, since [tripMutex] and
     * [com.example.stepsplit.trip.service.CommandGenerationGate]'s own lock are independent (see the
     * class doc comment's "Why `isCurrent` cannot be the safety mechanism" section). Lets a test
     * deterministically suspend a specific call (scoped by [token], so other calls sharing this same
     * repository instance are unaffected) at exactly that boundary - no fake extra repository instance
     * (which would fork [recordingOwner]/[finishCutoff] state away from what other callers in the same
     * test observe) or sleep required.
     */
    private val afterCurrencyCheck: suspend (token: Long) -> Unit = {},
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
    // itself (via a `claim*` method - see [claimTripForStart]) as the one actually driving live
    // collection for a trip. [token] values are process-unique *and* monotonically increasing (see
    // CommandGenerationGate), which is what lets the `claim*` methods and
    // [markTripInterruptedIfStillOwned] stay correct under arbitrary suspend-induced reordering using
    // nothing but a numeric comparison - see each method's own doc comment.
    private data class RecordingOwner(val tripId: Long, val token: Long)

    private var recordingOwner: RecordingOwner? = null

    /**
     * A plain, ownership-agnostic primitive: idempotent (if a trip is already ACTIVE, its existing id
     * is returned and no new row is created), but registers no recording owner - see the class doc
     * comment's "Atomic trip claiming" section for why the command controller uses [claimTripForStart]
     * instead of this directly. Kept for test fixtures that need an ACTIVE trip without exercising the
     * ownership machinery at all.
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
     * newer Finish cutoff (a token mismatch below) or starts a fresh collector, whose own `claim*` call
     * (see [claimTripForStart]) clears an *older* cutoff outright (see [claimOwnershipLocked]'s doc
     * comment) - either way this becomes a safe no-op instead of finishing a trip a newer collector has
     * since taken back over. Returns whether it actually finished the trip.
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
     * Resolves/creates the trip a Start command should operate on, verifies it, and registers [token]
     * as its recording owner - all inside one [tripMutex] hold. This replaces a previous two-step
     * `startTrip()` followed later by a separate `beginRecording()` call, which left a real suspend gap
     * between "the trip is ACTIVE" and "someone owns it" that a concurrent [markTripInterruptedIfStillOwned]
     * reconciliation (e.g. from a *different*, shutting-down controller/service instance racing this
     * one - see that method's own doc comment) could land in, interrupting a trip this call was about
     * to (or had just started to) own.
     *
     * Idempotent like the old `startTrip()`: reuses an already-`ACTIVE` trip's id if one exists rather
     * than creating a duplicate, which is also what makes a duplicate/redelivered Start command safe -
     * and, combined with the monotonic token compare-and-set against [recordingOwner] below, is exactly
     * what makes this call *convergent*: see the class doc comment's "Convergence, not linearization"
     * section for the full argument. [isCurrent] - re-evaluated as the very first action inside this
     * call's [tripMutex] hold - is an optimization only: it lets an *already, provably* stale call (one
     * whose own generation was superseded before this call even began) skip Room work entirely, but it
     * is **not** what makes a call that passes this check safe, since [isCurrent]'s snapshot can go
     * stale the instant a suspending Room call below yields - see the class doc comment's "Why
     * `isCurrent` cannot be the safety mechanism" section. Safety instead comes from this call's
     * idempotent reuse semantics: even if this call's own claim ends up stale by the time it commits, a
     * genuinely newer call always finds a trip it can simply take back over.
     *
     * Returns `null`, touching no trip row, owner, or cutoff at all, if either check fails - see
     * [claimOwnershipLocked]'s own doc comment for why the compare-and-set guards the *entire*
     * operation, evaluated before any trip lookup/creation, rather than only the ownership registration
     * at the end.
     */
    override suspend fun claimTripForStart(token: Long, isCurrent: () -> Boolean): Long? = tripMutex.withLock {
        if (!isCurrent()) return@withLock null
        afterCurrencyCheck(token)
        if ((recordingOwner?.token ?: NO_OWNER_TOKEN) >= token) return@withLock null
        val tripId = database.tripDao().getByState(TripState.ACTIVE.name)?.id ?: run {
            val now = clock.instant()
            database.tripDao().insert(
                TripEntity(
                    startEpochSecond = now.epochSecond,
                    endEpochSecond = null,
                    startZoneId = clock.zone.id,
                    state = TripState.ACTIVE.name,
                    distanceMeters = 0.0,
                    lastAcceptedPointEpochSecond = null,
                    createdAtEpochSecond = now.epochSecond,
                ),
            )
        }
        claimOwnershipLocked(tripId, token)
        tripId
    }

    /**
     * The Resume sibling of [claimTripForStart]: converges [tripId] to `ACTIVE` and registers [token]
     * as its recording owner - all inside one [tripMutex] hold, so there is no gap in which the trip is
     * `ACTIVE` but not yet owned, and no way for two trips to ever be simultaneously `ACTIVE`, even
     * transiently. Never creates a new trip and never touches a trip that is `FINISHED` or unknown -
     * returns `null`, unconditionally, in either case.
     *
     * **Handles three starting situations for [tripId], not one - this is the convergent-takeover half
     * of the class doc comment's "Convergence, not linearization" argument, covering both a racing
     * claim for the identical trip and one for a genuinely different trip:**
     * - No trip is currently `ACTIVE`, and [tripId] is `INTERRUPTED` - the ordinary case: transitions it
     *   to `ACTIVE` with a single row update.
     * - [tripId] *itself* is already `ACTIVE` - most plausibly because an *older* Resume for this
     *   identical [tripId], delayed past its own currency check by a concurrent, newer [beginCommand],
     *   already committed it. Rather than fail here (which is what an earlier revision did, on the
     *   reasoning that Resume should only ever apply to an `INTERRUPTED` trip), this call simply takes
     *   over ownership without touching Room's own state column - it is already correct. Safe because
     *   the token compare-and-set above has already proven [token] is strictly newer than whoever
     *   committed that, and because, by the single-ACTIVE-trip invariant every claim method preserves,
     *   if [tripId] is `ACTIVE` it *is* "the" `ACTIVE` trip - there is nothing further to verify.
     * - A *different* trip is currently `ACTIVE`, and [tripId] is `INTERRUPTED` - most plausibly because
     *   an older command (a Resume for that different trip, or a Start that created/reused it), delayed
     *   the identical way, already committed it. The token compare-and-set above compares [token]
     *   against [recordingOwner] *regardless of which trip it currently names* - so by the time this
     *   branch runs, [token] has already been proven strictly newer than whoever owns *whichever* trip
     *   is currently `ACTIVE`, not merely newer than whoever might own [tripId] specifically. Superseding
     *   it is therefore always correct: this call atomically transitions the other trip to `INTERRUPTED`
     *   and [tripId] to `ACTIVE` in a single [database.withTransaction] block, so no external reader that
     *   does not go through [tripMutex] at all - [getActiveTripId], `observeTrips`, the launch-time
     *   reconciliation - can ever observe two `ACTIVE` rows, not even momentarily between two separately
     *   committed updates. An earlier revision rejected this case outright (`currentlyActive != null` ⇒
     *   `null`), which left the *other* trip falsely `ACTIVE` with nothing recording it whenever this
     *   exact race occurred - see `TripRecordingCommandControllerTest`'s own historical regression
     *   coverage for the concrete interleaving (both a stale Resume and a stale Start racing a newer
     *   Resume for a different trip) this specifically has to survive.
     *
     * [isCurrent] and the monotonic token compare-and-set against [recordingOwner] are both evaluated as
     * the very first actions, before any of the three branches above - see [claimTripForStart]'s own
     * doc comment, and the class doc comment's "Why `isCurrent` cannot be the safety mechanism" section,
     * for why [isCurrent] is an optimization only and the *actual* safety here comes from the token
     * compare-and-set plus this convergent-takeover handling, not from [isCurrent] remaining true for
     * the rest of this call.
     */
    override suspend fun claimTripForResume(tripId: Long, token: Long, isCurrent: () -> Boolean): Long? = tripMutex.withLock {
        if (!isCurrent()) return@withLock null
        afterCurrencyCheck(token)
        if ((recordingOwner?.token ?: NO_OWNER_TOKEN) >= token) return@withLock null
        val trip = database.tripDao().getById(tripId) ?: return@withLock null
        when (trip.state) {
            TripState.INTERRUPTED.name -> {
                val currentlyActive = database.tripDao().getByState(TripState.ACTIVE.name)
                when {
                    currentlyActive == null -> database.tripDao().update(trip.copy(state = TripState.ACTIVE.name))
                    else -> database.withTransaction {
                        database.tripDao().update(currentlyActive.copy(state = TripState.INTERRUPTED.name))
                        database.tripDao().update(trip.copy(state = TripState.ACTIVE.name))
                    }
                }
            }
            TripState.ACTIVE.name -> {
                // Convergent takeover of this exact trip - no Room mutation needed, see doc comment.
            }
            else -> return@withLock null // FINISHED, or any other non-resumable state
        }
        claimOwnershipLocked(tripId, token)
        tripId
    }

    /**
     * The null-Intent-restart sibling of [claimTripForStart]: resolves whichever trip is currently
     * `ACTIVE` (if any) and registers [token] as its recording owner - all inside one [tripMutex] hold.
     * Never creates a trip ([claimTripForStart]'s job) and never transitions an `INTERRUPTED` trip back
     * to `ACTIVE` ([claimTripForResume]'s job) - a null-Intent restart may only *recover* a trip Android
     * itself already knows is `ACTIVE`, per
     * [com.example.stepsplit.trip.service.TripRecordingCommandController.handleRestart]'s own contract.
     * Already convergent by construction, the same way [claimTripForStart] is: it always operates on
     * "whichever trip is currently ACTIVE," so a stale claim committing first can never stop a
     * genuinely newer one from simply taking it back over - see the class doc comment's "Convergence,
     * not linearization" section. Returns `null` if no trip is `ACTIVE`, if [isCurrent] is false (an
     * optimization only - see the class doc comment's "Why `isCurrent` cannot be the safety mechanism"
     * section), or if [token] is not strictly newer than whatever currently owns [recordingOwner].
     */
    override suspend fun claimActiveTripForRestart(token: Long, isCurrent: () -> Boolean): Long? = tripMutex.withLock {
        if (!isCurrent()) return@withLock null
        afterCurrencyCheck(token)
        if ((recordingOwner?.token ?: NO_OWNER_TOKEN) >= token) return@withLock null
        val trip = database.tripDao().getByState(TripState.ACTIVE.name) ?: return@withLock null
        claimOwnershipLocked(trip.id, token)
        trip.id
    }

    /**
     * The shared second half of every `claim*` method above - call only after already having decided,
     * under [tripMutex], that [tripId] genuinely is (or was just transitioned/created to be) `ACTIVE`
     * and that [token] has already won the monotonic compare-and-set against [recordingOwner] (each
     * caller performs that check itself, *before* doing any trip lookup/creation/transition, so a
     * stale call touches no trip row at all - see each caller's own doc comment). Does not itself
     * re-check ownership: by the time this runs, the caller has already established [token] is the
     * winner.
     *
     * Also atomically supersedes an *older* outstanding Finish cutoff for the same [tripId]: a fresh
     * claim inherently means the previous Finish attempt for this trip, whatever it was, is no longer
     * the live state, so any cutoff installed by a token strictly older than this one is abandoned and
     * must not silently reject this claim's first points. A cutoff installed by a token *newer* than
     * [token] is left completely untouched - it belongs to a genuinely more current Finish this (by
     * definition, older) claim must never interfere with. This is what replaced the previous unscoped
     * `clearAbandonedFinishCutoff(tripId)` API, which decided "abandoned" purely from a trip id match
     * under a caller-side currency check that was not atomic with the clear itself.
     */
    private fun claimOwnershipLocked(tripId: Long, token: Long) {
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
     * Atomically: resolves whichever trip is currently ACTIVE (if any) and transitions it to
     * INTERRUPTED only if (a) [isCurrent] - a fast, synchronous, non-suspending predicate, evaluated as
     * the very first action *inside* this call's [tripMutex] hold - still says so, and (b) no recording
     * registered via a `claim*` method (see [claimTripForStart]) with a token *newer* than [token]
     * currently owns it. Resolves the target trip itself, atomically alongside both checks, rather than
     * taking a `tripId` the caller resolved earlier via a separate [getActiveTripId] call: that older
     * shape left its own gap, symmetric to the one the `claim*` methods themselves close (see the class
     * doc comment's "Atomic trip claiming" section) - a trip that became ACTIVE, and was claimed, in the
     * time between the caller's read and this call's mutation would otherwise be visible to (and
     * therefore votable by) a check that has no business considering it at all. Three distinct callers
     * rely on this, all from [com.example.stepsplit.trip.service.TripRecordingCommandController]:
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
     *   has since taken over the same (idempotently reused, or freshly claimed) trip.
     *
     * Both checks close a distinct gap, and neither is sufficient alone:
     * - (a) alone misses a newer collector belonging to a *different* controller/service instance -
     *   [isCurrent] as passed by `handleRecordingFailure`/`handleForegroundPromotionFailure` only
     *   reflects *this* instance's own gate, which has no way to know about a different instance's
     *   generations at all (see [com.example.stepsplit.trip.service.CommandGenerationGate]'s own doc
     *   comment on generation identity).
     * - (b) alone misses the case where a newer command has merely been *dispatched* (its generation
     *   reserved) but has not yet reached the point of actually completing a `claim*` call - the trip
     *   this call resolves would still show no owner at all (or the older token as owner), even though
     *   the older command is no longer actually current, because nothing has told this repository about
     *   the newer one yet.
     *
     * Resolving the target trip fresh (rather than accepting a `tripId` the caller resolved earlier via
     * a separate [getActiveTripId] call), and re-reading [recordingOwner] fresh for check (b), both
     * happen entirely inside this call's own [tripMutex] hold, so neither can go stale relative to any
     * *other* [tripMutex]-guarded mutation, in particular a newer `claim*` call - that part genuinely is
     * atomic. Check (a), the [isCurrent] snapshot, is a different story: it is **not** atomic with
     * [com.example.stepsplit.trip.service.CommandGenerationGate.begin] - see the class doc comment's
     * "Why `isCurrent` cannot be the safety mechanism" section - so a newer generation can still be
     * reserved after (a) reads `true` but before this call's own Room work commits.
     *
     * **Unlike the `claim*` methods, that staleness in (a) does not need a convergent redesign here,
     * for a structural reason: this call has no precondition on the trip's own state that a racing
     * older mutation could invalidate out from under a newer one.** A `claim*` call can fail outright if
     * its target trip's state no longer matches what it required (which is exactly the gap
     * [claimTripForResume]'s convergent-takeover branch exists to close). This call always simply
     * transitions "whichever trip is ACTIVE, if any" to INTERRUPTED, and check (b) - re-read fresh, with
     * no cross-lock staleness of its own - is what actually decides whether that is still correct at the
     * moment it runs, independent of how stale (a) has become: if some newer command has, by then,
     * already completed its own `claim*` call, (b) rejects the interrupt regardless of what (a) said. The
     * only way this call can succeed despite (a) being stale is if no newer command has *actually
     * claimed anything yet* - in which case honestly marking the trip INTERRUPTED is the correct outcome
     * regardless (nothing is provably still recording it), and that newer command's own eventual claim
     * proceeds normally afterward via the same convergent semantics every `claim*` method provides. The
     * only user-visible cost of this residual staleness is a possible split into two trip rows instead of
     * one continuous one - never a trip stuck ACTIVE with no collector, and never two simultaneously
     * ACTIVE trips - see [com.example.stepsplit.trip.service.TripRecordingCommandController]'s own
     * historical regression tests for the concrete races this and the `claim*` methods each have to
     * survive.
     *
     * A no-op (`false`) if no trip is currently ACTIVE at all, so this is safe to call more than once.
     * On a successful interrupt, also releases [recordingOwner] if it still names the trip - the trip is
     * no longer being recorded by anyone, by definition.
     */
    override suspend fun markTripInterruptedIfStillOwned(token: Long, isCurrent: () -> Boolean): Boolean = tripMutex.withLock {
        if (!isCurrent()) return@withLock false
        afterCurrencyCheck(token)
        val trip = database.tripDao().getByState(TripState.ACTIVE.name) ?: return@withLock false
        val owner = recordingOwner
        if (owner != null && owner.tripId == trip.id && owner.token > token) return@withLock false
        database.tripDao().update(trip.copy(state = TripState.INTERRUPTED.name))
        if (owner != null && owner.tripId == trip.id) recordingOwner = null
        true
    }

    /**
     * A plain, ownership-agnostic primitive, same rationale as [startTrip]: verifies [tripId] is still
     * INTERRUPTED and transitions it to ACTIVE in one locked step, but registers no recording owner -
     * see the class doc comment's "Atomic trip claiming" section for why the command controller uses
     * [claimTripForResume] instead of this directly. Returns `true` only if it actually performed the
     * transition; `false` (a no-op) if [tripId] was not found or was not INTERRUPTED. Kept for test
     * fixtures that need to exercise this state transition in isolation.
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

        /** Lower than every real token (see [com.example.stepsplit.trip.service.CommandGenerationGate]'s counter, which starts at 1) so the very first `claim*` call always wins its compare-and-set. */
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
