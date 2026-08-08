package com.example.stepsplit.domain.validation

/** A simple half-open `[startEpochSecond, endEpochSecondExclusive)` interval - never enforces non-degenerate bounds, unlike `domain.aggregation.EpochInterval`, since a zero-width guard-expanded point veto must stay representable. */
private data class Span(val start: Long, val end: Long)

/**
 * Pure, no-Android decision engine turning one [RawObservation] plus its relevant, already-fetched
 * evidence into a [ValidationDecision]. No Room/database access, no I/O, no wall-clock reads of its
 * own - every timestamp it needs (including "now") is passed in explicitly, exactly like
 * [com.example.stepsplit.domain.classification.WalkClassifier].
 *
 * This class does **not** claim mathematical certainty about what "really" happened - no automated
 * classifier can. It implements a conservative, fail-closed policy: a step counts only once the
 * complete observation is covered by stable positive `WALKING`/`RUNNING` evidence with no vehicle or
 * bicycle veto anywhere - never merely because a timeout expired or nothing bad was detected. See
 * the 11 numbered rules in the product requirement this implements 1:1 in [evaluate].
 *
 * **State machine** (see [ValidationState]):
 * - `REJECTED_VEHICLE`, `REJECTED_BICYCLE`, `LEGACY_UNVERIFIED` are **terminal forever** - `evaluate`
 *   returns them completely unchanged, without even looking at the new evidence. A vehicle/bicycle
 *   veto is a positive, active safety decision; nothing may silently reopen it (rule 11, generalized
 *   to cover a future policy retune too - see [POLICY_VERSION]'s own doc comment).
 * - `PENDING` and `REJECTED_UNVERIFIED` both run the full evaluation below - the only difference
 *   between them is *when* a caller invokes this function for each (a `REJECTED_UNVERIFIED` row is
 *   only ever re-offered to `evaluate` by the rare, explicit [POLICY_VERSION]-bump maintenance pass,
 *   never by routine per-evidence revalidation - see [com.example.stepsplit.data.repository.StepRepository]),
 *   not anything `evaluate` itself special-cases.
 * - `ACCEPTED_WALKING`/`ACCEPTED_RUNNING` also run the full evaluation - a delayed vehicle/bicycle
 *   veto revokes it (rule 10) exactly like a fresh `PENDING` decision would produce one, but losing
 *   *positive* coverage (a delayed-but-earlier `EXIT`/interrupt shortened the interval that had
 *   justified acceptance) produces an immediate `REJECTED_UNVERIFIED`/`REVOKED_NO_POSITIVE_COVERAGE`
 *   - never a fresh `PENDING` grace period, since that grace period was already spent once.
 */
object StrictStepValidationPolicy {
    /**
     * Bumped whenever the rules below change in a way that could produce a different decision from
     * the same evidence. A version bump forces re-evaluation of every `PENDING`/`REJECTED_UNVERIFIED`
     * row (mirroring [com.example.stepsplit.domain.classification.CLASSIFIER_VERSION]'s existing
     * precedent) - but deliberately never `REJECTED_VEHICLE`/`REJECTED_BICYCLE`/`ACCEPTED_*` rows, so
     * a future retune can never silently un-reject a real vehicle detection or silently strip
     * already-verified real steps.
     */
    const val POLICY_VERSION = 1

