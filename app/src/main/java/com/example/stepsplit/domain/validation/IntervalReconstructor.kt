package com.example.stepsplit.domain.validation

/**
 * One durable, permanently-materialized interval as already persisted in
 * [com.example.stepsplit.data.local.motion.ActivityIntervalEntity] - the seed every
 * [IntervalReconstructor.applySignal] call starts from (see that function's own doc comment for why
 * seeding, not a bounded window of signals, is what makes reconstruction correct regardless of how
 * old an interval is).
 */
data class PersistedInterval(
    /** Null only for a not-yet-inserted new interval about to be returned inside an [IntervalMutationSet]. */
    val id: Long?,
    val activityType: MotionActivityType,
    val startWallClockEpochMilli: Long,
    /** Null = still open/unresolved. */
    val endWallClockEpochMilli: Long?,
    val temporalContinuityEpoch: Long,
    /** `"OPEN"` when [endWallClockEpochMilli] is null; otherwise why it was already closed at insert time (e.g. a retroactively-reconstructed interval - see [IntervalReconstructor.applyTransition]). */
    val closedReason: String,
) {
    init {
        // A structural invariant, not just a convention: an interval with end <= start is nonsense
        // regardless of how it was produced, and out-of-order/delayed evidence is exactly the kind
        // of input that could otherwise construct one silently. Enforced here (the one place every
        // PersistedInterval - including every insert IntervalReconstructor ever returns - is built)
        // rather than trusted to every call site.
        val end = endWallClockEpochMilli
        if (end != null) {
            require(end > startWallClockEpochMilli) {
                "Invalid interval for $activityType: endWallClockEpochMilli ($end) must be strictly after startWallClockEpochMilli ($startWallClockEpochMilli)"
            }
        }
    }
}

/** Closes one specific, already-identified persisted interval by id - never a range/bulk delete. */
data class CloseOperation(val intervalId: Long, val endWallClockEpochMilli: Long, val closedReason: String)

/**
 * The complete, minimal set of row-level mutations [IntervalReconstructor.applySignal] wants
 * applied - together, atomically, in one transaction. A single sealed choice (close XOR insert)
 * cannot represent switching between two tracked types (e.g. WALKING -> RUNNING), which needs both
 * at once: the old row closed and the new one inserted in the same commit.
 */
data class IntervalMutationSet(
    val closes: List<CloseOperation> = emptyList(),
    val inserts: List<PersistedInterval> = emptyList(),
) {
    val isEmpty: Boolean get() = closes.isEmpty() && inserts.isEmpty()
}

/**
 * A newly-received piece of evidence to reconcile against already-persisted interval state. Two
 * deliberately different shapes - not one generic "activity event" type - because they are allowed
 * to do fundamentally different things to [com.example.stepsplit.data.local.motion.ActivityIntervalEntity]:
 * only a real Transition-API event can ever CREATE or EXTEND an interval; a sampled result can only
 * ever CLOSE one. Modeling a single sampled result as a "synthetic ENTER" was an earlier, incorrect
 * design - it would let one high-confidence sampled WALKING result materialize a permanent interval,
 * silently bypassing the policy's own "at least two consecutive samples" positive-evidence rule
 * (see [StrictStepValidationPolicy]'s rule 7). Keeping the two signal shapes distinct makes that bug
 * impossible to reintroduce by construction rather than by convention.
 */
sealed interface ReconciliationSignal {
    val wallClockEpochMilli: Long
    val temporalContinuityEpoch: Long

    /** The only signal shape that can OPEN a tracked-type row - Transition API `ENTER`/`EXIT` only. */
    data class Transition(
        val activityType: MotionActivityType,
        val isEnter: Boolean,
        override val wallClockEpochMilli: Long,
        override val temporalContinuityEpoch: Long,
    ) : ReconciliationSignal

    /**
     * Derived once per sampled `ActivityRecognitionResult` batch whose TOP activity is not one of
     * the group's tracked types (see the acquisition layer's atomic batch processing). Can only
     * ever CLOSE an open interval of the group it's applied against - [applySignal] never produces
     * an insert for this signal shape, and it is only ever offered to the positive
     * (WALKING/RUNNING) group, never the vehicle group (a sampled result must never be able to
     * close - or otherwise touch - a vehicle/bicycle veto interval).
     */
    data class SampledInterrupt(
        override val wallClockEpochMilli: Long,
        override val temporalContinuityEpoch: Long,
    ) : ReconciliationSignal
}

