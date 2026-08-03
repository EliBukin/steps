package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePointAcceptancePolicyTest {

    private fun sample(
        lat: Double = 32.0,
        lon: Double = 34.0,
        accuracy: Float = 10f,
        capturedAt: Long = 1_000L,
    ) = RawLocationSample(lat, lon, accuracy, capturedAt)

    private fun decide(candidate: RawLocationSample, last: RawLocationSample? = null, now: Long = candidate.capturedAtEpochSecond) =
        RoutePointAcceptancePolicy.evaluate(candidate, last, now)

    @Test
    fun `the first point of a trip with no predecessor is accepted`() {
        val decision = decide(sample())
        assertTrue(decision is RouteSampleDecision.Accepted)
    }

    @Test
    fun `invalid coordinates are rejected`() {
        assertEquals(
            RouteSampleRejectionReason.INVALID_COORDINATES,
            (decide(sample(lat = 91.0)) as RouteSampleDecision.Rejected).reason,
        )
        assertEquals(
            RouteSampleRejectionReason.INVALID_COORDINATES,
            (decide(sample(lon = -181.0)) as RouteSampleDecision.Rejected).reason,
        )
        assertEquals(
            RouteSampleRejectionReason.INVALID_COORDINATES,
            (decide(sample(lat = 0.0, lon = 0.0)) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `accuracy exactly at the threshold is accepted, just above it is rejected`() {
        val atThreshold = sample(accuracy = RoutePointAcceptancePolicy.MAX_ACCURACY_METERS)
        assertTrue(decide(atThreshold) is RouteSampleDecision.Accepted)

        val justOver = sample(accuracy = RoutePointAcceptancePolicy.MAX_ACCURACY_METERS + 0.1f)
        assertEquals(RouteSampleRejectionReason.POOR_ACCURACY, (decide(justOver) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `zero or negative accuracy is rejected`() {
        assertEquals(RouteSampleRejectionReason.POOR_ACCURACY, (decide(sample(accuracy = 0f)) as RouteSampleDecision.Rejected).reason)
        assertEquals(RouteSampleRejectionReason.POOR_ACCURACY, (decide(sample(accuracy = -1f)) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `a sample exactly at the staleness threshold is accepted, just beyond it is rejected`() {
        val candidate = sample(capturedAt = 1_000L)
        val atThreshold = candidate.capturedAtEpochSecond + RoutePointAcceptancePolicy.MAX_SAMPLE_AGE_SECONDS
        assertTrue(decide(candidate, now = atThreshold) is RouteSampleDecision.Accepted)

        val justStale = candidate.capturedAtEpochSecond + RoutePointAcceptancePolicy.MAX_SAMPLE_AGE_SECONDS + 1
        assertEquals(
            RouteSampleRejectionReason.STALE_SAMPLE,
            (decide(candidate, now = justStale) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `a duplicate or out-of-order timestamp relative to the last accepted point is rejected`() {
        val last = sample(capturedAt = 1_000L)
        val sameTime = sample(capturedAt = 1_000L)
        assertEquals(
            RouteSampleRejectionReason.NON_MONOTONIC_OR_DUPLICATE,
            (decide(sameTime, last) as RouteSampleDecision.Rejected).reason,
        )

        val earlier = sample(capturedAt = 999L)
        assertEquals(
            RouteSampleRejectionReason.NON_MONOTONIC_OR_DUPLICATE,
            (decide(earlier, last) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `an ordinary walking-pace jump is accepted`() {
        val last = sample(lat = 32.0000, lon = 34.0000, capturedAt = 1_000L)
        // Roughly 14m north over 10 seconds - a brisk but entirely plausible walking pace (~1.4 m/s).
        val next = sample(lat = 32.000126, lon = 34.0000, capturedAt = 1_010L)
        assertTrue(decide(next, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `a speed just under the plausibility threshold is accepted, just over it is rejected`() {
        // Along a meridian (same longitude) haversine distance is an exact function of the
        // latitude delta alone, so this stays a reliable boundary check without depending on
        // sub-ULP floating-point behavior the way asserting bit-exact equality at the threshold
        // would.
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L)
        val elapsedSeconds = 10L

        val justUnderSpeed = RoutePointAcceptancePolicy.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND - 0.5
        val justUnder = sample(
            lat = last.latitude + degreesLatFor(justUnderSpeed * elapsedSeconds),
            lon = last.longitude,
            capturedAt = last.capturedAtEpochSecond + elapsedSeconds,
        )
        assertTrue(decide(justUnder, last) is RouteSampleDecision.Accepted)

        val justOverSpeed = RoutePointAcceptancePolicy.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND + 0.5
        val justOver = sample(
            lat = last.latitude + degreesLatFor(justOverSpeed * elapsedSeconds),
            lon = last.longitude,
            capturedAt = last.capturedAtEpochSecond + elapsedSeconds,
        )
        assertEquals(
            RouteSampleRejectionReason.IMPLAUSIBLE_JUMP,
            (decide(justOver, last) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `a near-antipodal teleport is rejected as implausible, not silently accepted via a poisoned NaN distance`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L)
        // Almost exactly antipodal to `last` - the floating-point edge RouteMath.haversineMeters
        // must clamp, and regardless of that, a jump this large in 10 seconds is never plausible.
        val next = sample(lat = 0.0000001, lon = 179.9999999, capturedAt = 1_010L)
        val decision = decide(next, last)
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decision as RouteSampleDecision.Rejected).reason)
    }

    private fun degreesLatFor(distanceMeters: Double): Double =
        Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
