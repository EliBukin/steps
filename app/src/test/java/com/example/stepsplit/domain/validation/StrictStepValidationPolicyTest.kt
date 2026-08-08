package com.example.stepsplit.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictStepValidationPolicyTest {

    private val obsStart = 10_000L
    private val obsEnd = 10_060L // one-minute observation, matches production bucket granularity
    private val observation = RawObservation(obsStart, obsEnd, rawSteps = 80)
    private val epoch = 1L

    private fun materialized(type: MotionActivityType, start: Long, end: Long?, epoch: Long = this.epoch) =
        MaterializedInterval(type, start * 1000, end?.times(1000), epoch)

    private fun sampled(type: MotionActivityType, atSeconds: Long, confidence: Int, batchId: String? = null, epoch: Long = this.epoch) =
        MotionEvidenceEvent(MotionEvidenceKind.SAMPLED, type, confidence, atSeconds * 1000, epoch, batchId)

    private fun evaluate(
        vehicle: List<MaterializedInterval> = emptyList(),
        positive: List<MaterializedInterval> = emptyList(),
        sampledEvidence: List<MotionEvidenceEvent> = emptyList(),
        now: Long = obsEnd,
        previousState: ValidationState = ValidationState.PENDING,
    ) = StrictStepValidationPolicy.evaluate(observation, vehicle, positive, sampledEvidence, epoch, now, previousState)

    // ---- 1. Raw vehicle-only steps produce zero accepted steps ----
    @Test
    fun `vehicle interval covering the observation rejects it with zero accepted steps`() {
        val result = evaluate(vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 100, obsEnd + 100)))
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
        assertEquals(0L, result.acceptedSteps)
        assertEquals(RejectionReason.VEHICLE_VETO, result.rejectionReason)
    }

    // ---- 2. WALKING and IN_VEHICLE overlapping produces zero accepted steps ----
    @Test
    fun `overlapping WALKING and IN_VEHICLE still rejects - vehicle always wins`() {
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 100, obsEnd + 100)),
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 100, obsEnd + 100)),
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    // ---- 5. Steps inside the vehicle guard windows are rejected ----
    @Test
    fun `an observation just inside the guard window before a vehicle interval is rejected`() {
        // Vehicle interval starts 20s after the observation ends - inside the default 30s guard.
        val result = evaluate(vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsEnd + 20, obsEnd + 500)))
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
    }

    @Test
    fun `an observation safely outside the guard window is not vetoed by a distant vehicle interval`() {
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsEnd + 100, obsEnd + 500)),
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 100, obsEnd + 100)),
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
    }

    // ---- 6. Walking after vehicle EXIT accepted only after exit guard + stable positive evidence ----
    @Test
    fun `walking immediately after a vehicle exit is rejected until past the exit guard`() {
        val vehicleExitAt = obsStart - 10 // vehicle ended 10s before the observation - inside the 30s guard
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 500, vehicleExitAt)),
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 100)),
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
    }

    @Test
    fun `walking well past the exit guard, with stability satisfied, is accepted`() {
        val vehicleExitAt = obsStart - 100 // outside the 30s guard
        val walkingEnterAt = obsStart - 50 // stability (15s) satisfied well before the observation starts
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 1000, vehicleExitAt)),
            positive = listOf(materialized(MotionActivityType.WALKING, walkingEnterAt, obsEnd + 100)),
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
        assertEquals(observation.rawSteps, result.acceptedSteps)
    }

    // ---- 7. A bucket spanning walking and vehicle time is rejected completely ----
    @Test
    fun `a bucket whose span is only partly covered by a vehicle interval is rejected wholly`() {
        // Vehicle interval overlaps only the tail of the observation.
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsEnd - 5, obsEnd + 500)),
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    // ---- 8. STILL, UNKNOWN, missing evidence never approve ----
    @Test
    fun `no evidence at all remains PENDING before the finalization deadline, then REJECTED_UNVERIFIED`() {
        val pending = evaluate(now = obsEnd + 60)
        assertEquals(ValidationState.PENDING, pending.state)

        val timedOut = evaluate(now = obsEnd + 121)
        assertEquals(ValidationState.REJECTED_UNVERIFIED, timedOut.state)
        assertEquals(RejectionReason.AWAITING_EVIDENCE_TIMEOUT, timedOut.rejectionReason)
        assertEquals(0L, timedOut.acceptedSteps)
    }

    @Test
    fun `STILL evidence alone never approves a bucket`() {
        val result = evaluate(
            positive = emptyList(),
            sampledEvidence = listOf(sampled(MotionActivityType.STILL, obsStart, 90)),
            now = obsEnd + 200,
        )
        assertEquals(ValidationState.REJECTED_UNVERIFIED, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    // ---- 9. Two (or more) stable walking samples approve an eligible bucket ----
    @Test
    fun `a run of consecutive qualifying sampled WALKING results, ~15s apart, approves a bucket they fully bracket`() {
        // Coverage is [secondSample, lastSample] (rule 8: never extends past the run's own last
        // sample) - to fully cover a 60s observation the run itself must span at least that width,
        // which requires more than the bare minimum of two ~15s-apart samples.
        val result = evaluate(
            sampledEvidence = listOf(
                sampled(MotionActivityType.WALKING, obsStart - 15, 70),
                sampled(MotionActivityType.WALKING, obsStart, 70),
                sampled(MotionActivityType.WALKING, obsStart + 15, 70),
                sampled(MotionActivityType.WALKING, obsStart + 30, 70),
                sampled(MotionActivityType.WALKING, obsStart + 45, 70),
                sampled(MotionActivityType.WALKING, obsEnd + 5, 70),
            ),
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
        assertEquals(observation.rawSteps, result.acceptedSteps)
    }

    @Test
    fun `exactly two qualifying samples produce only a degenerate coverage point, never enough to cover a real bucket`() {
        // Coverage for a run of exactly two samples is [secondSample, lastSampleInRun] - the same
        // instant, since the second sample IS the last one in a two-sample run. A real,
        // positive-duration observation (every production bucket is at least a minute wide) can
        // therefore never be fully covered by exactly two samples alone, no matter how they're
        // positioned - proving coverage never silently widens beyond what the samples themselves prove.
        val result = evaluate(
            sampledEvidence = listOf(
                sampled(MotionActivityType.WALKING, obsStart - 15, 70),
                sampled(MotionActivityType.WALKING, obsStart, 70),
            ),
            now = obsEnd + 200,
        )
        assertEquals(
            "two samples alone must never cover a full-width bucket - see the run-of-six test above for what actually does",
            ValidationState.REJECTED_UNVERIFIED,
            result.state,
        )
    }

    @Test
    fun `one high-confidence sampled WALKING result alone never accepts a bucket`() {
        val result = evaluate(
            sampledEvidence = listOf(sampled(MotionActivityType.WALKING, obsStart - 5, 95)),
            now = obsEnd + 200,
        )
        assertEquals(
            "a single sample must never satisfy the >=2-consecutive-sample rule",
            ValidationState.REJECTED_UNVERIFIED,
            result.state,
        )
    }

    @Test
    fun `sampled coverage never extends past the last sample in its run`() {
        // Two qualifying samples end well before the observation even starts - coverage stops there.
        val result = evaluate(
            sampledEvidence = listOf(
                sampled(MotionActivityType.WALKING, obsStart - 200, 70),
                sampled(MotionActivityType.WALKING, obsStart - 185, 70),
            ),
            now = obsEnd + 200,
        )
        assertEquals(ValidationState.REJECTED_UNVERIFIED, result.state)
    }

    // ---- 10. A vehicle sample wins over a simultaneous walking sample ----
    @Test
    fun `a sampled batch with WALKING ranked first but IN_VEHICLE at confidence 50 or more still vetoes`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 100, obsEnd + 100)),
            sampledEvidence = listOf(
                sampled(MotionActivityType.WALKING, obsStart + 10, confidence = 90, batchId = "batch-1"),
                sampled(MotionActivityType.IN_VEHICLE, obsStart + 10, confidence = 55, batchId = "batch-1"),
            ),
        )
        assertEquals(
            "top-rank alone must never override the confidence>=50 veto rule",
            ValidationState.REJECTED_VEHICLE,
            result.state,
        )
    }

    @Test
    fun `a sampled IN_VEHICLE below 50 confidence and not top-of-batch does not veto`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 100, obsEnd + 100)),
            sampledEvidence = listOf(
                sampled(MotionActivityType.WALKING, obsStart + 10, confidence = 90, batchId = "batch-2"),
                sampled(MotionActivityType.IN_VEHICLE, obsStart + 10, confidence = 20, batchId = "batch-2"),
            ),
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
    }

    // ---- Terminal states ----
    @Test
    fun `REJECTED_VEHICLE is terminal - never re-evaluated even with strong new positive evidence`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            previousState = ValidationState.REJECTED_VEHICLE,
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    @Test
    fun `LEGACY_UNVERIFIED is terminal - never re-evaluated`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            previousState = ValidationState.LEGACY_UNVERIFIED,
        )
        assertEquals(ValidationState.LEGACY_UNVERIFIED, result.state)
    }

    // ---- ACCEPTED revocation when positive coverage is later invalidated (review-4 point 2) ----
    @Test
    fun `an accepted bucket is revoked immediately, not given a new PENDING grace period, when coverage is lost`() {
        val result = evaluate(
            positive = emptyList(), // the positive interval that had justified acceptance is now gone
            now = obsEnd + 1, // well within what would normally still be a PENDING grace window
            previousState = ValidationState.ACCEPTED_WALKING,
        )
        assertEquals(ValidationState.REJECTED_UNVERIFIED, result.state)
        assertEquals(RejectionReason.REVOKED_NO_POSITIVE_COVERAGE, result.rejectionReason)
        assertEquals(0L, result.acceptedSteps)
    }

    @Test
    fun `an accepted bucket is revoked when delayed vehicle evidence arrives`() {
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 10, obsEnd + 10)),
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            previousState = ValidationState.ACCEPTED_WALKING,
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    @Test
    fun `an accepted bucket remains accepted when re-evaluated with unchanged full coverage`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            previousState = ValidationState.ACCEPTED_WALKING,
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
        assertEquals(observation.rawSteps, result.acceptedSteps)
    }

    // ---- Zero/large-value safety ----
    @Test
    fun `zero raw steps still produce a valid decision without crashing`() {
        val zeroObservation = RawObservation(obsStart, obsEnd, rawSteps = 0)
        val result = StrictStepValidationPolicy.evaluate(
            zeroObservation,
            emptyList(),
            listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            emptyList(),
            epoch,
            obsEnd,
            ValidationState.PENDING,
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
        assertEquals(0L, result.acceptedSteps)
    }

    @Test
    fun `a very large raw step count is preserved exactly when accepted`() {
        val hugeObservation = RawObservation(obsStart, obsEnd, rawSteps = Long.MAX_VALUE)
        val result = StrictStepValidationPolicy.evaluate(
            hugeObservation,
            emptyList(),
            listOf(materialized(MotionActivityType.WALKING, obsStart - 500, obsEnd + 500)),
            emptyList(),
            epoch,
            obsEnd,
            ValidationState.PENDING,
        )
        assertEquals(ValidationState.ACCEPTED_WALKING, result.state)
        assertEquals(Long.MAX_VALUE, result.acceptedSteps)
    }

    @Test
    fun `an open positive interval from a stale epoch contributes no coverage`() {
        val result = evaluate(
            positive = listOf(materialized(MotionActivityType.WALKING, obsStart - 500, null, epoch = 0L)),
        )
        assertEquals(
            "a positive interval left open under a previous temporal-continuity epoch must not extend into the current one",
            ValidationState.PENDING,
            result.state,
        )
    }

    @Test
    fun `an open vehicle interval from a stale epoch still vetoes - only a discontinuity closes it, never an ordinary mismatch`() {
        // In practice the repository always force-closes a stale-epoch open interval at the
        // discontinuity boundary before it could ever reach evaluate() with a mismatched epoch, but
        // the policy's own veto construction is defense-in-depth and must still be safe if it did.
        val result = evaluate(
            vehicle = listOf(materialized(MotionActivityType.IN_VEHICLE, obsStart - 500, null, epoch = 0L)),
        )
        assertEquals(ValidationState.REJECTED_VEHICLE, result.state)
    }
}