/**
 * Deterministically reconciles one newly-received [ReconciliationSignal] against already-persisted
 * [PersistedInterval] state, for one "interrupt-group" at a time (the vehicle/bicycle group, or the
 * walking/running group - see [applySignal]'s own parameters). Two properties make this safe in the
 * ways a naive bounded-window rebuild is not:
 *
 * 1. **Always seeded from the permanent store, never from a time window.** [currentOpenByType] is
 *    looked up by the caller with a plain, unbounded "is there a currently-open row of this type"
 *    query - so it does not matter whether that row's own start is 3 minutes or 3 hours before the
 *    new signal. An `EXIT` always closes its true, historically-real matching open interval.
 * 2. **Every mutation is targeted, never a range delete/replace.** [applySignal] returns at most one
 *    or two [CloseOperation]s (closing exactly the row(s) actually affected, by id) plus at most one
 *    [PersistedInterval] insert - nothing outside those specific rows is ever touched.
 *
 * [nearbySignals] exists for a narrower, different problem: reordering among signals that arrive
 * close together in time (e.g. a delayed, chronologically-older `ENTER` processed after a newer
 * `EXIT` already ran). It answers "is there a signal, chronologically after the one I'm processing
 * now, that already resolves it" - not "was there ever an interval before this window," which
 * [currentOpenByType] already answers unconditionally. The caller is responsible for widening
 * [nearbySignals] (per [ValidationConstants.reconciliationWindowSeconds]) when the new signal is
 * unusually late relative to what's already known for this group.
 */
object IntervalReconstructor {

    /**
     * @param trackedTypes types that can be opened/closed as their own interval rows, e.g.
     *   `{WALKING, RUNNING}` or `{IN_VEHICLE, ON_BICYCLE}`.
     * @param interrupterTypes Transition-API `ENTER` types (of neither group's own tracked set)
     *   that close an open tracked interval without opening anything themselves - e.g. `STILL` for
     *   the positive group. Empty for the vehicle group: only its own `EXIT`, or a switch to the
     *   other vehicle-group type, may ever close a vehicle/bicycle interval - never `WALKING`,
     *   since Google documents `WALKING` and `IN_VEHICLE` as able to be simultaneously
     *   high-confidence (walking around inside a moving bus).
     * @param currentOpenByType the unbounded §1.2-style seed - at most one entry per tracked type,
     *   each with `endWallClockEpochMilli == null`.
     * @param nearbySignals every other known signal for this group within the reconciliation
     *   window around [newSignal] (or wider, if the caller already detected [newSignal] is very
     *   late) - used only for the short-range reordering check described above.
     * @param failClosedIfLateAndUnconfirmed true for the positive group, false for the vehicle
     *   group - see the class doc comment: a very-late `ENTER` that cannot be confirmed safe (no
     *   newer closing signal found even after widening) contributes nothing for the positive group,
     *   but the vehicle group instead always performs the wider reconciliation and trusts the
     *   result, since under-vetoing is the unsafe direction there.
     */
    fun applySignal(
        trackedTypes: Set<MotionActivityType>,
        interrupterTypes: Set<MotionActivityType>,
        newSignal: ReconciliationSignal,
        currentOpenByType: Map<MotionActivityType, PersistedInterval>,
        nearbySignals: List<ReconciliationSignal>,
        failClosedIfLateAndUnconfirmed: Boolean,
        constants: ValidationConstants = ValidationConstants.DEFAULT,
    ): IntervalMutationSet {
        return when (newSignal) {
            is ReconciliationSignal.SampledInterrupt -> applySampledInterrupt(newSignal, currentOpenByType)
            is ReconciliationSignal.Transition -> applyTransition(
                trackedTypes, interrupterTypes, newSignal, currentOpenByType, nearbySignals,
                failClosedIfLateAndUnconfirmed, constants,
            )
        }
    }

