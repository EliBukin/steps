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
        speed: Float? = null,
    ) = RawLocationSample(lat, lon, accuracy, capturedAt, speedMetersPerSecond = speed)

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

        val justUnderSpeed = RouteMovementPlausibility.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND - 0.5
        val justUnder = sample(
            lat = last.latitude + degreesLatFor(justUnderSpeed * elapsedSeconds),
            lon = last.longitude,
            capturedAt = last.capturedAtEpochSecond + elapsedSeconds,
        )
        assertTrue(decide(justUnder, last) is RouteSampleDecision.Accepted)

        val justOverSpeed = RouteMovementPlausibility.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND + 0.5
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

    @Test
    fun `reasonable running movement is accepted`() {
        val last = sample(lat = 32.0000, lon = 34.0000, capturedAt = 1_000L)
        // ~35m over 10s = 3.5 m/s - a comfortable jog, well under the plausibility ceiling.
        val next = sample(lat = last.latitude + degreesLatFor(35.0), lon = last.longitude, capturedAt = 1_010L)
        assertTrue(decide(next, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `a plausible jump with no reported speed at all is still accepted safely`() {
        val last = sample(lat = 32.0000, lon = 34.0000, capturedAt = 1_000L)
        val next = sample(lat = last.latitude + degreesLatFor(14.0), lon = last.longitude, capturedAt = 1_010L, speed = null)
        assertTrue(decide(next, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `an implied speed under the raw cap is still rejected when it materially contradicts a low reported speed`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L)
        val elapsedSeconds = 10L
        val impliedSpeed = 5.0 // under MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND (6.0) on its own
        // Reported 0.5 m/s gives a contradiction ceiling of 0.5*2+3=4.0 - well under the 5.0
        // implied speed, so only the reported-speed check (not the raw cap) rejects this.
        val next = sample(
            lat = last.latitude + degreesLatFor(impliedSpeed * elapsedSeconds),
            lon = last.longitude,
            capturedAt = last.capturedAtEpochSecond + elapsedSeconds,
            speed = 0.5f,
        )
        assertEquals(
            RouteSampleRejectionReason.SPEED_CONTRADICTION,
            (decide(next, last) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `the confirmed A55 defect pattern - implied 8-11 mps against reported 1_3-1_8 mps - is rejected`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L)
        val elapsedSeconds = 10L
        for ((impliedSpeed, reportedSpeed) in listOf(8.0 to 1.3f, 11.0 to 1.8f)) {
            val next = sample(
                lat = last.latitude + degreesLatFor(impliedSpeed * elapsedSeconds),
                lon = last.longitude,
                capturedAt = last.capturedAtEpochSecond + elapsedSeconds,
                speed = reportedSpeed,
            )
            assertTrue(
                "implied $impliedSpeed m/s vs reported $reportedSpeed m/s must be rejected",
                decide(next, last) is RouteSampleDecision.Rejected,
            )
        }
    }

    @Test
    fun `non-finite accuracy is rejected`() {
        assertEquals(
            RouteSampleRejectionReason.NON_FINITE_MEASUREMENT,
            (decide(sample(accuracy = Float.NaN)) as RouteSampleDecision.Rejected).reason,
        )
        assertEquals(
            RouteSampleRejectionReason.NON_FINITE_MEASUREMENT,
            (decide(sample(accuracy = Float.POSITIVE_INFINITY)) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `non-finite reported speed is rejected even with otherwise-valid coordinates and accuracy`() {
        val last = sample(lat = 32.0000, lon = 34.0000, capturedAt = 1_000L)
        val next = sample(lat = last.latitude + degreesLatFor(14.0), lon = last.longitude, capturedAt = 1_010L, speed = Float.NaN)
        assertEquals(
            RouteSampleRejectionReason.NON_FINITE_MEASUREMENT,
            (decide(next, last) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `obvious stationary wobble - tiny displacement within combined accuracy, near-zero reported speed - is rejected`() {
        val last = sample(lat = 32.00000, lon = 34.00000, accuracy = 10f, capturedAt = 1_000L)
        // ~1m of drift over 10s (0.1 m/s implied) with tight accuracy on both ends and a near-zero
        // reported speed - textbook GPS noise while stationary, not real movement.
        val next = sample(
            lat = last.latitude + degreesLatFor(1.0),
            lon = last.longitude,
            accuracy = 10f,
            capturedAt = 1_010L,
            speed = 0.1f,
        )
        assertEquals(
            RouteSampleRejectionReason.STATIONARY_WOBBLE,
            (decide(next, last) as RouteSampleDecision.Rejected).reason,
        )
    }

    @Test
    fun `a small displacement is not treated as wobble when reported speed shows genuine movement`() {
        val last = sample(lat = 32.00000, lon = 34.00000, accuracy = 10f, capturedAt = 1_000L)
        // Same tiny displacement/timing as the wobble case above, but Android itself reports real
        // movement - must not be suppressed, since this is exactly the "stop/start movement" case
        // the wobble veto exists to preserve.
        val next = sample(
            lat = last.latitude + degreesLatFor(1.0),
            lon = last.longitude,
            accuracy = 10f,
            capturedAt = 1_010L,
            speed = 1.2f,
        )
        assertTrue(decide(next, last) is RouteSampleDecision.Accepted)
    }

    // ---- Driving: the vehicle regime (see RouteMovementPlausibility's own doc comment) ----

    @Test
    fun `steady driving at 20 to 80 kmh with a matching reported speed is accepted`() {
        for (kmh in 20..80 step 10) {
            val (last, next) = drive(kmh, kmh)
            assertTrue("$kmh km/h must be accepted", decide(next, last) is RouteSampleDecision.Accepted)
        }
    }

    @Test
    fun `a whole drive from a standstill up to 80 kmh and back to a crawl is accepted fix by fix`() {
        // Each fix is compared with the previous one, exactly as live recording compares it with
        // the last accepted fix - the comparison that used to stall above ~22 km/h.
        var last: RawLocationSample? = null
        for (fix in drive(0, 10, 20, 35, 50, 65, 80, 80, 80, 60, 40, 20, 5)) {
            assertTrue(
                "fix at ${fix.capturedAtEpochSecond} reporting ${fix.speedMetersPerSecond} m/s must be accepted",
                decide(fix, last) is RouteSampleDecision.Accepted,
            )
            last = fix
        }
    }

    @Test
    fun `hard braking is accepted - the previous fix's speed explains the distance the almost-stopped newer fix cannot`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = 20f)
        // Braking from 72 km/h to a crawl within one 10s interval covers ~100m (10 m/s on average),
        // while the newer fix itself already reports only 0.5 m/s.
        val next = sample(lat = degreesLatFor(100.0), lon = 0.0, capturedAt = 1_010L, speed = 0.5f)
        assertTrue(decide(next, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `the same braking-shaped jump is still rejected when neither fix reports more than walking pace`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = 1.4f)
        val next = sample(lat = degreesLatFor(100.0), lon = 0.0, capturedAt = 1_010L, speed = 0.5f)
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decide(next, last) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `a reported speed exactly at the on-foot cap is not vehicle evidence, just above it is`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = RouteMovementPlausibility.VEHICLE_REPORTED_SPEED_THRESHOLD_MPS.toFloat())
        val impliedSpeed = RouteMovementPlausibility.MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND + 0.5
        val atThreshold = sample(
            lat = degreesLatFor(impliedSpeed * 10),
            lon = 0.0,
            capturedAt = 1_010L,
            speed = RouteMovementPlausibility.VEHICLE_REPORTED_SPEED_THRESHOLD_MPS.toFloat(),
        )
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decide(atThreshold, last) as RouteSampleDecision.Rejected).reason)

        val justAbove = atThreshold.copy(speedMetersPerSecond = impliedSpeed.toFloat())
        assertTrue(decide(justAbove, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `driving-speed movement with no reported speed on either fix is still rejected - nothing shows it is a vehicle`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = null)
        val next = sample(lat = degreesLatFor(150.0), lon = 0.0, capturedAt = 1_010L, speed = null) // 15 m/s implied
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decide(next, last) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `a spike during a drive that the reported speed cannot explain is still rejected`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = 20f)
        // 500m in 10s = 50 m/s implied, while both fixes report 20 m/s: over the 20*2+3 = 43 m/s ceiling.
        val spike = sample(lat = degreesLatFor(500.0), lon = 0.0, capturedAt = 1_010L, speed = 20f)
        assertEquals(RouteSampleRejectionReason.SPEED_CONTRADICTION, (decide(spike, last) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `the vehicle regime is capped at about 200 kmh whatever speed is reported`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = 40f)
        val maxSpeed = RouteMovementPlausibility.MAX_VEHICLE_SPEED_METERS_PER_SECOND

        val justUnder = sample(lat = degreesLatFor((maxSpeed - 0.5) * 10), lon = 0.0, capturedAt = 1_010L, speed = 40f)
        assertTrue(decide(justUnder, last) is RouteSampleDecision.Accepted)

        // 40*2+3 = 83 m/s would allow this; the hard ceiling does not.
        val justOver = sample(lat = degreesLatFor((maxSpeed + 0.5) * 10), lon = 0.0, capturedAt = 1_010L, speed = 40f)
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decide(justOver, last) as RouteSampleDecision.Rejected).reason)

        // Nor can a garbage reported speed license a teleport.
        val teleport = sample(lat = degreesLatFor(5_000.0), lon = 0.0, capturedAt = 1_010L, speed = 1_000f)
        assertEquals(RouteSampleRejectionReason.IMPLAUSIBLE_JUMP, (decide(teleport, last) as RouteSampleDecision.Rejected).reason)
    }

    @Test
    fun `the A55 defect pattern is still rejected right after a fast jog that stays under the vehicle threshold`() {
        val last = sample(lat = 0.0, lon = 0.0, capturedAt = 1_000L, speed = 5.9f)
        // ~90m in 10s = 9 m/s implied, reported 1.5 m/s - the defect's own shape.
        val spike = sample(lat = degreesLatFor(90.0), lon = 0.0, capturedAt = 1_010L, speed = 1.5f)
        assertTrue(decide(spike, last) is RouteSampleDecision.Rejected)
    }

    /**
     * Fixes every 10s heading north along a meridian, for a drive whose speed changes evenly
     * between consecutive fixes through [speedsKmh]; each fix reports its own instantaneous speed,
     * as a phone's GPS does.
     */
    private fun drive(vararg speedsKmh: Int): List<RawLocationSample> {
        var traveledMeters = 0.0
        return speedsKmh.mapIndexed { i, kmh ->
            if (i > 0) traveledMeters += (speedsKmh[i - 1] + kmh) / 3.6 / 2 * 10
            sample(lat = 32.0 + degreesLatFor(traveledMeters), lon = 34.0, capturedAt = 1_000L + i * 10L, speed = (kmh / 3.6).toFloat())
        }
    }

    private fun degreesLatFor(distanceMeters: Double): Double =
        Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
