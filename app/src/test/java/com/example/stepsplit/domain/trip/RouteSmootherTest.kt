package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSmootherTest {

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

    /** A point at (eastMeters, northMeters) offset from (baseLat, baseLon), via the same equirectangular approximation [RouteSmoother] itself uses - keeps synthetic "circle"/"curve" fixtures internally consistent with the code under test. */
    private fun pointAtOffset(
        baseLat: Double,
        baseLon: Double,
        eastMeters: Double,
        northMeters: Double,
        t: Long,
        accuracy: Float = 10f,
        speed: Float? = null,
    ) = point(
        lat = baseLat + degreesLatFor(northMeters),
        lon = baseLon + degreesLonFor(eastMeters, baseLat),
        t = t,
        accuracy = accuracy,
        speed = speed,
    )

    private fun totalDistance(points: List<TripPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += RouteMath.haversineMeters(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
        }
        return total
    }

    // ---- Empty / one-point / two-point routes ----

    @Test
    fun `an empty route is handled safely`() {
        val result = RouteSmoother.smooth(emptyList())
        assertTrue(result.points.isEmpty())
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun `a single-point route is preserved as-is with zero distance`() {
        val single = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val result = RouteSmoother.smooth(listOf(single))
        assertEquals(listOf(single), result.points)
        assertEquals(0.0, result.distanceMeters, 1e-9)
    }

    @Test
    fun `a two-point route is preserved as-is, endpoints exact`() {
        val a = point(10.0, 20.0, t = 1_000L, speed = 1.4f)
        val b = point(10.0 + degreesLatFor(14.0), 20.0, t = 1_010L, speed = 1.4f)
        val result = RouteSmoother.smooth(listOf(a, b))
        assertEquals(listOf(a, b), result.points)
        assertEquals(RouteMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude), result.distanceMeters, 1e-9)
    }

    // ---- Endpoints always exact, on a nontrivial route ----

    @Test
    fun `the first and last point of a longer route are returned completely unchanged`() {
        val points = (0 until 10).map { i -> point(10.0 + degreesLatFor(14.0 * i), 20.0, t = 1_000L + i * 10L, speed = 1.4f) }
        val result = RouteSmoother.smooth(points)
        assertEquals(points.first(), result.points.first())
        assertEquals(points.last(), result.points.last())
    }

    // ---- Clean straight route ----

    @Test
    fun `a clean straight route at constant pace is effectively unchanged`() {
        val trueSpeed = 1.4
        val stepMeters = trueSpeed * 10.0
        val points = (0 until 15).map { i ->
            point(10.0 + degreesLatFor(stepMeters * i), 20.0, t = 1_000L + i * 10L, accuracy = 10f, speed = trueSpeed.toFloat())
        }
        val trueDistance = stepMeters * 14

        val result = RouteSmoother.smooth(points)

        assertEquals(points.first(), result.points.first())
        assertEquals(points.last(), result.points.last())
        assertEquals(trueDistance, result.distanceMeters, 2.0)
    }

    // ---- Alternating lateral noise: the real-world failure mode ----

    @Test
    fun `alternating lateral GPS noise does not accumulate substantial extra distance`() {
        val trueSpeed = 1.4
        val stepMeters = trueSpeed * 10.0
        val lateralJitterMeters = 4.0
        val points = (0 until 20).map { i ->
            val lateral = if (i % 2 == 0) lateralJitterMeters else -lateralJitterMeters
            pointAtOffset(10.0, 20.0, eastMeters = lateral, northMeters = stepMeters * i, t = 1_000L + i * 10L, accuracy = 10f, speed = trueSpeed.toFloat())
        }
        val trueDistance = stepMeters * 19
        val rawDistance = totalDistance(points)
        assertTrue("fixture must actually be noisy relative to the true straight-line distance", rawDistance > trueDistance * 1.05)

        val result = RouteSmoother.smooth(points)

        assertTrue(
            "smoothed distance (${result.distanceMeters}) must be less than raw zig-zag distance ($rawDistance)",
            result.distanceMeters < rawDistance,
        )
        val rawOverage = rawDistance - trueDistance
        val smoothedOverage = result.distanceMeters - trueDistance
        assertTrue(
            "smoothing must cut the zig-zag overage substantially, not just marginally (raw overage=$rawOverage, smoothed overage=$smoothedOverage)",
            smoothedOverage < rawOverage * 0.5,
        )
    }

    // ---- Gradual curve preserved, not flattened ----

    @Test
    fun `a gradual curve is preserved, not flattened toward a straight chord`() {
        val radiusMeters = 200.0
        val stepMeters = 14.0
        val angleStepRadians = stepMeters / radiusMeters
        val points = (0..10).map { i ->
            val angle = angleStepRadians * i
            pointAtOffset(
                10.0,
                20.0,
                eastMeters = radiusMeters * sin(angle),
                northMeters = radiusMeters * (1 - cos(angle)),
                t = 1_000L + i * 10L,
                accuracy = 8f,
                speed = 1.4f,
            )
        }
        val mid = points[5]
        val chordStart = points.first()
        val chordEnd = points.last()
        val rawSagitta = perpendicularDistanceMeters(mid, chordStart, chordEnd)

        val result = RouteSmoother.smooth(points)
        val smoothedMid = result.points[5]
        val smoothedSagitta = perpendicularDistanceMeters(smoothedMid, result.points.first(), result.points.last())

        assertTrue("a real curve's midpoint must stay well clear of the straight chord (raw sagitta=$rawSagitta)", rawSagitta > 5.0)
        assertTrue(
            "smoothing must not flatten the curve toward the chord (raw sagitta=$rawSagitta, smoothed sagitta=$smoothedSagitta)",
            smoothedSagitta > rawSagitta * 0.7,
        )
    }

    private fun perpendicularDistanceMeters(point: TripPoint, lineStart: TripPoint, lineEnd: TripPoint): Double {
        val baseLat = lineStart.latitude
        fun toEastNorth(p: TripPoint): Pair<Double, Double> {
            val north = Math.toRadians(p.latitude - baseLat) * EARTH_RADIUS_METERS
            val east = Math.toRadians(p.longitude - lineStart.longitude) * EARTH_RADIUS_METERS * cos(Math.toRadians(baseLat))
            return east to north
        }
        val (sx, sy) = toEastNorth(lineStart)
        val (ex, ey) = toEastNorth(lineEnd)
        val (px, py) = toEastNorth(point)
        val dx = ex - sx
        val dy = ey - sy
        val lineLength = kotlin.math.hypot(dx, dy)
        if (lineLength == 0.0) return kotlin.math.hypot(px - sx, py - sy)
        // Cross product magnitude / line length = perpendicular distance from point to the line.
        return kotlin.math.abs(dx * (py - sy) - dy * (px - sx)) / lineLength
    }

    // ---- Genuine 90-degree corner not cut excessively ----

    @Test
    fun `a genuine 90-degree corner is not cut excessively`() {
        // A realistic number of supporting points on each leg (not just one or two) - a real route
        // has far more samples on each side of a corner than in the immediate smoothing window
        // (+-20s reaches about two samples either way), so the corner's own local rounding is a
        // small fraction of the whole leg's distance, not half of it.
        val latitude = 10.0
        val northLeg = (0..5).map { i -> point(latitude + degreesLatFor(14.0 * i), 20.0, t = 1_000L + i * 10L, speed = 1.4f) }
        val corner = northLeg.last()
        val eastLeg = (1..5).map { i -> point(corner.latitude, 20.0 + degreesLonFor(14.0 * i, latitude), t = corner.capturedAtEpochSecond + i * 10L, speed = 1.4f) }
        val points = northLeg + eastLeg
        val cornerIndex = northLeg.size - 1
        val rawDistance = totalDistance(points)
        val fullyCutDistance = RouteMath.haversineMeters(points.first().latitude, points.first().longitude, points.last().latitude, points.last().longitude)

        val result = RouteSmoother.smooth(points)
        val smoothedCorner = result.points[cornerIndex]

        val cornerDisplacement = RouteMath.haversineMeters(corner.latitude, corner.longitude, smoothedCorner.latitude, smoothedCorner.longitude)
        assertTrue(
            "the corner must never move further than its own reported accuracy (${corner.accuracyMeters}m), moved $cornerDisplacement m",
            cornerDisplacement <= corner.accuracyMeters + 1e-6,
        )
        // A fully-cut corner would replace the two legs with the direct diagonal, losing about
        // (rawDistance - fullyCutDistance) - "not excessive" is checked against a bound well short
        // of that: smoothing must recover only a modest fraction of what a full cut would.
        val fullCutLoss = rawDistance - fullyCutDistance
        val actualLoss = rawDistance - result.distanceMeters
        assertTrue(
            "smoothing must not cut anywhere near as much as a full diagonal shortcut would " +
                "(raw=$rawDistance, smoothed=${result.distanceMeters}, full-cut would lose $fullCutLoss, actually lost $actualLoss)",
            actualLoss < fullCutLoss * 0.5,
        )
    }

    // ---- Real U-turn / hairpin remains present ----

    @Test
    fun `a real U-turn remains present, not collapsed toward the mean position`() {
        val latitude = 10.0
        val outbound = (0..6).map { i -> point(latitude + degreesLatFor(14.0 * i), 20.0, t = 1_000L + i * 10L, speed = 1.4f) }
        val inbound = (1..6).map { i -> point(latitude + degreesLatFor(14.0 * (6 - i)), 20.0, t = 1_000L + (6 + i) * 10L, speed = 1.4f) }
        val points = outbound + inbound
        val rawDistance = totalDistance(points)
        val tip = points[6]

        val result = RouteSmoother.smooth(points)
        val smoothedTip = result.points[6]

        val tipDisplacement = RouteMath.haversineMeters(tip.latitude, tip.longitude, smoothedTip.latitude, smoothedTip.longitude)
        assertTrue(
            "the turnaround point must never move further than its own accuracy (${tip.accuracyMeters}m), moved $tipDisplacement m",
            tipDisplacement <= tip.accuracyMeters + 1e-6,
        )
        assertTrue(
            "an out-and-back path must not collapse toward zero distance (raw=$rawDistance, smoothed=${result.distanceMeters})",
            result.distanceMeters > rawDistance * 0.85,
        )
    }

    // ---- Circular route retains its loop and close start/end ----

    @Test
    fun `a circular route retains its loop shape and close start-end gap`() {
        val radiusMeters = 60.0
        val n = 24
        val points = (0..n).map { i ->
            val angle = 2 * Math.PI * i / n
            pointAtOffset(
                10.0,
                20.0,
                eastMeters = radiusMeters * sin(angle),
                northMeters = radiusMeters * (1 - cos(angle)),
                t = 1_000L + i * 10L,
                accuracy = 8f,
                speed = 1.4f,
            )
        }
        val trueCircumference = 2 * Math.PI * radiusMeters

        val result = RouteSmoother.smooth(points)

        assertEquals(points.first(), result.points.first())
        assertEquals(points.last(), result.points.last())
        val startEndGap = RouteMath.haversineMeters(
            result.points.first().latitude,
            result.points.first().longitude,
            result.points.last().latitude,
            result.points.last().longitude,
        )
        assertTrue("a closed loop's start/end must stay close together, gap was $startEndGap m", startEndGap < 5.0)
        assertTrue(
            "the loop must not collapse toward its own center (circumference=$trueCircumference, smoothed=${result.distanceMeters})",
            result.distanceMeters > trueCircumference * 0.85,
        )
    }

    // ---- Never smooths across a long sampling gap ----

    @Test
    fun `smoothing never crosses a long sampling gap`() {
        val clusterA = (0..4).map { i -> point(10.0 + degreesLatFor(14.0 * i), 20.0, t = 1_000L + i * 10L, speed = 1.4f) }
        // Far away in both space and time - well beyond the segmentation threshold.
        val gapStart = clusterA.last().capturedAtEpochSecond + 300L
        val clusterB = (0..4).map { i -> point(40.0 + degreesLatFor(14.0 * i), 90.0, t = gapStart + i * 10L, speed = 1.4f) }

        val combinedResult = RouteSmoother.smooth(clusterA + clusterB)
        val clusterAAlone = RouteSmoother.smooth(clusterA)

        // Every point except clusterA's own last one must compute identically whether or not
        // clusterB exists on the far side of the gap - that last point is excluded here only
        // because it is clusterA's *global* endpoint (forced to its exact raw value) in the
        // standalone call, but merely an internal segment boundary (still smoothed from its own
        // segment's neighbors, correctly) in the combined call; see the boundary-specific checks
        // below for that point instead.
        assertEquals(clusterAAlone.points.dropLast(1), combinedResult.points.subList(0, clusterA.size - 1))

        val lastOfA = clusterA.last()
        val smoothedLastOfA = combinedResult.points[clusterA.size - 1]
        val displacement = RouteMath.haversineMeters(lastOfA.latitude, lastOfA.longitude, smoothedLastOfA.latitude, smoothedLastOfA.longitude)
        assertTrue(
            "the point right before the gap must stay within its own accuracy bound, not be pulled toward clusterB",
            displacement <= lastOfA.accuracyMeters + 1e-6,
        )
        val distanceToClusterB = RouteMath.haversineMeters(smoothedLastOfA.latitude, smoothedLastOfA.longitude, clusterB.first().latitude, clusterB.first().longitude)
        val rawGapDistance = RouteMath.haversineMeters(lastOfA.latitude, lastOfA.longitude, clusterB.first().latitude, clusterB.first().longitude)
        assertTrue(
            "the point right before the gap must remain essentially as far from clusterB as it started",
            distanceToClusterB > rawGapDistance * 0.99,
        )
    }

    // ---- Accuracy weighting: poor-accuracy points influence the output less ----

    @Test
    fun `a poor-accuracy neighbor influences the smoothed result less than a precise one at the same position`() {
        val latitude = 10.0
        fun windowWith(outlierAccuracy: Float): List<TripPoint> = listOf(
            point(latitude, 20.0, t = 990L, accuracy = 10f, speed = 1.4f),
            point(latitude + degreesLatFor(14.0), 20.0, t = 1_000L, accuracy = 10f, speed = 1.4f),
            // A neighbor offset laterally by 6m east - the only asymmetric influence in the window.
            point(
                latitude + degreesLatFor(28.0),
                20.0 + degreesLonFor(6.0, latitude),
                t = 1_010L,
                accuracy = outlierAccuracy,
                speed = 1.4f,
            ),
        )

        val center = windowWith(10f)[1]
        val tightResult = RouteSmoother.smooth(windowWith(5f))
        val poorResult = RouteSmoother.smooth(windowWith(28f))

        val tightDisplacement = RouteMath.haversineMeters(center.latitude, center.longitude, tightResult.points[1].latitude, tightResult.points[1].longitude)
        val poorDisplacement = RouteMath.haversineMeters(center.latitude, center.longitude, poorResult.points[1].latitude, poorResult.points[1].longitude)

        assertTrue(
            "a precise (5m accuracy) neighbor must pull the center point more than a poor (28m accuracy) one at the same position (tight=$tightDisplacement, poor=$poorDisplacement)",
            tightDisplacement > poorDisplacement,
        )
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
