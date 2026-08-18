package com.example.stepsplit.domain.trip

/** Whether an A -> B movement looks like something a person could actually have done. */
enum class MovementPlausibility { PLAUSIBLE, STATIONARY_WOBBLE, IMPLAUSIBLE_JUMP, SPEED_CONTRADICTION }

/**
 * The shared movement-quality core used by both [RoutePointAcceptancePolicy] (live, per-fix
 * acceptance while a trip is actively recording) and [RouteSanitizer] (post-hoc cleaning of an
 * already-stored route's points, non-destructively, at read time). Both are ultimately answering
 * the same question - "does this A -> B jump look like real human movement?" - just at different
 * points in the pipeline (write-time vs. read-time) and with different surrounding context
 * available (a single incoming fix vs. a whole already-recorded sequence). Centralizing the
 * thresholds here means fixing or retuning this logic never requires touching two places in sync.
 *
 * See [RoutePointAcceptancePolicy]'s own doc comment for the full A55 field-defect background these
 * thresholds were calibrated against.
 */
object RouteMovementPlausibility {
    /**
     * ~43 km/h's previous 12 m/s cap let clear GPS-teleport artifacts straight through - the A55
     * defect's worst segments implied 8-11 m/s, comfortably under it. 6.0 m/s instead sits just
     * above elite marathon pace (~5.7 m/s, the fastest pace a human sustains for hours) - generous
     * enough to preserve legitimate brisk walking (~1.5-2 m/s), jogging, and occasional running,
     * including short bursts faster than a walk, while still catching every one of the defect's
     * observed 8-11 m/s segments with 2+ m/s of margin.
     */
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 6.0

    /**
     * A coordinate-implied jump speed is [MovementPlausibility.SPEED_CONTRADICTION] if it exceeds
     * `reportedSpeed * MULTIPLIER + MARGIN_MPS`, whenever a finite, non-negative reported speed is
     * available. Calibrated directly against the A55 defect: reported speeds of ~1.3-1.8 m/s during
     * the bad segments give a ceiling of `1.3*2+3=5.6` to `1.8*2+3=6.6` m/s - comfortably under the
     * observed 8-11 m/s implied speeds, so every defect segment is caught here even before
     * [MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND] is considered. The multiplier scales tolerance up for
     * genuinely fast segments instead of clamping every jump to one fixed band regardless of pace;
     * the additive margin keeps the check usable near zero reported speed (e.g. a person just
     * starting to walk, before Android's own speed estimate has caught up) without an unreasonably
     * tight ceiling. Android's reported speed is a live Doppler-based estimate, generally more
     * reliable during genuine motion than a two-point position/time derivative over a single short
     * interval - a *large, sustained* contradiction between the two (not just ordinary GPS noise,
     * which this margin already tolerates) is trustworthy evidence of a bad coordinate, not of
     * Android's own speed estimate being wrong.
     */
    const val REPORTED_SPEED_CONTRADICTION_MULTIPLIER = 2.0
    const val REPORTED_SPEED_CONTRADICTION_MARGIN_MPS = 3.0

    /**
     * Suppresses obvious stationary GPS wobble - a person standing still while the fix drifts a few
     * metres between samples purely from receiver noise - using all four available signals:
     * **displacement** and **elapsed time** together (as the implied speed, so a given displacement
     * is judged relative to how long it took, not in isolation), the **accuracy radii** of both
     * fixes (displacement is judged against a *combined-uncertainty noise floor*, not a flat
     * distance, so two very precise fixes get a tighter wobble bar than two imprecise ones), and
     * **reported speed** as a veto: if Android itself reports the device already moving faster than
     * [STATIONARY_WOBBLE_MAX_REPORTED_SPEED_MPS], the sample is never treated as wobble no matter
     * how small its own displacement looks - this is what preserves genuine stop/start movement (the
     * first sample or two after a person starts walking again can still have a small displacement
     * while already reporting real speed). Both the implied-speed and accuracy-noise-floor
     * conditions must hold together, not either alone - deliberately conservative, so only
     * genuinely *obvious* wobble is suppressed, never real, if slow, deliberate movement.
     */
    const val STATIONARY_WOBBLE_MAX_IMPLIED_SPEED_MPS = 0.3
    const val STATIONARY_WOBBLE_ACCURACY_FRACTION = 0.5
    const val STATIONARY_WOBBLE_MAX_REPORTED_SPEED_MPS = 0.3f

    /**
     * [elapsedSeconds] must be strictly positive (the caller is responsible for chronological
     * ordering/duplicate-timestamp handling before ever reaching this). [distanceMeters] is assumed
     * already a finite, non-negative haversine distance (see [RouteMath.haversineMeters]'s own
     * finiteness guarantee).
     */
    fun evaluate(
        distanceMeters: Double,
        elapsedSeconds: Long,
        fromAccuracyMeters: Float,
        toAccuracyMeters: Float,
        reportedSpeedMetersPerSecond: Float?,
    ): MovementPlausibility {
        require(elapsedSeconds > 0) { "elapsedSeconds must be positive - the caller must enforce chronological order first" }
        val impliedSpeed = distanceMeters / elapsedSeconds

        val movesFasterThanWobble =
            reportedSpeedMetersPerSecond != null && reportedSpeedMetersPerSecond > STATIONARY_WOBBLE_MAX_REPORTED_SPEED_MPS
        val withinAccuracyNoiseFloor =
            distanceMeters <= (fromAccuracyMeters + toAccuracyMeters) * STATIONARY_WOBBLE_ACCURACY_FRACTION
        if (impliedSpeed <= STATIONARY_WOBBLE_MAX_IMPLIED_SPEED_MPS && withinAccuracyNoiseFloor && !movesFasterThanWobble) {
            return MovementPlausibility.STATIONARY_WOBBLE
        }

        if (impliedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
            return MovementPlausibility.IMPLAUSIBLE_JUMP
        }

        if (reportedSpeedMetersPerSecond != null && reportedSpeedMetersPerSecond >= 0f) {
            val contradictionCeiling =
                reportedSpeedMetersPerSecond * REPORTED_SPEED_CONTRADICTION_MULTIPLIER + REPORTED_SPEED_CONTRADICTION_MARGIN_MPS
            if (impliedSpeed > contradictionCeiling) {
                return MovementPlausibility.SPEED_CONTRADICTION
            }
        }

        return MovementPlausibility.PLAUSIBLE
    }
}
