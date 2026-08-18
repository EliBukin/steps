package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * The result of [RouteSmoother.smooth]: [points] is the same count, in the same chronological
 * order, as the input - every point's coordinates may have shifted by a small, accuracy-bounded
 * amount, but nothing is added, removed, or reordered, and every non-coordinate field (timestamp,
 * accuracy, reported speed, altitude) is carried over unchanged. [distanceMeters] is the haversine
 * sum of consecutive [points] in that exact sequence - the only distance figure this class ever
 * produces, never a separately-recomputed one.
 */
data class SmoothedRoute(
    val points: List<TripPoint>,
    val distanceMeters: Double,
)

/**
 * Pure, JVM-testable, non-destructive reduction of ordinary GPS wobble, applied *after*
 * [RouteSanitizer] as a second, independent stage: `stored points -> RouteSanitizer -> RouteSmoother
 * -> map/distance/GPX`. [RouteSanitizer] removes points with concrete evidence against them (bad
 * accuracy, an implausible jump); it does nothing about a route where *every* point is individually
 * plausible but the sequence still zig-zags a few metres side to side around the true path -
 * ordinary receiver noise within accuracy tolerance, at walking speed, that no single-point or
 * single-pair rule can flag as "wrong" (there is nothing evidenced wrong with any one of them) but
 * which still accumulates real extra distance over hundreds of samples, exactly the failure mode a
 * second real-world walk confirmed: 526 stored points, zero removed by the sanitizer (no segment
 * exceeded 5 m/s, the worst coordinate-implied speed was 3.5 m/s against an Android-reported ~1.28
 * m/s), yet stored distance still ran noticeably ahead of Android's own reported-speed integration
 * and a step-count-based estimate.
 *
 * ## Algorithm: centered, accuracy-and-time-weighted local averaging
 *
 * For each point, in a local metre-based tangent plane centered on that point's own raw position
 * (east/north offsets via an equirectangular approximation - accurate at the scale of a single
 * smoothing window, a few tens of metres), every other point within [TIME_WINDOW_SECONDS] of it in
 * the same segment (see below) contributes a weighted vote for where the "true" position likely is,
 * weighted by:
 * - **Accuracy** - inverse-variance weighting (`1 / accuracy²`), the standard way to combine several
 *   independent estimates of one true value when each has a different, known uncertainty (Android's
 *   accuracy circle behaves like a roughly Gaussian 68%-confidence radius): a fix with half the
 *   accuracy radius of another gets four times its influence, so a single much-worse-than-usual fix
 *   never dominates a cluster of ordinary ones.
 * - **Time** - a Gaussian kernel on elapsed time from the point being smoothed, not a flat window: a
 *   neighbour at the very edge of the window contributes much less than one right next to it, so the
 *   result changes smoothly as points enter/leave the window rather than jumping at a hard cutoff.
 *   This is what makes the filter genuinely time-aware, not just "the next/previous N samples" -
 *   real sampling intervals vary (5-15s, occasional batching), and weighting by elapsed time rather
 *   than a fixed point count degrades gracefully when they do.
 *
 * A **centered, weighted local average** was chosen over more elaborate options (a Kalman filter,
 * spline fitting) specifically because it is simple enough to reason about and bound: every output
 * point is a convex combination of nearby *real, recorded* positions (never an extrapolation or a
 * fitted curve that could overshoot past the actual data), and the displacement safeguard below can
 * be expressed directly in terms of one physical quantity Android already reports (accuracy) rather
 * than a filter-specific parameter a reviewer would have to trust separately.
 *
 * ## Safeguards
 *
 * - **Segmentation**: consecutive points more than [MAX_SEGMENT_GAP_SECONDS] apart start a new
 *   segment; a window never reaches across that boundary. A real gap (paused recording, lost fix,
 *   tunnel) does not behave like ordinary sampling jitter on either side of it, so blending across it
 *   would not be smoothing - it would be inventing a relationship between two unrelated stretches of
 *   the route.
 * - **Displacement bound**: after computing the weighted-average offset for a point, if its distance
 *   from that point's own raw position exceeds `accuracyMeters * [DISPLACEMENT_ACCURACY_MULTIPLIER]`,
 *   the offset is scaled back to exactly that distance (same direction, shorter length). A point is
 *   never moved further than Android's own stated uncertainty for that exact fix allows - this is
 *   what keeps a real corner, U-turn, or tight loop from being smoothed away: the *evidence* for a
 *   large, deliberate direction change (the recorded positions on both sides of it) simply cannot be
 *   overridden by more than one fix-width of averaging.
 * - **Endpoints**: the very first and last point of the whole input list are always returned
 *   completely unchanged (same coordinates, same every other field) - the map's start/finish markers
 *   and the trip's displayed start/end must never move.
 * - Segments (and the whole route) with fewer than 3 points are returned unchanged - there is no
 *   meaningful local neighbourhood to average over.
 * - Never fabricates a point, drops a point, or reorders points; never touches any field but
 *   latitude/longitude; never looks at (or needs) the underlying stored rows - a pure function of its
 *   input list, computed fresh on every call, exactly like [RouteSanitizer.sanitize].
 */
