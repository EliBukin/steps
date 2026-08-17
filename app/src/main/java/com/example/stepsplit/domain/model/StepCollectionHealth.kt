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
 * **None of these states may ever be read as "collection is happening right now".** A passive,
 * event-driven background subscription cannot be asked "are you working at this exact moment", only
 * "has a sync ever actually seen a positive sample" - once [SAMPLE_OBSERVED] is reached it stays
 * there for the rest of the process's/app's life even through subsequent successful-but-empty reads
 * (see [com.example.stepsplit.data.stepsource.StepSourceHealthStore.recordReadSuccess]'s own doc
 * comment on [everObservedSample] being monotonic).
 *
 * [STALE] is the one exception to "never invent a stronger state from raw numbers" this enum used to
 * document as an absolute rule - a real, field-verified failure proved that rule too strong: Local
 * Recording kept reporting successful subscribe/read outcomes on a device while its own newest
 * sample was **7 days old**, and the old logic (ignoring `latestSampleEpochSecond` entirely) reported
 * that as indistinguishable from genuinely healthy [SAMPLE_OBSERVED]. The distinction that actually
 * matters is not "how long ago was the latest sample" in general (which genuinely can't separate
 * "hasn't walked yet today" from "acquisition broke") but specifically "has it been so long that
 * *no* ordinary explanation - including a full inactive day - covers it" - [DEFAULT_STALE_THRESHOLD_SECONDS]
 * is deliberately set to clear a full day comfortably (never false-alarming on "hasn't walked yet
 * today") while still catching a multi-day-or-longer gap immediately, and [latestSampleEpochSecond]/
 * [nowEpochSecond] default to `null` (staleness never evaluated) so every existing caller/test is
 * unaffected unless it opts in.
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

    /**
     * Available, no recorded sync failure, at least one sample has ever been observed - but the
     * newest sample is older than [DEFAULT_STALE_THRESHOLD_SECONDS]. This is precisely the gap a
     * plain "was a sync attempt technically successful" signal cannot see - see this enum's own doc
     * comment for the real-device failure that motivated adding it.
     */
    STALE,
}

/** See [StepCollectionHealth.STALE]'s own doc comment for why this specific value was chosen. */
const val DEFAULT_STALE_THRESHOLD_SECONDS: Long = 48L * 3600L

/**
 * Pure combination of the independent signals that together decide [StepCollectionHealth] - kept as
 * a free function (rather than inlined into a ViewModel) so every branch can be pinned down by a
 * plain unit test with no Android/Robolectric dependency. Returns null only when [availability]
 * itself hasn't been determined yet (mirrors the existing "checking..." state already shown while
 * [availability] is null), so callers never have to invent a placeholder health value for that case.
 *
 * A recorded [syncFailure] always wins over [everObservedSample] - collection is not actually
 * healthy right now regardless of what happened in the past, mirroring how [SyncFailure] already
 * takes priority over availability elsewhere (see the existing "Data collection status" section in
 * Settings). Only once there is no such failure does whether a sample has ever been observed decide
 * between [StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE] and [StepCollectionHealth.SAMPLE_OBSERVED]/
 * [StepCollectionHealth.STALE] - and even [StepCollectionHealth.SAMPLE_OBSERVED] is historical
 * evidence only, never a claim that collection is working at this exact moment (see that state's own
 * doc comment).
 *
 * [latestSampleEpochSecond]/[nowEpochSecond] both default to `null`, in which case staleness is never
 * evaluated (identical behavior to before [StepCollectionHealth.STALE] existed) - callers that want
 * the staleness check must explicitly pass both.
 */
fun deriveStepCollectionHealth(
    availability: StepSourceAvailability?,
    syncFailure: SyncFailure?,
    everObservedSample: Boolean,
    latestSampleEpochSecond: Long? = null,
    nowEpochSecond: Long? = null,
    staleThresholdSeconds: Long = DEFAULT_STALE_THRESHOLD_SECONDS,
): StepCollectionHealth? {
    if (availability == null) return null
    if (availability !is StepSourceAvailability.Available) return StepCollectionHealth.UNAVAILABLE

    return when (syncFailure?.category) {
        SyncFailureCategory.SUBSCRIPTION_FAILED -> StepCollectionHealth.SUBSCRIPTION_FAILED
        SyncFailureCategory.READ_FAILED, SyncFailureCategory.UNKNOWN -> StepCollectionHealth.READ_FAILED
        null -> when {
            !everObservedSample -> StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE
            isStale(latestSampleEpochSecond, nowEpochSecond, staleThresholdSeconds) -> StepCollectionHealth.STALE
            else -> StepCollectionHealth.SAMPLE_OBSERVED
        }
    }
}

private fun isStale(latestSampleEpochSecond: Long?, nowEpochSecond: Long?, thresholdSeconds: Long): Boolean {
    if (latestSampleEpochSecond == null || nowEpochSecond == null) return false
    return nowEpochSecond - latestSampleEpochSecond > thresholdSeconds
}