    fun evaluate(
        observation: RawObservation,
        overlappingVehicleIntervals: List<MaterializedInterval>,
        overlappingPositiveIntervals: List<MaterializedInterval>,
        sampledEvidence: List<MotionEvidenceEvent>,
        currentEpoch: Long,
        nowEpochSecond: Long,
        previousState: ValidationState,
        constants: ValidationConstants = ValidationConstants.DEFAULT,
    ): ValidationDecision {
        if (previousState == ValidationState.REJECTED_VEHICLE ||
            previousState == ValidationState.REJECTED_BICYCLE ||
            previousState == ValidationState.LEGACY_UNVERIFIED
        ) {
            return ValidationDecision(previousState, 0L, null)
        }

        val observationSpan = Span(observation.startEpochSecond, observation.endEpochSecondExclusive)

        // ---- Veto construction (rules 1-4) ----
        val vehicleVeto = buildVetoIntervals(
            MotionActivityType.IN_VEHICLE, overlappingVehicleIntervals, sampledEvidence, nowEpochSecond, constants,
        )
        val bicycleVeto = buildVetoIntervals(
            MotionActivityType.ON_BICYCLE, overlappingVehicleIntervals, sampledEvidence, nowEpochSecond, constants,
        )
        if (vehicleVeto.any { overlaps(it, observationSpan) }) {
            return ValidationDecision(ValidationState.REJECTED_VEHICLE, 0L, RejectionReason.VEHICLE_VETO)
        }
        if (bicycleVeto.any { overlaps(it, observationSpan) }) {
            return ValidationDecision(ValidationState.REJECTED_BICYCLE, 0L, RejectionReason.BICYCLE_VETO)
        }

        // ---- Positive coverage (rules 5-8) ----
        val walkingCoverage = buildPositiveCoverage(
            MotionActivityType.WALKING, overlappingPositiveIntervals, sampledEvidence, currentEpoch, nowEpochSecond, constants,
        )
        val runningCoverage = buildPositiveCoverage(
            MotionActivityType.RUNNING, overlappingPositiveIntervals, sampledEvidence, currentEpoch, nowEpochSecond, constants,
        )
        val allPositive = mergeSpans(walkingCoverage + runningCoverage)
        val fullyCovered = allPositive.any { it.start <= observationSpan.start && it.end >= observationSpan.end }

        if (fullyCovered) {
            val walkingDuration = overlapDuration(walkingCoverage, observationSpan)
            val runningDuration = overlapDuration(runningCoverage, observationSpan)
            val classification = if (runningDuration > walkingDuration) ValidationState.ACCEPTED_RUNNING else ValidationState.ACCEPTED_WALKING
            return ValidationDecision(classification, observation.rawSteps, null)
        }

        // Not fully covered - an already-ACCEPTED bucket loses its justification immediately (no
        // grace period; it already had one), a fresh PENDING/REJECTED_UNVERIFIED bucket gets the
        // ordinary timeout treatment.
        if (previousState == ValidationState.ACCEPTED_WALKING || previousState == ValidationState.ACCEPTED_RUNNING) {
            return ValidationDecision(ValidationState.REJECTED_UNVERIFIED, 0L, RejectionReason.REVOKED_NO_POSITIVE_COVERAGE)
        }

        val secondsSinceEnd = nowEpochSecond - observation.endEpochSecondExclusive
        return if (secondsSinceEnd < constants.pendingFinalizationDelaySeconds) {
            ValidationDecision(ValidationState.PENDING, 0L, null)
        } else {
            ValidationDecision(ValidationState.REJECTED_UNVERIFIED, 0L, RejectionReason.AWAITING_EVIDENCE_TIMEOUT)
        }
    }

    /** Rules 1-4: every open/closed materialized interval of [type] plus qualifying sampled point vetoes, guard-expanded and merged. */
    private fun buildVetoIntervals(
        type: MotionActivityType,
        materialized: List<MaterializedInterval>,
        sampledEvidence: List<MotionEvidenceEvent>,
        nowEpochSecond: Long,
        constants: ValidationConstants,
    ): List<Span> {
        val fromMaterialized = materialized
            .filter { it.activityType == type }
            .mapNotNull { toSpanSeconds(it, nowEpochSecond, requireCurrentEpoch = false, currentEpoch = it.temporalContinuityEpoch) }

        val batchTopConfidence = sampledEvidence
            .filter { it.batchId != null }
            .groupBy { it.batchId }
            .mapValues { (_, rows) -> rows.maxOf { it.confidence ?: 0 } }
        val fromSampled = sampledEvidence
            .filter { it.kind == MotionEvidenceKind.SAMPLED && it.activityType == type }
            .filter { evt ->
                val confidence = evt.confidence ?: 0
                val isTopInBatch = evt.batchId != null && confidence == batchTopConfidence[evt.batchId]
                isTopInBatch || confidence >= constants.vehicleSampledVetoMinConfidence
            }
            .map { it.wallClockEpochMilli / 1000L }
            .map { pointSeconds -> Span(pointSeconds, pointSeconds + 1) }

        val guarded = (fromMaterialized + fromSampled).map {
            Span(it.start - constants.guardBeforeVehicleSeconds, it.end + constants.guardAfterVehicleSeconds)
        }
        return mergeSpans(guarded)
    }

