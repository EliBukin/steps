package com.example.stepsplit.domain.trip

/** Whether an A -> B movement looks like something a person - on foot or in a vehicle - could actually have done. */
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
 *
 * ## Two speed regimes: on foot, and in a vehicle
 *
 * The owner also records off-road 4x4 drives, so a fixed on-foot speed cap alone is not enough:
 * live acceptance compares each fix only with the last *accepted* one, so once the device moves
 * faster than [MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND] every following fix is rejected, the route
 * stalls, and it then jumps in a straight line once the vehicle slows down. Which regime applies
 * is decided per A -> B pair, from Android's own reported speed (normally Doppler-based, measured
 * separately from the position) - never from the coordinates themselves, which are exactly what a
 * spike corrupts:
 *
 * - **On foot** (the default): neither fix reports more than [MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND],
 *   or no speed is reported at all. Every check below applies exactly as calibrated against the A55
 *   defect - whose spikes all carried an ordinary walking-pace reported speed (~1.3-1.8 m/s), so
 *   they always stay in this regime.
 * - **Vehicle**: at least one of the two fixes reports more than
 *   [MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND] - see [VEHICLE_REPORTED_SPEED_THRESHOLD_MPS]. The
 *   on-foot cap is replaced by the same reported-speed contradiction ceiling used on foot, computed
 *   from the faster of the two reported speeds and clamped to [MAX_VEHICLE_SPEED_METERS_PER_SECOND].
 *   Either end counts, not just the newer fix: when a vehicle brakes, the newer fix can already
 *   report almost zero while the distance covered since the previous fix was still covered at speed.
 *
 * The vehicle regime is deliberately looser in proportion to speed: at 20 m/s a pair may imply up
 * to 43 m/s, so a sideways spike smaller than the distance covered in one sampling interval cannot
 * be told apart from real movement by speed alone (that would need heading, which is not recorded).
 * A spike whose implied speed clearly exceeds what the reported speed supports is still rejected.
 */
object RouteMovementPlausibility {
    /**
     * ~43 km/h's previous 12 m/s cap let clear GPS-teleport artifacts straight through - the A55
     * defect's worst segments implied 8-11 m/s, comfortably under it. 6.0 m/s instead sits just
     * above elite marathon pace (~5.7 m/s, the fastest pace a human sustains for hours) - generous
     * enough to preserve legitimate brisk walking (~1.5-2 m/s), jogging, and occasional running,
     * including short bursts faster than a walk, while still catching every one of the defect's
     * observed 8-11 m/s segments with 2+ m/s of margin. Applies only in the on-foot regime - see
     * this class's own doc comment.
     */
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 6.0

    /**
     * A reported speed strictly above this, on either fix of a pair, is evidence of riding in a
     * vehicle rather than moving on foot. It is the on-foot cap itself: nobody walks or hikes faster
     * than that, and the A55 defect's spikes reported only ~1.3-1.8 m/s - far below it.
     */
    const val VEHICLE_REPORTED_SPEED_THRESHOLD_MPS = MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND

    /**
     * ~200 km/h - the hard ceiling in the vehicle regime, whatever speed is reported. Well above any
     * road a 4x4 is driven on (a motorway to the trailhead included), so it never cuts a real drive
     * short, while a garbage reported speed can never license an arbitrarily large jump.
     */
    const val MAX_VEHICLE_SPEED_METERS_PER_SECOND = 55.0

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
     * finiteness guarantee). [fromReportedSpeedMetersPerSecond] / [toReportedSpeedMetersPerSecond]
     * are Android's reported speeds at A and B, `null` where the fix carried none; only the newer
     * fix's speed feeds the wobble veto and the on-foot contradiction check, while either one can
     * select the vehicle regime (see this class's own doc comment).
     */
    fun evaluate(
        distanceMeters: Double,
        elapsedSeconds: Long,
        fromAccuracyMeters: Float,
        toAccuracyMeters: Float,
        fromReportedSpeedMetersPerSecond: Float?,
        toReportedSpeedMetersPerSecond: Float?,
    ): MovementPlausibility {
        require(elapsedSeconds > 0) { "elapsedSeconds must be positive - the caller must enforce chronological order first" }
        val impliedSpeed = distanceMeters / elapsedSeconds

        val movesFasterThanWobble =
            toReportedSpeedMetersPerSecond != null && toReportedSpeedMetersPerSecond > STATIONARY_WOBBLE_MAX_REPORTED_SPEED_MPS
        val withinAccuracyNoiseFloor =
            distanceMeters <= (fromAccuracyMeters + toAccuracyMeters) * STATIONARY_WOBBLE_ACCURACY_FRACTION
        if (impliedSpeed <= STATIONARY_WOBBLE_MAX_IMPLIED_SPEED_MPS && withinAccuracyNoiseFloor && !movesFasterThanWobble) {
            return MovementPlausibility.STATIONARY_WOBBLE
        }

        val vehicleSpeed = vehicleReportedSpeed(fromReportedSpeedMetersPerSecond, toReportedSpeedMetersPerSecond)
        if (vehicleSpeed != null) {
            if (impliedSpeed > MAX_VEHICLE_SPEED_METERS_PER_SECOND) {
                return MovementPlausibility.IMPLAUSIBLE_JUMP
            }
            if (impliedSpeed > contradictionCeiling(vehicleSpeed)) {
                return MovementPlausibility.SPEED_CONTRADICTION
            }
            return MovementPlausibility.PLAUSIBLE
        }

        if (impliedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
            return MovementPlausibility.IMPLAUSIBLE_JUMP
        }

        if (toReportedSpeedMetersPerSecond != null && toReportedSpeedMetersPerSecond >= 0f) {
            if (impliedSpeed > contradictionCeiling(toReportedSpeedMetersPerSecond)) {
                return MovementPlausibility.SPEED_CONTRADICTION
            }
        }

        return MovementPlausibility.PLAUSIBLE
    }

    /** The faster of the two reported speeds if it is vehicle evidence (finite and above [VEHICLE_REPORTED_SPEED_THRESHOLD_MPS]), else `null` - the on-foot regime. */
    private fun vehicleReportedSpeed(fromReported: Float?, toReported: Float?): Float? =
        listOfNotNull(fromReported, toReported)
            .filter { it.isFinite() && it > VEHICLE_REPORTED_SPEED_THRESHOLD_MPS }
            .maxOrNull()

    private fun contradictionCeiling(reportedSpeedMetersPerSecond: Float): Double =
        reportedSpeedMetersPerSecond * REPORTED_SPEED_CONTRADICTION_MULTIPLIER + REPORTED_SPEED_CONTRADICTION_MARGIN_MPS
}
