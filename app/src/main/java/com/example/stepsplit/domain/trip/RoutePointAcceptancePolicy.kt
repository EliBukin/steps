package com.example.stepsplit.domain.trip

/** One GPS fix before acceptance/rejection - independent of `android.location.Location` so the policy stays a plain, JVM-testable function of its inputs. */
data class RawLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochSecond: Long,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
)

enum class RouteSampleRejectionReason {
    INVALID_COORDINATES,
    NON_FINITE_MEASUREMENT,
    POOR_ACCURACY,
    NON_MONOTONIC_OR_DUPLICATE,
    STALE_SAMPLE,
    STATIONARY_WOBBLE,
    IMPLAUSIBLE_JUMP,
    SPEED_CONTRADICTION,
}

sealed interface RouteSampleDecision {
    data class Accepted(val sample: RawLocationSample) : RouteSampleDecision
    data class Rejected(val reason: RouteSampleRejectionReason) : RouteSampleDecision
}

/**
 * Decides whether one raw GPS fix should become a durable [com.example.stepsplit.data.local.trip.TripPointEntity].
 * A pure function of its three inputs (no clock, no I/O) so every boundary is exactly reproducible
 * in a unit test. Deliberately conservative in the *other* direction too: an ordinary imperfect fix
 * (moderate accuracy, a slightly wobbly but plausible walking-speed jump) must still be accepted -
 * only fixes with a concrete, specific problem are rejected.
 *
 * ## Background: the A55 field defect this hardens against
 *
 * A real ~21-minute recorded walk stored ~3,514.3m of distance, while integrating Android's own
 * reported instantaneous speed over the same points gives only ~2,134.8m - a ~1,380m (65%)
 * overstatement. Twelve accepted segments implied over 5 m/s and six implied over 8-11 m/s, while
 * Android reported only ~1.3-1.8 m/s during those exact segments - a clear, consistent ~6x
 * contradiction between "how far the coordinates jumped" and "how fast the device says it was
 * actually moving." The previous thresholds here (50m accuracy, 12 m/s speed cap, no reported-speed
 * cross-check at all, no stationary-wobble suppression) were far too permissive to catch this.
 *
 * Fixing this is deliberately two-layered: this policy stops *future* bad points from ever being
 * accepted/stored/added to distance - see [MAX_ACCURACY_METERS] and
 * [RouteMovementPlausibility]'s own doc comments for the evidence-based per-point and per-pair
 * thresholds, which [RouteSanitizer] references directly rather than duplicating, so a fix that
 * would be rejected live is rejected the same way when read back later too. [RouteSanitizer]
 * separately cleans *already-stored* points (including this exact historical trip)
 * non-destructively at read time, and - because it can see the whole recorded sequence rather than
 * one incoming fix at a time - also performs a contextual look-ahead reconsideration this policy
 * structurally cannot; see [RouteSanitizer]'s own doc comment for what is genuinely shared between
 * the two layers versus unique to read-time cleanup, and `RouteSanitizerTest` for the end-to-end
 * proof.
 */
object RoutePointAcceptancePolicy {
    /**
     * Android's accuracy is a 68% confidence radius in metres. The previous 50m limit accepted
     * fixes bad enough to be dominated by their own error margin - a poor fix combined with
     * completely ordinary movement can *by itself* imply an extra several-metres-per-second jump
     * purely from position uncertainty, without the device having moved that fast at all. Hiking
     * trail GPS commonly reports 5-30m under open sky (degrading further under tree canopy or
     * between buildings); 30m is the upper edge of that normal-open-sky range, so it still accepts
     * ordinary outdoor noise and brief, moderately degraded conditions (a short tree-canopy patch, a
     * turn near a building) while rejecting fixes bad enough that their *own* uncertainty could
     * account for a walking-scale "jump."
     */
    const val MAX_ACCURACY_METERS = 30f

    /** A fix time-stamped further in the past than this relative to when it was actually processed is stale - most plausibly a delayed/batched delivery of an old cached fix, not a live position update. */
    const val MAX_SAMPLE_AGE_SECONDS = 120L

    fun evaluate(
        candidate: RawLocationSample,
        lastAccepted: RawLocationSample?,
        nowEpochSecond: Long,
    ): RouteSampleDecision {
        if (!isValidCoordinate(candidate.latitude, candidate.longitude)) {
            return RouteSampleDecision.Rejected(RouteSampleRejectionReason.INVALID_COORDINATES)
        }
        if (!candidate.accuracyMeters.isFinite() || candidate.speedMetersPerSecond?.isFinite() == false) {
            return RouteSampleDecision.Rejected(RouteSampleRejectionReason.NON_FINITE_MEASUREMENT)
        }
        if (candidate.accuracyMeters <= 0f || candidate.accuracyMeters > MAX_ACCURACY_METERS) {
            return RouteSampleDecision.Rejected(RouteSampleRejectionReason.POOR_ACCURACY)
        }
        if (nowEpochSecond - candidate.capturedAtEpochSecond > MAX_SAMPLE_AGE_SECONDS) {
            return RouteSampleDecision.Rejected(RouteSampleRejectionReason.STALE_SAMPLE)
        }
        if (lastAccepted != null) {
            val elapsedSeconds = candidate.capturedAtEpochSecond - lastAccepted.capturedAtEpochSecond
            if (elapsedSeconds <= 0) {
                return RouteSampleDecision.Rejected(RouteSampleRejectionReason.NON_MONOTONIC_OR_DUPLICATE)
            }
            val distanceMeters = RouteMath.haversineMeters(
                lastAccepted.latitude,
                lastAccepted.longitude,
                candidate.latitude,
                candidate.longitude,
            )
            when (
                RouteMovementPlausibility.evaluate(
                    distanceMeters = distanceMeters,
                    elapsedSeconds = elapsedSeconds,
                    fromAccuracyMeters = lastAccepted.accuracyMeters,
                    toAccuracyMeters = candidate.accuracyMeters,
                    reportedSpeedMetersPerSecond = candidate.speedMetersPerSecond,
                )
            ) {
                MovementPlausibility.STATIONARY_WOBBLE -> return RouteSampleDecision.Rejected(RouteSampleRejectionReason.STATIONARY_WOBBLE)
                MovementPlausibility.IMPLAUSIBLE_JUMP -> return RouteSampleDecision.Rejected(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)
                MovementPlausibility.SPEED_CONTRADICTION -> return RouteSampleDecision.Rejected(RouteSampleRejectionReason.SPEED_CONTRADICTION)
                MovementPlausibility.PLAUSIBLE -> Unit
            }
        }
        return RouteSampleDecision.Accepted(candidate)
    }

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude >= -90.0 && latitude <= 90.0 &&
            longitude >= -180.0 && longitude <= 180.0 &&
            !(latitude == 0.0 && longitude == 0.0) // the classic "no fix" sentinel some stacks report
}