    /** Rules 5-8: transition-derived open/closed positive intervals (epoch-gated) plus qualifying consecutive sampled runs. */
    private fun buildPositiveCoverage(
        type: MotionActivityType,
        materialized: List<MaterializedInterval>,
        sampledEvidence: List<MotionEvidenceEvent>,
        currentEpoch: Long,
        nowEpochSecond: Long,
        constants: ValidationConstants,
    ): List<Span> {
        val fromMaterialized = materialized
            .filter { it.activityType == type }
            .mapNotNull { toSpanSeconds(it, nowEpochSecond, requireCurrentEpoch = true, currentEpoch = currentEpoch) }
            .map { Span(it.start + constants.walkingStabilitySeconds, it.end) }
            .filter { it.end > it.start }

        val samples = sampledEvidence
            .filter { it.kind == MotionEvidenceKind.SAMPLED && it.activityType == type && it.temporalContinuityEpoch == currentEpoch }
            .filter { (it.confidence ?: 0) >= constants.sampledPositiveMinConfidence }
            .sortedBy { it.wallClockEpochMilli }

        val fromSampled = mutableListOf<Span>()
        var runStart = 0
        for (i in 1 until samples.size) {
            val gapSeconds = (samples[i].wallClockEpochMilli - samples[i - 1].wallClockEpochMilli) / 1000L
            if (gapSeconds > constants.sampledPositiveMaxGapSeconds) {
                if (i - runStart >= constants.sampledPositiveMinConsecutive) {
                    fromSampled += Span(samples[runStart + 1].wallClockEpochMilli / 1000L, samples[i - 1].wallClockEpochMilli / 1000L)
                }
                runStart = i
            }
        }
        if (samples.isNotEmpty() && samples.size - runStart >= constants.sampledPositiveMinConsecutive) {
            fromSampled += Span(samples[runStart + 1].wallClockEpochMilli / 1000L, samples.last().wallClockEpochMilli / 1000L)
        }

        return mergeSpans(fromMaterialized + fromSampled)
    }

    /** Converts a [MaterializedInterval] to seconds; an open interval only extends to `now` when its own epoch is trustworthy (unconditionally for a veto, only same-epoch for positive coverage - see callers). */
    private fun toSpanSeconds(interval: MaterializedInterval, nowEpochSecond: Long, requireCurrentEpoch: Boolean, currentEpoch: Long): Span? {
        val startSeconds = interval.startWallClockEpochMilli / 1000L
        val endSeconds = if (interval.endWallClockEpochMilli != null) {
            interval.endWallClockEpochMilli / 1000L
        } else {
            if (requireCurrentEpoch && interval.temporalContinuityEpoch != currentEpoch) return null
            nowEpochSecond
        }
        if (endSeconds <= startSeconds) return null
        return Span(startSeconds, endSeconds)
    }

    private fun overlaps(a: Span, b: Span): Boolean = a.start < b.end && a.end > b.start

    private fun overlapDuration(spans: List<Span>, within: Span): Long =
        spans.sumOf { (minOf(it.end, within.end) - maxOf(it.start, within.start)).coerceAtLeast(0) }

    private fun mergeSpans(spans: List<Span>): List<Span> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.start }
        val merged = mutableListOf<Span>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            current = if (next.start <= current.end) Span(current.start, maxOf(current.end, next.end)) else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }
}