object RouteSmoother {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * A gap larger than this starts a new smoothing segment. [com.example.stepsplit.data.trip.FusedTripLocationClient]
     * requests updates every 10s (5s minimum, up to 15s of platform batching), so a gap six times the
     * nominal interval is comfortably beyond ordinary batching delays and much more likely to reflect
     * a real pause, lost fix, or dead zone - exactly the kind of gap [RouteSanitizerTest]'s own "long
     * sampling gap" case already treats as legitimate rather than suspicious, just from the smoothing
     * side rather than the acceptance side.
     */
    private const val MAX_SEGMENT_GAP_SECONDS = 60L

    /**
     * Only points within this many seconds of the one being smoothed contribute to it. At the
     * nominal 10s sampling interval this reaches about two samples on either side (a five-point
     * neighbourhood) - enough real, independent fixes to average out ordinary receiver noise without
     * pulling in a stretch of the route far enough away in time to represent meaningfully different
     * ground truth (a real turn a person made ten seconds later, say).
     */
    private const val TIME_WINDOW_SECONDS = 20.0

    /**
     * Standard deviation of the Gaussian time-weighting kernel. At exactly [TIME_WINDOW_SECONDS] away
     * (the edge of the window) a neighbour's time-weight is `exp(-20²/(2·10²)) ≈ 0.14` relative to the
     * centre point's own `1.0` - a real but small contribution, so the window's edge is a taper, not a
     * cliff.
     */
    private const val TIME_KERNEL_SIGMA_SECONDS = 10.0

    /**
     * A point is never displaced more than its own reported accuracy (the 68%-confidence radius
     * Android itself assigned that exact fix), times this multiplier. `1.0` was chosen because it is
     * the least arbitrary possible bound: it ties the cap directly to a quantity the fix itself
     * already carries, rather than an independently-chosen distance. A fix Android considered precise
     * can only move a little; a fix at the sanitizer's own 30m acceptance ceiling could in principle
     * move up to 30m, but in practice the weighted average of a locally consistent cluster of ordinary
     * fixes lands far closer than that - this is a backstop, not the typical case.
     */
    private const val DISPLACEMENT_ACCURACY_MULTIPLIER = 1.0

    /** cos(latitude) below this magnitude is clamped to avoid a division blow-up near the poles - not a real scenario for a walking app, but [RouteMath.haversineMeters] applies the same defensive-finiteness discipline elsewhere in this package. */
    private const val MIN_ABS_COS_LATITUDE = 1e-6

