package com.example.stepsplit.domain.model

import com.example.stepsplit.data.stepsource.StepSourceAvailability

/**
 * The honest, user-facing acquisition health state - deliberately distinct from both
 * [StepSourceAvailability] (permission/API presence only) and [SyncFailure] (the sync pipeline's
 * own outcome) because neither of those, alone or combined, can tell "subscribed and reading
 * successfully but zero steps ever observed" apart from "genuinely collecting real step data". A
 * successful empty read looks identical to a healthy one under both - this is exactly what let the
 * UI claim "Step collection is active" while the app had never actually observed a single step.
 *
 * **None of these states may ever be read as "collection is happening right now".** The Local
 * Recording API is a passive, event-driven background subscription - there is no way to ask it "are
 * you working at this exact moment", only "has a sync ever actually seen a positive sample". Once
 * [SAMPLE_OBSERVED] is reached it stays there for the rest of the process's/app's life even through
 * an unbounded run of subsequent successful-but-empty reads (see
 * [com.example.stepsplit.data.stepsource.StepSourceHealthStore.recordReadSuccess]'s own doc comment
 * on [everObservedSample] being monotonic) - that is deliberate: the app genuinely cannot tell
 * "the user simply hasn't taken a step yet today" apart from "acquisition silently broke after
 * working once", so it must never claim more confidence than it actually has in either direction.
 * [deriveStepCollectionHealth] therefore intentionally does *not* take `consecutiveEmptyReads` or a
 * "how long ago was the latest sample" staleness signal as an input to distinguish some
 * "recently observed" state from [SAMPLE_OBSERVED] - the raw numbers remain available to a
 * technical user via the debug diagnostics panel
 * ([com.example.stepsplit.data.stepsource.StepSourceHealthSnapshot]), but inventing an ordinary-user
 * facing state on top of them would just be a different flavor of the same overclaiming this exists
 * to prevent.
 */
enum class StepCollectionHealth {
    /** Permission missing, API unavailable, or a Play services problem - see [StepSourceAvailability]. */
    UNAVAILABLE,

    /** Available, but the most recent [SyncFailure] is [SyncFailureCategory.SUBSCRIPTION_FAILED]. */
    SUBSCRIPTION_FAILED,

    /** Available, no recorded failure, but no positive sample has ever been read from the source yet. */
    WAITING_FOR_FIRST_SAMPLE,

    /**
     * Available, no recorded failure, and at least one positive sample has been observed at some
     * point in this source's history. **Historical evidence only** - see this enum's own doc
     * comment for why this must never be presented as "collection is active right now".
     */
    SAMPLE_OBSERVED,

    /** Available, but the most recent [SyncFailure] is a read/unknown failure. */
    READ_FAILED,
}

/**
 * Pure combination of the three independent signals that together decide [StepCollectionHealth] -
 * kept as a free function (rather than inlined into a ViewModel) so every branch can be pinned down
 * by a plain unit test with no Android/Robolectric dependency. Returns null only when [availability]
 * itself hasn't been determined yet (mirrors the existing "checking..." state already shown while
 * [availability] is null), so callers never have to invent a placeholder health value for that case.
 *
 * A recorded [syncFailure] always wins over [everObservedSample] - collection is not actually
 * healthy right now regardless of what happened in the past, mirroring how [SyncFailure] already
 * takes priority over availability elsewhere (see the existing "Data collection status" section in
 * Settings). Only once there is no such failure does whether a sample has ever been observed decide
 * between [StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE] and [StepCollectionHealth.SAMPLE_OBSERVED]
 * - and even then, [StepCollectionHealth.SAMPLE_OBSERVED] is historical evidence only, never a claim
 * that collection is working at this exact moment (see that state's own doc comment).
 */
fun deriveStepCollectionHealth(
    availability: StepSourceAvailability?,
    syncFailure: SyncFailure?,
    everObservedSample: Boolean,
): StepCollectionHealth? {
    if (availability == null) return null
    if (availability !is StepSourceAvailability.Available) return StepCollectionHealth.UNAVAILABLE

    return when (syncFailure?.category) {
        SyncFailureCategory.SUBSCRIPTION_FAILED -> StepCollectionHealth.SUBSCRIPTION_FAILED
        SyncFailureCategory.READ_FAILED, SyncFailureCategory.UNKNOWN -> StepCollectionHealth.READ_FAILED
        null -> if (everObservedSample) StepCollectionHealth.SAMPLE_OBSERVED else StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE
    }
}
