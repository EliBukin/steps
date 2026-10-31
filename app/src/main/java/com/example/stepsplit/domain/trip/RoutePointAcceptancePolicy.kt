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

    /** Read-time only: one of up to three fixes that jump away from the path and back, faster than the phone says it moved - see [RouteSanitizer]. */
    OUT_AND_BACK_EXCURSION,
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
 *
 * ## Driving
 *
 * Each fix is compared only with the last *accepted* one, so an on-foot speed cap alone would stall
 * a trip recorded in a car or 4x4 the moment it went faster than a run: every later fix would be
 * rejected until the vehicle slowed down, and the route would then jump in a straight line. A
 * reported speed above the on-foot cap on either fix switches that pair to
 * [RouteMovementPlausibility]'s vehicle regime instead - see its own doc comment.
 *
 * ## After a gap: a new stretch
 *
 * The same comparison stalls after a gap: a Resume once the phone has travelled (driven to the
 * next trailhead), or a bus, train or cable car ride with no usable fix. The first fix where the
 * owner walks on is far from the last kept one, the gap's distance over its time is faster than
 * anyone walks, and every later fix is rejected the same way - for about an hour after a 20 km
 * drive - so that part of the walk is lost.
 *
 * So a fix rejected only for being too fast on foot ([RouteSampleRejectionReason.IMPLAUSIBLE_JUMP]
 * or [RouteSampleRejectionReason.SPEED_CONTRADICTION]) starts a new stretch instead when
 * [mayStartNewStretch] - more than [NEW_STRETCH_MIN_GAP_SECONDS] after the last kept fix, and at
 * most [NEW_STRETCH_MAX_ACCURACY_METERS] accurate - and the fix after it agrees ([confirmsNewStretch]):
 * an ordinary move on from it, within [NEW_STRETCH_MIN_GAP_SECONDS]. A spike seconds after a kept
 * fix (the A55 defect) is never a new stretch, and neither is one wild fix after a quiet minute
 * that the next fix doesn't follow. The jump itself is drawn as a gap (gray and dashed, see
 * [RouteSpeedColoring.isGap]), and its straight line counts in the distance, as every gap's does.
 *
 * Live, the first fix waits in memory for the next one (see
 * [com.example.stepsplit.data.trip.TripRepository.recordAcceptedBatch]); read back, [RouteSanitizer]
 * looks ahead to the next stored point - the same pair, so both keep the same fixes.
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

    /**
     * Longer than this since the last kept fix is a gap after which a fix may start a new stretch -
     * see the class doc comment's "After a gap" section. The same minute [RouteSmoother] splits a
     * route at, and [RouteSpeedColoring.isGap] draws as a gap.
     */
    const val NEW_STRETCH_MIN_GAP_SECONDS = 60L

    /** The fix that starts a new stretch must be at least this accurate: a good fix, not just an acceptable one ([MAX_ACCURACY_METERS]). */
    const val NEW_STRETCH_MAX_ACCURACY_METERS = 20f

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
                    fromReportedSpeedMetersPerSecond = lastAccepted.speedMetersPerSecond,
                    toReportedSpeedMetersPerSecond = candidate.speedMetersPerSecond,
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

    /**
     * Whether a fix rejected for [reason] against the last kept fix, [elapsedSeconds] after it and
     * [accuracyMeters] accurate, may start a new stretch: it does once [confirmsNewStretch]. See the
     * class doc comment's "After a gap" section.
     */
    fun mayStartNewStretch(reason: RouteSampleRejectionReason, elapsedSeconds: Long, accuracyMeters: Float): Boolean =
        (reason == RouteSampleRejectionReason.IMPLAUSIBLE_JUMP || reason == RouteSampleRejectionReason.SPEED_CONTRADICTION) &&
            elapsedSeconds > NEW_STRETCH_MIN_GAP_SECONDS &&
            accuracyMeters <= NEW_STRETCH_MAX_ACCURACY_METERS

    /**
     * Whether the fix after one that [mayStartNewStretch], [elapsedSeconds] later, confirms it: an
     * [ordinaryMove] on from it ([MovementPlausibility.PLAUSIBLE] against it), not across another gap.
     */
    fun confirmsNewStretch(ordinaryMove: Boolean, elapsedSeconds: Long): Boolean =
        ordinaryMove && elapsedSeconds in 1..NEW_STRETCH_MIN_GAP_SECONDS

    /** Also what [SpotFixRule] asks of a spot's fix. */
    internal fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude >= -90.0 && latitude <= 90.0 &&
            longitude >= -180.0 && longitude <= 180.0 &&
            !(latitude == 0.0 && longitude == 0.0) // the classic "no fix" sentinel some stacks report
}