    fun smooth(points: List<TripPoint>): SmoothedRoute {
        if (points.size < 3) return SmoothedRoute(points, sumDistanceMeters(points))

        val smoothed = splitIntoSegments(points).flatMap(::smoothSegment).toMutableList()
        smoothed[0] = points.first()
        smoothed[smoothed.size - 1] = points.last()

        return SmoothedRoute(smoothed, sumDistanceMeters(smoothed))
    }

    private fun splitIntoSegments(points: List<TripPoint>): List<List<TripPoint>> {
        val segments = mutableListOf(mutableListOf(points[0]))
        for (i in 1 until points.size) {
            val gapSeconds = points[i].capturedAtEpochSecond - points[i - 1].capturedAtEpochSecond
            if (gapSeconds > MAX_SEGMENT_GAP_SECONDS) {
                segments += mutableListOf(points[i])
            } else {
                segments.last() += points[i]
            }
        }
        return segments
    }

    private fun smoothSegment(segment: List<TripPoint>): List<TripPoint> {
        if (segment.size < 3) return segment
        return segment.indices.map { index -> smoothPoint(segment, index) }
    }

    private fun smoothPoint(segment: List<TripPoint>, index: Int): TripPoint {
        val center = segment[index]
        val cosOrigin = cos(Math.toRadians(center.latitude)).let {
            if (abs(it) < MIN_ABS_COS_LATITUDE) MIN_ABS_COS_LATITUDE else it
        }

        var sumWeight = 0.0
        var sumWeightedEast = 0.0
        var sumWeightedNorth = 0.0
        for (neighbor in segment) {
            val dtSeconds = (neighbor.capturedAtEpochSecond - center.capturedAtEpochSecond).toDouble()
            if (abs(dtSeconds) > TIME_WINDOW_SECONDS) continue

            val eastNorth = if (neighbor === center) EastNorth.ORIGIN else localOffsetMeters(center, neighbor, cosOrigin)
            val accuracyWeight = 1.0 / (neighbor.accuracyMeters.toDouble() * neighbor.accuracyMeters.toDouble())
            val timeWeight = exp(-(dtSeconds * dtSeconds) / (2.0 * TIME_KERNEL_SIGMA_SECONDS * TIME_KERNEL_SIGMA_SECONDS))
            val weight = accuracyWeight * timeWeight

            sumWeight += weight
            sumWeightedEast += weight * eastNorth.east
            sumWeightedNorth += weight * eastNorth.north
        }

        // sumWeight is always > 0: the center point always contributes to its own average
        // (dtSeconds = 0, a strictly positive accuracy-weight - accuracy is always > 0 by the time a
        // point reaches here, since RouteSanitizer has already run).
        var east = sumWeightedEast / sumWeight
        var north = sumWeightedNorth / sumWeight

        val displacementMeters = hypot(east, north)
        val maxDisplacementMeters = center.accuracyMeters.toDouble() * DISPLACEMENT_ACCURACY_MULTIPLIER
        if (displacementMeters > maxDisplacementMeters) {
            val scale = maxDisplacementMeters / displacementMeters
            east *= scale
            north *= scale
        }

        return center.copy(
            latitude = center.latitude + Math.toDegrees(north / EARTH_RADIUS_METERS),
            longitude = center.longitude + Math.toDegrees(east / (EARTH_RADIUS_METERS * cosOrigin)),
        )
    }

    private data class EastNorth(val east: Double, val north: Double) {
        companion object {
            val ORIGIN = EastNorth(0.0, 0.0)
        }
    }

    private fun localOffsetMeters(origin: TripPoint, point: TripPoint, cosOrigin: Double): EastNorth {
        val north = Math.toRadians(point.latitude - origin.latitude) * EARTH_RADIUS_METERS
        val east = Math.toRadians(point.longitude - origin.longitude) * EARTH_RADIUS_METERS * cosOrigin
        return EastNorth(east, north)
    }

    private fun sumDistanceMeters(points: List<TripPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += RouteMath.haversineMeters(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
        }
        return total
    }
}