    /** Constructs a [CloseOperation] closing [open] - never callable with an end at or before [open]'s own start, so a chronology bug elsewhere fails loudly here rather than silently writing end<=start. */
    private fun closeOperation(open: PersistedInterval, endWallClockEpochMilli: Long, closedReason: String): CloseOperation {
        require(endWallClockEpochMilli > open.startWallClockEpochMilli) {
            "Refusing to close interval ${open.id} (${open.activityType}, started ${open.startWallClockEpochMilli}) " +
                "at $endWallClockEpochMilli - end would not be strictly after start."
        }
        return CloseOperation(open.id!!, endWallClockEpochMilli, closedReason)
    }

    private fun applySampledInterrupt(
        signal: ReconciliationSignal.SampledInterrupt,
        currentOpenByType: Map<MotionActivityType, PersistedInterval>,
    ): IntervalMutationSet {
        val open = currentOpenByType.values.firstOrNull { it.endWallClockEpochMilli == null }
            ?: return IntervalMutationSet()
        // A sampled result chronologically BEFORE this interval even started cannot possibly be the
        // thing that ends it - see the identical reasoning on every Transition-signal guard below.
        if (open.startWallClockEpochMilli >= signal.wallClockEpochMilli) return IntervalMutationSet()
        return IntervalMutationSet(
            closes = listOf(closeOperation(open, signal.wallClockEpochMilli, ClosedReason.INTERRUPTED_BY_CONFLICT)),
        )
    }

