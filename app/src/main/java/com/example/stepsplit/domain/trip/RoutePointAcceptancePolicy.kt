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
    POOR_ACCURACY,
    NON_MONOTONIC_OR_DUPLICATE,
    STALE_SAMPLE,
    IMPLAUSIBLE_JUMP,
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
 * Thresholds (tunable after real-device testing on an actual hike - see [MAX_ACCURACY_METERS] etc.):
 * - [MAX_ACCURACY_METERS]: Android's accuracy is a 68% confidence radius in metres; hiking trail
 *   GPS commonly reports 5-30m under open sky, degrading further under tree canopy or between
 *   buildings. 50m rejects fixes bad enough to be dominated by their own error margin while still
 *   accepting normal outdoor noise.
 * - [MAX_SAMPLE_AGE_SECONDS]: a fix time-stamped further in the past than this relative to when it
 *   was actually processed is stale - most plausibly a delayed/batched delivery of an old cached
 *   fix, not a live position update.
 * - [MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND]: ~43 km/h - generously above any realistic hiking or
 *   trail-running pace (even trail running rarely exceeds 5 m/s), so it only ever rejects a clear
 *   GPS teleport artifact, never a human's actual movement.
 */
object RoutePointAcceptancePolicy {
    const val MAX_ACCURACY_METERS = 50f
    const val MAX_SAMPLE_AGE_SECONDS = 120L
    const val MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 12.0

    fun evaluate(
        candidate: RawLocationSample,
        lastAccepted: RawLocationSample?,
        nowEpochSecond: Long,
    ): RouteSampleDecision {
        if (!isValidCoordinate(candidate.latitude, candidate.longitude)) {
            return RouteSampleDecision.Rejected(RouteSampleRejectionReason.INVALID_COORDINATES)
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
            val impliedSpeed = distanceMeters / elapsedSeconds
            if (impliedSpeed > MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
                return RouteSampleDecision.Rejected(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)
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
