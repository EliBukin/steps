package com.example.stepsplit.domain.trip

import kotlin.math.cos
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

    // ---- Standing still: drift while the phone reports 0 m/s (see RouteMovementPlausibility.STANDING_STILL_ACCURACY_FRACTION) ----

    @Test
    fun `the standing-still fixture reproduces the trip 4 shape the implied-speed wobble rule let through`() {
        val stoppedFixes = listOf(StandingStillDriftFixture.stop) + StandingStillDriftFixture.drift
        assertEquals(5, stoppedFixes.size)
        for ((from, to) in stoppedFixes.zipWithNext()) {
            val meters = RouteMath.haversineMeters(
                StandingStillDriftFixture.latitude(from),
                StandingStillDriftFixture.longitude(from),
                StandingStillDriftFixture.latitude(to),
                StandingStillDriftFixture.longitude(to),
            )
            val impliedSpeed = meters / (to.t - from.t)
            assertTrue("$meters m apart", meters in 8.0..21.0)
            assertTrue("$impliedSpeed m/s implied", impliedSpeed > RouteMovementPlausibility.STATIONARY_WOBBLE_MAX_IMPLIED_SPEED_MPS && impliedSpeed <= 1.0)
            assertTrue(to.accuracy in 12f..17f && to.speed == 0f)
        }
    }

    @Test
    fun `drift while the phone reports standing still is rejected even when it implies more than 0_3 mps - the A55 trip 4 shape`() {
        val fixture = StandingStillDriftFixture
        val (accepted, rejected) = recordLive(fixture.all.map(::liveSample))

        assertEquals((fixture.walkIn + fixture.stop + fixture.walkOut).map(::liveSample), accepted)
        assertEquals(fixture.drift.map { liveSample(it) to RouteSampleRejectionReason.STATIONARY_WOBBLE }, rejected)
        // Only the stop fix's own noise is left on top of what was walked - not the ~58m of drift.
        assertTrue(pathMeters(accepted) < fixture.WALKED_METERS + fixture.stop.accuracy)
    }

    @Test
    fun `slow walking at 0_5 to 1 mps with a matching reported speed is kept fix by fix, even within the accuracy noise`() {
        // Starts from a fix reporting standing still, and every step is well within the 15+15m
        // summed accuracy, so only the reported speed shows this is real movement.
        var northMeters = 0.0
        val fixes = listOf(0f, 0.5f, 0.75f, 1.0f, 0.5f).mapIndexed { i, speed ->
            northMeters += speed * 10
            sample(lat = 32.0 + degreesLatFor(northMeters), lon = 34.0, accuracy = 15f, capturedAt = 1_000L + i * 10L, speed = speed)
        }

        val (accepted, rejected) = recordLive(fixes)

        assertEquals(fixes, accepted)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `starting to walk loses no distance while the phone's speed still reports about 0 mps`() {
        val stop = sample(lat = 32.0, lon = 34.0, capturedAt = 1_000L, speed = 0f)
        // Walking north at ~1 m/s; Android's speed estimate takes two fixes to catch up.
        val walk = listOf(9.0 to 0f, 18.0 to 0.2f, 28.0 to 0.9f, 38.0 to 1.0f).mapIndexed { i, (northMeters, speed) ->
            sample(lat = 32.0 + degreesLatFor(northMeters), lon = 34.0, capturedAt = 1_010L + i * 10L, speed = speed)
        }

        val (accepted, rejected) = recordLive(listOf(stop) + walk)

        // The two lagging fixes are within the 10+10m summed accuracy of the stop, so they count as
        // drift - but the stop stays the anchor, and the next kept fix's segment covers the whole way.
        assertEquals(listOf(stop, walk[2], walk[3]), accepted)
        assertEquals(listOf(walk[0], walk[1]).map { it to RouteSampleRejectionReason.STATIONARY_WOBBLE }, rejected)
        assertEquals(38.0, pathMeters(accepted), 1e-6)
    }

    @Test
    fun `a walk the phone wrongly reports as 0 mps throughout still counts in full once it leaves the accuracy noise`() {
        val fixes = (0..6).map { i -> sample(lat = 32.0 + degreesLatFor(11.0 * i), lon = 34.0, capturedAt = 1_000L + i * 10L, speed = 0f) }

        val (accepted, _) = recordLive(fixes)

        // Every second fix is more than the 10+10m summed accuracy from the last kept one.
        assertEquals(listOf(fixes[0], fixes[2], fixes[4], fixes[6]), accepted)
        assertEquals(66.0, pathMeters(accepted), 1e-6)
    }

    @Test
    fun `a vehicle braking to a halt is never drift, while the parked drift after it is`() {
        val moving = sample(lat = 32.0, lon = 34.0, accuracy = 20f, capturedAt = 1_000L, speed = 7f)
        // ~25 km/h to a halt within 10s covers ~35m: inside the 20+20m summed accuracy, but the
        // previous fix reports driving speed, so this is not standing still.
        val halted = sample(lat = 32.0 + degreesLatFor(35.0), lon = 34.0, accuracy = 20f, capturedAt = 1_010L, speed = 0f)
        val parkedDrift = sample(
            lat = halted.latitude,
            lon = 34.0 + degreesLonFor(15.0, halted.latitude),
            accuracy = 20f,
            capturedAt = 1_030L,
            speed = 0f,
        )

        val (accepted, rejected) = recordLive(listOf(moving, halted, parkedDrift))

        assertEquals(listOf(moving, halted), accepted)
        assertEquals(listOf(parkedDrift to RouteSampleRejectionReason.STATIONARY_WOBBLE), rejected)
    }

    @Test
    fun `while both fixes report standing still, drift up to the summed accuracy is rejected and beyond it is kept`() {
        val last = sample(lat = 0.0, lon = 0.0, accuracy = 10f, capturedAt = 1_000L, speed = 0f)
        // 30s apart, so ~0.65 and ~0.68 m/s implied - both over the 0.3 m/s implied-speed wobble limit.
        val within = sample(lat = degreesLatFor(19.5), lon = 0.0, accuracy = 10f, capturedAt = 1_030L, speed = 0f)
        assertEquals(RouteSampleRejectionReason.STATIONARY_WOBBLE, (decide(within, last) as RouteSampleDecision.Rejected).reason)

        val beyond = within.copy(latitude = degreesLatFor(20.5))
        assertTrue(decide(beyond, last) is RouteSampleDecision.Accepted)
    }

    @Test
    fun `standing still means a reported speed of at most 0_3 mps on both fixes, never a missing one`() {
        val last = sample(lat = 0.0, lon = 0.0, accuracy = 10f, capturedAt = 1_000L, speed = 0f)
        val next = sample(
            lat = degreesLatFor(15.0),
            lon = 0.0,
            accuracy = 10f,
            capturedAt = 1_030L,
            speed = RouteMovementPlausibility.STATIONARY_WOBBLE_MAX_REPORTED_SPEED_MPS,
        )
        assertEquals(RouteSampleRejectionReason.STATIONARY_WOBBLE, (decide(next, last) as RouteSampleDecision.Rejected).reason)

        assertTrue(decide(next.copy(speedMetersPerSecond = 0.31f), last) is RouteSampleDecision.Accepted)
        assertTrue(decide(next, last.copy(speedMetersPerSecond = 0.31f)) is RouteSampleDecision.Accepted)
        assertTrue(decide(next.copy(speedMetersPerSecond = null), last) is RouteSampleDecision.Accepted)
        assertTrue(decide(next, last.copy(speedMetersPerSecond = null)) is RouteSampleDecision.Accepted)
    }

    private fun liveSample(fix: StandingStillDriftFixture.Fix) = sample(
        lat = StandingStillDriftFixture.latitude(fix),
        lon = StandingStillDriftFixture.longitude(fix),
        accuracy = fix.accuracy,
        capturedAt = fix.t,
        speed = fix.speed,
    )

    /** Feeds [fixes] through the policy in order, each compared with the last accepted one - exactly as recording does. */
    private fun recordLive(fixes: List<RawLocationSample>): Pair<List<RawLocationSample>, List<Pair<RawLocationSample, RouteSampleRejectionReason>>> {
        val accepted = mutableListOf<RawLocationSample>()
        val rejected = mutableListOf<Pair<RawLocationSample, RouteSampleRejectionReason>>()
        for (fix in fixes) {
            when (val decision = decide(fix, accepted.lastOrNull())) {
                is RouteSampleDecision.Accepted -> accepted += fix
                is RouteSampleDecision.Rejected -> rejected += fix to decision.reason
            }
        }
        return accepted to rejected
    }

    private fun pathMeters(fixes: List<RawLocationSample>): Double =
        fixes.zipWithNext().sumOf { (a, b) -> RouteMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }

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

    private fun degreesLonFor(distanceMeters: Double, atLatitude: Double): Double =
        Math.toDegrees(distanceMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(atLatitude))))

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