    private fun applyTransition(
        trackedTypes: Set<MotionActivityType>,
        interrupterTypes: Set<MotionActivityType>,
        signal: ReconciliationSignal.Transition,
        currentOpenByType: Map<MotionActivityType, PersistedInterval>,
        nearbySignals: List<ReconciliationSignal>,
        failClosedIfLateAndUnconfirmed: Boolean,
        constants: ValidationConstants,
    ): IntervalMutationSet {
        if (!signal.isEnter) {
            // EXIT: close the matching open row if one exists. If none exists (an orphaned/delayed
            // EXIT with no open interval to close yet), this is a no-op now - a later, chronologically
            // -older ENTER for the same type will pick this exact EXIT up via nearbySignals and use
            // it to construct the correct already-closed interval directly (see below).
            val open = currentOpenByType[signal.activityType]?.takeIf { it.endWallClockEpochMilli == null }
                ?: return IntervalMutationSet()
            // An EXIT chronologically BEFORE the currently-open interval's own start cannot be this
            // interval's own EXIT at all - it belongs to some earlier, already-closed (or not-yet-
            // reconstructed) segment. Closing the open interval with it would both misattribute the
            // event and construct an invalid end<start interval. Leave the open interval untouched;
            // this EXIT stays in the evidence log for a later delayed-ENTER to pick up via
            // nearbySignals (see the ENTER branch below).
            if (signal.wallClockEpochMilli <= open.startWallClockEpochMilli) return IntervalMutationSet()
            return IntervalMutationSet(
                closes = listOf(closeOperation(open, signal.wallClockEpochMilli, ClosedReason.OWN_EXIT)),
            )
        }

        // ENTER. Interrupter-type ENTERs (e.g. STILL against the positive group) never open a row
        // of their own - they only ever close whichever tracked type is currently open, exactly
        // like a SampledInterrupt.
        if (signal.activityType !in trackedTypes) {
            check(signal.activityType in interrupterTypes) {
                "Transition ENTER for ${signal.activityType} is neither a tracked nor an interrupter type for this group"
            }
            val open = currentOpenByType.values.firstOrNull { it.endWallClockEpochMilli == null }
                ?: return IntervalMutationSet()
            if (open.startWallClockEpochMilli >= signal.wallClockEpochMilli) return IntervalMutationSet()
            return IntervalMutationSet(
                closes = listOf(closeOperation(open, signal.wallClockEpochMilli, ClosedReason.INTERRUPTED_BY_CONFLICT)),
            )
        }

        // A genuine duplicate/glitchy re-ENTER of the type that's already open (at or after its own
        // start) must not reset its start - a no-op. A delayed ENTER chronologically OLDER than the
        // already-open interval's own start is a DIFFERENT historical event entirely (e.g. the
        // interval closed and reopened, or this is the true start of a segment a later EXIT already
        // resolved) - it must fall through to the reconstruction logic below instead of being
        // silently dropped, and must never touch the currently-open interval (handled by the
        // same-type exclusion in the "otherOpenType" check further down).
        val alreadyOpenSameType = currentOpenByType[signal.activityType]
        if (alreadyOpenSameType != null && alreadyOpenSameType.endWallClockEpochMilli == null &&
            signal.wallClockEpochMilli >= alreadyOpenSameType.startWallClockEpochMilli
        ) {
            return IntervalMutationSet()
        }

        // Look forward among nearby signals for anything that already, chronologically, resolves
        // this ENTER - the mechanism that makes a delayed ENTER processed after its own EXIT (or
        // after a conflicting/switching signal) produce a correctly already-closed interval instead
        // of a bogus "still open" one, regardless of arrival order.
        val resolvingSignal = nearbySignals
            .filter { it.wallClockEpochMilli > signal.wallClockEpochMilli }
            .filter { candidate -> resolves(candidate, signal.activityType, trackedTypes, interrupterTypes) }
            .minByOrNull { it.wallClockEpochMilli }

        val lastKnown = nearbySignals.maxOfOrNull { it.wallClockEpochMilli }
        val isVeryLate = lastKnown != null &&
            (lastKnown - signal.wallClockEpochMilli) > constants.reconciliationWindowSeconds * 1000L
        if (failClosedIfLateAndUnconfirmed && isVeryLate && resolvingSignal == null) {
            // Very late, and even the widened check (the caller is expected to have already widened
            // nearbySignals for this case) found nothing newer to confirm this ENTER is safe to
            // trust - contribute nothing rather than risk granting stale positive coverage.
            return IntervalMutationSet()
        }

        val closes = mutableListOf<CloseOperation>()
        // Only a DIFFERENT-type interval that started AT OR BEFORE this ENTER can possibly have been
        // superseded/switched-away-from by it - a delayed, chronologically-OLDER ENTER of another
        // type must never reach forward in time to close an interval that started AFTER it (that
        // interval is simply newer and unrelated to this delayed report - see the "preserve the
        // newer bicycle interval" regression test).
        val otherOpenType = currentOpenByType.values.firstOrNull {
            it.endWallClockEpochMilli == null && it.activityType != signal.activityType &&
                signal.wallClockEpochMilli >= it.startWallClockEpochMilli
        }
        if (otherOpenType != null) {
            closes += closeOperation(otherOpenType, signal.wallClockEpochMilli, ClosedReason.INTERRUPTED_BY_CONFLICT)
        }

        val endTime = resolvingSignal?.wallClockEpochMilli
        val closedReasonForInsert = when {
            endTime == null -> ClosedReason.OPEN
            resolvingSignal is ReconciliationSignal.Transition && !resolvingSignal.isEnter -> ClosedReason.OWN_EXIT
            else -> ClosedReason.INTERRUPTED_BY_CONFLICT
        }
        val inserted = PersistedInterval(
            id = null,
            activityType = signal.activityType,
            startWallClockEpochMilli = signal.wallClockEpochMilli,
            endWallClockEpochMilli = endTime,
            temporalContinuityEpoch = signal.temporalContinuityEpoch,
            closedReason = closedReasonForInsert,
        )
        return IntervalMutationSet(closes = closes, inserts = listOf(inserted))
    }

    /** True if [candidate] would, chronologically, close an interval of [openType] that started at or before it. */
    private fun resolves(
        candidate: ReconciliationSignal,
        openType: MotionActivityType,
        trackedTypes: Set<MotionActivityType>,
        interrupterTypes: Set<MotionActivityType>,
    ): Boolean = when (candidate) {
        is ReconciliationSignal.Transition -> when {
            candidate.activityType == openType && !candidate.isEnter -> true // matching EXIT
            candidate.activityType in trackedTypes && candidate.activityType != openType && candidate.isEnter -> true // switch
            candidate.activityType in interrupterTypes && candidate.isEnter -> true // interrupter
            else -> false
        }
        is ReconciliationSignal.SampledInterrupt -> true
    }

    /** Shared with [com.example.stepsplit.data.local.motion.ActivityIntervalEntity.closedReason] and the repository's own discontinuity-handling code. */
    object ClosedReason {
        const val OPEN = "OPEN"
        const val OWN_EXIT = "OWN_EXIT"
        const val INTERRUPTED_BY_CONFLICT = "INTERRUPTED_BY_CONFLICT"
        const val CLOSED_AT_DISCONTINUITY = "CLOSED_AT_DISCONTINUITY"
    }
}
