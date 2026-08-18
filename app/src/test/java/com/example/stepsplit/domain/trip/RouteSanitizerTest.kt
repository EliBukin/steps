package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSanitizerTest {

    private fun point(
        lat: Double,
        lon: Double,
        t: Long,
        accuracy: Float = 10f,
        altitude: Double? = null,
        speed: Float? = null,
    ) = TripPoint(
        capturedAtEpochSecond = t,
        latitude = lat,
        longitude = lon,
        accuracyMeters = accuracy,
        altitudeMeters = altitude,
        speedMetersPerSecond = speed,
    )

    private fun degreesLatFor(distanceMeters: Double): Double = Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS)

    private fun degreesLonFor(distanceMeters: Double, atLatitude: Double): Double =
        Math.toDegrees(distanceMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(atLatitude))))

    // ---- Boundary/empty cases ----

    @Test
    fun `an empty route sanitizes to an empty result with zero distance`() {
        val result = RouteSanitizer.sanitize(emptyList())
        assertTrue(result.points.isEmpty())
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun `a single-point route is preserved as-is with zero distance`() {
        val single = point(10.0, 20.0, t = 1_000L)
        val result = RouteSanitizer.sanitize(listOf(single))
        assertEquals(listOf(single), result.points)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    // ---- Chronological ordering / duplicate timestamps ----

    @Test
    fun `points delivered out of order are sanitized in chronological order`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(28.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(c, a, b))

        assertEquals(listOf(1_000L, 1_010L, 1_020L), result.points.map { it.capturedAtEpochSecond })
    }

    @Test
    fun `an exact duplicate timestamp is dropped, keeping the first occurrence`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val duplicateOfA = point(10.0 + degreesLatFor(50.0), 20.0, t = 1_000L, speed = 1.4f) // same timestamp, different position
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, duplicateOfA, b))

        assertEquals(2, result.points.size)
        assertEquals(a, result.points.first())
        assertTrue(result.rejections.any { it.point == duplicateOfA && it.reason == RouteSampleRejectionReason.NON_MONOTONIC_OR_DUPLICATE })
    }

    // ---- Non-finite / invalid values ----

    @Test
    fun `points with non-finite or invalid coordinates, accuracy, speed, or altitude are dropped, the rest kept`() {
        val good1 = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val nanLat = point(Double.NaN, 20.0, t = 1_010L)
        val infiniteAccuracy = point(10.001, 20.0, t = 1_020L, accuracy = Float.POSITIVE_INFINITY)
        val nanSpeed = point(10.002, 20.0, t = 1_030L, speed = Float.NaN)
        val infiniteAltitude = point(10.003, 20.0, t = 1_040L, altitude = Double.POSITIVE_INFINITY)
        val zeroZeroSentinel = point(0.0, 0.0, t = 1_050L)
        val good2 = point(10.0 + degreesLatFor(56.0), 20.0, t = 1_060L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(good1, nanLat, infiniteAccuracy, nanSpeed, infiniteAltitude, zeroZeroSentinel, good2))

        assertEquals(listOf(good1, good2), result.points)
        assertTrue(result.distanceMeters.isFinite())
        assertTrue(result.distanceMeters >= 0.0)
    }

    @Test
    fun `a route with only invalid points sanitizes to empty with finite zero distance`() {
        val result = RouteSanitizer.sanitize(listOf(point(Double.NaN, 20.0, t = 1_000L), point(10.0, Double.NaN, t = 1_010L)))
        assertTrue(result.points.isEmpty())
        assertEquals(0.0, result.distanceMeters, 1e-9)
        assertTrue(result.distanceMeters.isFinite())
    }

    // ---- Historical accuracy filtering, shared with the live policy (Finding 1) ----

    @Test
    fun `a historical point with 31-50m accuracy is removed even though its movement passes every speed check`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        // Recorded under the old, more permissive 50m accuracy policy - ordinary walking-pace
        // movement on both sides, so every movement/speed check alone would accept it; only the
        // accuracy bound, now shared with RoutePointAcceptancePolicy, catches it.
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, accuracy = 45f, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(28.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, c), result.points)
        assertTrue(result.rejections.any { it.point == b && it.reason == RouteSampleRejectionReason.POOR_ACCURACY })
    }

    @Test
    fun `a point at exactly the 30m accuracy limit is kept`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, accuracy = RoutePointAcceptancePolicy.MAX_ACCURACY_METERS, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, b))

        assertEquals(listOf(a, b), result.points)
        assertTrue(result.rejections.none { it.point == b })
    }

    @Test
    fun `a point just above the 30m accuracy limit is rejected as POOR_ACCURACY, matching the live policy`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(
            10.0 + degreesLatFor(14.0),
            20.0,
            t = 1_010L,
            accuracy = Math.nextUp(RoutePointAcceptancePolicy.MAX_ACCURACY_METERS),
            speed = 1.4f,
        )

        val result = RouteSanitizer.sanitize(listOf(a, b))

        assertEquals(listOf(a), result.points)
        assertTrue(result.rejections.any { it.point == b && it.reason == RouteSampleRejectionReason.POOR_ACCURACY })
    }

    // ---- Hard, immediately-rejected isolated spike (the three-point rule) ----

    @Test
    fun `a single out-and-back spike is removed, the plausible path on both sides survives`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        // B jumps ~300m away in 10s (~30 m/s) then C returns to essentially where A was, also in
        // 10s - A->B implausible, B->C implausible, A->C plausible (just one normal walking step).
        val spike = point(10.0 + degreesLatFor(300.0), 20.0, t = 1_010L, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, spike, c))

        assertEquals(listOf(a, c), result.points)
        assertTrue(result.rejections.any { it.point == spike })
        val expectedDistance = RouteMath.haversineMeters(a.latitude, a.longitude, c.latitude, c.longitude)
        assertEquals(expectedDistance, result.distanceMeters, 1e-6)
    }

    @Test
    fun `multiple consecutive bad samples are all removed, not just the first one`() {
        // Two genuine leading points establish the anchor *before* the bad run begins - this is
        // what isolates the greedy scan's own robustness (comparing each candidate against the
        // last *accepted* point) from the separate anchor-bootstrap step, which - by design, see
        // RouteSanitizer's own doc comment - only ever looks at raw adjacent pairs and can't itself
        // distinguish a genuine anchor from a run of bad points that merely stay close to each
        // other (this is why a spike run is deliberately placed *after* a confirmed-good anchor in
        // this test, exactly as it is in the A55 fixture below).
        val a0 = point(10.0, 20.0, t = 990L, speed = 1.4f)
        val a = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_000L, speed = 1.4f)
        val spike1 = point(a.latitude + degreesLatFor(300.0), 20.0, t = 1_010L, speed = 1.4f)
        val spike2 = point(a.latitude + degreesLatFor(340.0), 20.0, t = 1_020L, speed = 1.4f)
        val spike3 = point(a.latitude + degreesLatFor(280.0), 20.0, t = 1_030L, speed = 1.4f)
        val c = point(a.latitude + degreesLatFor(14.0), 20.0, t = 1_040L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a0, a, spike1, spike2, spike3, c))

        assertEquals(listOf(a0, a, c), result.points)
        assertEquals(3, result.rejections.count { it.point in setOf(spike1, spike2, spike3) })
    }

    @Test
    fun `a bad first point is dropped and does not poison the rest of a legitimate route`() {
        val badFirst = point(10.0 + degreesLatFor(5_000.0), 20.0, t = 1_000L, speed = 0.0f) // wildly off, e.g. a stale cached fix
        val a = point(10.0, 20.0, t = 1_010L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(28.0), 20.0, t = 1_030L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(badFirst, a, b, c))

        assertEquals(listOf(a, b, c), result.points)
        assertTrue(result.rejections.any { it.point == badFirst })
    }

    @Test
    fun `a bad final point is dropped, the preceding legitimate route survives`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(28.0), 20.0, t = 1_020L, speed = 1.4f)
        val badLast = point(c.latitude + degreesLatFor(5_000.0), 20.0, t = 1_030L, speed = 0.0f)

        val result = RouteSanitizer.sanitize(listOf(a, b, c, badLast))

        assertEquals(listOf(a, b, c), result.points)
        assertTrue(result.rejections.any { it.point == badLast })
    }

    // ---- Moderate spike requiring look-ahead reconsideration (Finding 2) ----
    //
    // A -> B and B -> C can each individually clear every pairwise check (the raw speed cap, the
    // reported-speed contradiction ceiling) while the three-point path they describe is still not
    // real movement - a plain greedy scan that permanently accepts B the moment A -> B passes can
    // never catch this, since it never revisits B once C arrives. These tests exercise the
    // pending/look-ahead reconsideration ([RouteSanitizer]'s own doc comment, algorithm step 4)
    // that a single pairwise-only scan cannot express.

    @Test
    fun `a moderate out-and-back spike that individually clears every pairwise check is still removed via look-ahead`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        // B: ~55m out in 10s (~5.5 m/s) - under the 6.0 m/s cap and under its own reported-speed
        // contradiction ceiling (1.5*2+3=6.0), so the old pairwise-only scan accepted it outright.
        val b = point(10.0 + degreesLatFor(55.0), 20.0, t = 1_010L, speed = 1.5f)
        // C: back to ~14m from A in another 10s (~4.1 m/s B->C, likewise individually plausible).
        val c = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, c), result.points)
        assertTrue(result.rejections.any { it.point == b })
        val expectedDistance = RouteMath.haversineMeters(a.latitude, a.longitude, c.latitude, c.longitude)
        assertEquals(expectedDistance, result.distanceMeters, 1e-6)
    }

    @Test
    fun `a contextual spike is still removed even when its own accuracy is legally poor but within the 30m limit`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(55.0), 20.0, t = 1_010L, accuracy = 25f, speed = 1.5f)
        val c = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, c), result.points)
        assertTrue(result.rejections.any { it.point == b })
    }

    @Test
    fun `the same moderate spike shape is kept, not removed, when no reported speed is available to distinguish it from a real U-turn`() {
        // Identical geometry to the moderate-spike test above, but with no reported speed on any
        // point - the evidence needed to call this an excursion neither leg's own speed supports
        // simply does not exist, so the conservative default is to leave it alone.
        val a = point(10.0, 20.0, t = 1_000L, speed = null)
        val b = point(10.0 + degreesLatFor(55.0), 20.0, t = 1_010L, speed = null)
        val c = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = null)

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, b, c), result.points)
    }

    @Test
    fun `a legitimate out-and-back walk with reported speed matching both legs remains intact even though A and C are close`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(24.0), 20.0, t = 1_010L, speed = 2.4f)
        val c = point(10.0, 20.0, t = 1_020L, speed = 2.4f) // back to essentially A's own position

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, b, c), result.points)
    }

    @Test
    fun `a legitimate brisk-pace turn is preserved even when the geometric detour is large enough to require reported-speed support`() {
        val latitude = 10.0
        // A wider turn than the ordinary-walking-pace one below, at a brisker but still plausible
        // pace (~5.5 m/s, under the 6.0 m/s cap) - large enough that the detour clears the material
        // floor and genuinely exercises the reported-speed-support check, not just the floor itself.
        val a = point(latitude, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(latitude + degreesLatFor(55.0), 20.0, t = 1_010L, speed = 5.5f)
        val c = point(latitude + degreesLatFor(55.0), 20.0 + degreesLonFor(55.0, latitude), t = 1_020L, speed = 5.5f)

        val result = RouteSanitizer.sanitize(listOf(a, b, c))

        assertEquals(listOf(a, b, c), result.points)
    }

    @Test
    fun `a final point with no successor to look ahead to is kept once it clears the ordinary pairwise check alone`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f)
        // The route ends here. This point has no successor, so it can only ever receive the
        // ordinary pairwise check - the same one-sample-delay limitation that already applied
        // before look-ahead reconsideration existed.
        val last = point(b.latitude + degreesLatFor(55.0), 20.0, t = 1_020L, speed = 1.5f)

        val result = RouteSanitizer.sanitize(listOf(a, b, last))

        assertEquals(listOf(a, b, last), result.points)
    }

    // ---- Stationary wobble ----

    @Test
    fun `stationary GPS wobble collapses to a single representative point with near-zero distance`() {
        // ~1-2m of drift each step, near-zero reported speed throughout - textbook noise while
        // standing still, not real movement.
        val points = (0 until 6).map { i ->
            point(
                lat = 10.0 + degreesLatFor(if (i % 2 == 0) 1.0 else 1.8),
                lon = 20.0,
                t = 1_000L + i * 10L,
                accuracy = 10f,
                speed = 0.1f,
            )
        }
        val result = RouteSanitizer.sanitize(points)

        assertEquals(1, result.points.size)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    // ---- Legitimate sharp turn ----

    @Test
    fun `a legitimate sharp turn at ordinary walking pace is fully preserved`() {
        val latitude = 10.0
        // Walking north for two samples, then a 90-degree turn east for two more - constant
        // ordinary walking pace throughout (~1.4 m/s), just a change in direction. A turn is not a
        // speed anomaly and must never be treated as a spike.
        val points = listOf(
            point(latitude, 20.0, t = 1_000L, speed = 1.4f),
            point(latitude + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f),
            point(latitude + degreesLatFor(14.0), 20.0 + degreesLonFor(14.0, latitude), t = 1_020L, speed = 1.4f),
            point(latitude + degreesLatFor(14.0), 20.0 + degreesLonFor(28.0, latitude), t = 1_030L, speed = 1.4f),
        )

        val result = RouteSanitizer.sanitize(points)

        assertEquals(points, result.points)
    }

    // ---- Long sampling gaps ----

    @Test
    fun `a long sampling gap with a proportionally larger distance is preserved, not flagged as a jump`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        // 10 minutes later, ~1.4 m/s of real walking the whole time covers ~840m - a large
        // distance, but a perfectly ordinary implied speed once the gap is accounted for.
        val gapSeconds = 600L
        val b = point(10.0 + degreesLatFor(1.4 * gapSeconds), 20.0, t = 1_000L + gapSeconds, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, b))

        assertEquals(listOf(a, b), result.points)
        assertEquals(RouteMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude), result.distanceMeters, 1e-6)
    }

    // ---- Missing reported speed ----

    @Test
    fun `sanitizing still works safely when no point reports a speed at all`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = null)
        val spike = point(10.0 + degreesLatFor(300.0), 20.0, t = 1_010L, speed = null)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = null)

        val result = RouteSanitizer.sanitize(listOf(a, spike, b))

        assertEquals(listOf(a, b), result.points)
    }

    // ---- Distance == haversine sum of only the cleaned points ----

    @Test
    fun `distanceMeters always equals the haversine sum of consecutive cleaned points, never the raw input`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val spike = point(10.0 + degreesLatFor(300.0), 20.0, t = 1_010L, speed = 1.4f)
        val c = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_020L, speed = 1.4f)

        val result = RouteSanitizer.sanitize(listOf(a, spike, c))

        var manualSum = 0.0
        for (i in 1 until result.points.size) {
            val prev = result.points[i - 1]
            val curr = result.points[i]
            manualSum += RouteMath.haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        }
        assertEquals(manualSum, result.distanceMeters, 1e-9)
        // And it must be meaningfully less than what naively summing every RAW input segment
        // (including the spike both ways) would have given.
        val rawSum = RouteMath.haversineMeters(a.latitude, a.longitude, spike.latitude, spike.longitude) +
            RouteMath.haversineMeters(spike.latitude, spike.longitude, c.latitude, c.longitude)
        assertTrue(result.distanceMeters < rawSum)
    }

    // ---- Anonymized A55 field-defect regression fixture ----
    //
    // Reproduces the *shape* of the confirmed defect - GPS jump segments implying 8-11 m/s while
    // Android's own reported speed for those exact fixes stays at ordinary walking pace (~1.3-1.8
    // m/s), interspersed through an otherwise-plausible ~21-minute walking route sampled every 10s
    // (matching FusedTripLocationClient.UPDATE_INTERVAL_MILLIS) - without using the user's real
    // coordinates, timestamps, or route: the base position/epoch below are arbitrary synthetic
    // values (10.0N, 20.0E - open ocean, chosen only for being nowhere near anyone's real location),
    // and the walking path is a straight synthetic line, not a reconstruction of any real trail.

    private object A55Fixture {
        const val BASE_LATITUDE = 10.0
        const val BASE_LONGITUDE = 20.0
        const val START_EPOCH_SECOND = 2_000_000_000L // arbitrary synthetic epoch
        const val WALK_SPEED_METERS_PER_SECOND = 1.35 // within the real defect's reported 1.3-1.8 m/s range
        const val SAMPLE_INTERVAL_SECONDS = 10L
        const val DURATION_SECONDS = 21 * 60L // matches the real ~21-minute recording
        const val SPIKE_REPORTED_SPEED_METERS_PER_SECOND = 1.5f // within the real defect's 1.3-1.8 m/s reported range

        /**
         * Steps (10s-spaced samples) that are bad spikes, mapped to how far (metres) each jumps
         * from the true path at that moment - a mix of isolated singles and short consecutive
         * runs, matching "several segments" and "multiple consecutive bad samples" both being
         * present in the real defect. Isolated spikes use 90m (implying 90/10=9.0 m/s over one
         * 10s hop - squarely in the confirmed 8-11 m/s range). The two consecutive runs use a
         * larger 300m so every point in the run stays implausible even when the greedy scan
         * compares it against an anchor that is now two or three samples (20-30s) stale, not just
         * one - see RouteSanitizer's own doc comment on why comparing against the last *accepted*
         * point (not the last *seen* point) is what makes a whole run removable, and
         * `multiple consecutive bad samples are all removed` above for the same reasoning applied
         * to a smaller, standalone example.
         */
        val spikeJumpMeters: Map<Int, Double> = mapOf(
            20 to 90.0,
            45 to 300.0,
            46 to 300.0,
            70 to 90.0,
            90 to 300.0,
            91 to 300.0,
            92 to 300.0,
            110 to 90.0,
        )
        val spikeSteps: Set<Int> get() = spikeJumpMeters.keys

        fun build(): List<TripPoint> {
            val points = mutableListOf<TripPoint>()
            var traveled = 0.0
            var step = 0L
            var t = START_EPOCH_SECOND
            while (t < START_EPOCH_SECOND + DURATION_SECONDS) {
                val jumpMeters = spikeJumpMeters[step.toInt()]
                if (jumpMeters != null) {
                    points += TripPoint(
                        capturedAtEpochSecond = t,
                        latitude = BASE_LATITUDE + Math.toDegrees((traveled + jumpMeters) / EARTH_RADIUS_METERS),
                        longitude = BASE_LONGITUDE,
                        accuracyMeters = 12f, // ordinary accuracy - the point looks fine except for its implied speed
                        altitudeMeters = null,
                        speedMetersPerSecond = SPIKE_REPORTED_SPEED_METERS_PER_SECOND,
                    )
                } else {
                    traveled += WALK_SPEED_METERS_PER_SECOND * SAMPLE_INTERVAL_SECONDS
                    points += TripPoint(
                        capturedAtEpochSecond = t,
                        latitude = BASE_LATITUDE + Math.toDegrees(traveled / EARTH_RADIUS_METERS),
                        longitude = BASE_LONGITUDE,
                        accuracyMeters = 10f,
                        altitudeMeters = null,
                        speedMetersPerSecond = WALK_SPEED_METERS_PER_SECOND.toFloat(),
                    )
                }
                t += SAMPLE_INTERVAL_SECONDS
                step++
            }
            return points
        }
    }

    @Test
    fun `the A55 defect pattern - 8-11 mps jumps against 1_3-1_8 mps reported speed - is removed without deleting the plausible walking path`() {
        val raw = A55Fixture.build()

        // Sanity-check the fixture itself reproduces the confirmed contradiction shape before
        // asserting anything about the sanitizer.
        var worstImpliedSpeed = 0.0
        for (i in 1 until raw.size) {
            val prev = raw[i - 1]
            val curr = raw[i]
            val elapsed = curr.capturedAtEpochSecond - prev.capturedAtEpochSecond
            val distance = RouteMath.haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            worstImpliedSpeed = maxOf(worstImpliedSpeed, distance / elapsed)
        }
        assertTrue("fixture must reproduce implied speeds in the 8-11 m/s range", worstImpliedSpeed >= 8.0)

        val rawDistance = (1 until raw.size).sumOf { i ->
            RouteMath.haversineMeters(raw[i - 1].latitude, raw[i - 1].longitude, raw[i].latitude, raw[i].longitude)
        }

        val result = RouteSanitizer.sanitize(raw)

        // Every injected spike was actually removed.
        val spikeCount = A55Fixture.spikeSteps.size
        assertEquals(spikeCount, result.rejections.size)
        assertEquals(raw.size - spikeCount, result.points.size)

        // The plausible walking path survives chronologically intact around the removed spikes.
        val expectedCleanedPoints = raw.filterIndexed { index, _ -> index !in A55Fixture.spikeSteps }
        assertEquals(expectedCleanedPoints, result.points)

        // distanceMeters must be exactly the haversine sum of that same surviving path - derived
        // independently here, not by trusting the production code's own accumulator.
        var expectedDistance = 0.0
        for (i in 1 until expectedCleanedPoints.size) {
            expectedDistance += RouteMath.haversineMeters(
                expectedCleanedPoints[i - 1].latitude,
                expectedCleanedPoints[i - 1].longitude,
                expectedCleanedPoints[i].latitude,
                expectedCleanedPoints[i].longitude,
            )
        }
        assertEquals(expectedDistance, result.distanceMeters, 1e-6)

        // The cleaned distance is a defensible, substantially reduced figure relative to the raw,
        // outlier-inflated one - not asserted against any single "ground truth" number, per the
        // task's own instruction not to treat any one figure as unquestionable.
        assertTrue(
            "cleaned distance (${result.distanceMeters}) must be substantially less than the raw, spike-inflated distance ($rawDistance)",
            result.distanceMeters < rawDistance * 0.8,
        )
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
