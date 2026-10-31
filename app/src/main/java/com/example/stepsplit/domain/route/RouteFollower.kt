package com.example.stepsplit.domain.route

import com.example.stepsplit.domain.trip.RouteMath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the phone is along one imported route, fix after fix - for the Map tab's route strip
 * ("3.2 km done · 9.2 km to the end"). Pure and JVM-testable; nothing here is stored.
 *
 * Each fix is matched to the closest place on the route. Where the route passes the same place
 * twice - an out-and-back on one trail, or a loop that ends where it started - more than one
 * place is about as close, and the follower picks the one along the route nearest to where the
 * last fix was, so it doesn't jump to the other leg - ahead rather than back, when both are as
 * near (at the turn of an out-and-back, the way back is ahead). With no earlier fix (or none
 * near), it picks the earliest along the route: at the start of a loop you're at its start, not
 * its end.
 *
 * Distances along the route are within one line (one track of the file - one day of a three-day
 * file), with each segment end to end; a gap between segments counts nothing.
 */
class RouteFollower(lines: List<RouteLine>) {

    /** A match: [alongMeters] from the start of line [lineIndex] (of [lineLengthMeters]), [offMeters] from the route. */
    data class Match(val lineIndex: Int, val alongMeters: Double, val offMeters: Double, val lineLengthMeters: Double)

    /** One line ready for matching: every point's distance along it. */
    private class Prepared(val segments: List<RouteSegment>, val along: List<DoubleArray>, val length: Double)

    private val prepared: List<Prepared> = lines.map { line ->
        var total = 0.0
        val along = line.segments.map { segment ->
            DoubleArray(segment.size).also { distances ->
                distances[0] = total
                for (i in 1 until segment.size) {
                    total += RouteMath.haversineMeters(segment.latitudes[i - 1], segment.longitudes[i - 1], segment.latitudes[i], segment.longitudes[i])
                    distances[i] = total
                }
            }
        }
        Prepared(line.segments, along, total)
    }

    private var last: Match? = null

    /** The route's first point: where it starts. */
    val startLatitude: Double = lines.first().segments.first().latitudes[0]
    val startLongitude: Double = lines.first().segments.first().longitudes[0]

    /** Where ([latitude], [longitude]) is along the route; remembered for the next fix. */
    fun locate(latitude: Double, longitude: Double): Match {
        val xScale = METERS_PER_DEGREE * cos(Math.toRadians(latitude))

        // Every edge's distance from the fix, in route order, and the closest of them.
        val edgeCount = prepared.sumOf { line -> line.segments.sumOf { it.size - 1 } }
        val offs = DoubleArray(edgeCount)
        val ts = DoubleArray(edgeCount)
        var minOff = Double.POSITIVE_INFINITY
        var e = 0
        for (line in prepared) for (segment in line.segments) {
            for (i in 0 until segment.size - 1) {
                val ax = (segment.longitudes[i] - longitude) * xScale
                val ay = (segment.latitudes[i] - latitude) * METERS_PER_DEGREE
                val bx = (segment.longitudes[i + 1] - longitude) * xScale
                val by = (segment.latitudes[i + 1] - latitude) * METERS_PER_DEGREE
                val dx = bx - ax
                val dy = by - ay
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
                val cx = ax + t * dx
                val cy = ay + t * dy
                offs[e] = sqrt(cx * cx + cy * cy)
                ts[e] = t
                if (offs[e] < minOff) minOff = offs[e]
                e++
            }
        }

        // The places about as close as the closest: each run of edges within the slack is one
        // pass of the route by the fix, and its closest edge is that pass's match.
        val candidates = mutableListOf<Match>()
        val limit = minOff + SAME_DISTANCE_SLACK_METERS
        e = 0
        for ((lineIndex, line) in prepared.withIndex()) {
            var best: Match? = null
            for ((s, segment) in line.segments.withIndex()) {
                val along = line.along[s]
                for (i in 0 until segment.size - 1) {
                    val off = offs[e]
                    if (off <= limit) {
                        if (best == null || off < best.offMeters) {
                            best = Match(lineIndex, along[i] + ts[e] * (along[i + 1] - along[i]), off, line.length)
                        }
                    } else if (best != null) {
                        candidates += best
                        best = null
                    }
                    e++
                }
                // A gap between segments ends a pass too.
                if (best != null) {
                    candidates += best
                    best = null
                }
            }
        }

        val previous = last
        val near = previous?.let { prev ->
            candidates.filter { it.lineIndex == prev.lineIndex && abs(it.alongMeters - prev.alongMeters) <= CONTINUITY_METERS }
                .minByOrNull { candidate ->
                    val moved = candidate.alongMeters - prev.alongMeters
                    if (moved >= 0) moved else -moved * BACKWARD_WEIGHT
                }
        }
        val chosen = near ?: candidates.minWith(compareBy<Match>({ it.lineIndex }, { it.alongMeters }))
        last = chosen
        return chosen
    }

    /** What the route strip says for a fix at ([latitude], [longitude]) with [accuracyMeters] - see [Progress]. */
    fun progress(latitude: Double, longitude: Double, accuracyMeters: Float?): Progress {
        val match = locate(latitude, longitude)
        val left = max(0.0, match.lineLengthMeters - match.alongMeters)
        val onRoute = max(ON_ROUTE_METERS, min(accuracyMeters?.toDouble() ?: 0.0, MAX_ON_ROUTE_METERS))
        return when {
            match.offMeters <= onRoute -> Progress.OnRoute(doneMeters = match.alongMeters, leftMeters = left, offMeters = match.offMeters)
            match.offMeters <= NEAR_METERS -> Progress.OffRoute(offMeters = match.offMeters, leftMeters = left)
            else -> Progress.Far(toStartMeters = RouteMath.haversineMeters(latitude, longitude, startLatitude, startLongitude), offMeters = match.offMeters)
        }
    }

    /** Where the phone is, as the route strip says it. [offMeters] picks the closest of several routes on the map. */
    sealed interface Progress {
        val offMeters: Double

        /** On the route: [doneMeters] from the start of its line, [leftMeters] to its end. */
        data class OnRoute(val doneMeters: Double, val leftMeters: Double, override val offMeters: Double) : Progress

        /** Near the route but off it: [offMeters] away, [leftMeters] from the closest place on it to the end. */
        data class OffRoute(override val offMeters: Double, val leftMeters: Double) : Progress

        /** Far from the route: [toStartMeters] in a straight line to where it starts. */
        data class Far(val toStartMeters: Double, override val offMeters: Double) : Progress
    }

    companion object {
        /**
         * Closer than this to the route is on it. A phone's fix is often 5-20 m off, and a trail
         * a few meters wide; a bigger fix circle counts too (see [MAX_ON_ROUTE_METERS]).
         */
        const val ON_ROUTE_METERS = 50.0

        /** The most a fix's own inaccuracy widens "on the route". */
        const val MAX_ON_ROUTE_METERS = 150.0

        /** Up to this far from the route the strip says how far off it you are; further, how far its start is. */
        const val NEAR_METERS = 1_000.0

        /** Passes of the route within this of the closest one count as just as close: GPS noise can't tell them apart. */
        const val SAME_DISTANCE_SLACK_METERS = 25.0

        /** A match this far along the route from the last one is still "the same leg". */
        const val CONTINUITY_METERS = 1_500.0

        /** Going back along the route counts this many times as far as going ahead, when choosing between legs. */
        const val BACKWARD_WEIGHT = 2.0

        private const val METERS_PER_DEGREE = 111_320.0
    }
}
