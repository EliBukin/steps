package com.example.stepsplit.domain.route

import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.TripElevation

/**
 * An imported route's distance, climb and extent, worked out once at import and kept with it.
 * Pure and JVM-testable.
 *
 * The climb and descent follow the trip screen's own rules ([TripElevation]): heights smoothed
 * along the distance, then rises and falls counted past a dead band - so a planned route and the
 * trip recorded along it get comparable numbers. Each line is measured on its own and the lines
 * add up; a line with too few heights adds nothing, and with none anywhere there is no climb.
 * Within a line, each segment is smoothed and counted on its own too: nothing joins the gap
 * between two segments, not even the height step across it (a cable car, a recording paused on
 * the way up).
 */
object RouteMeasure {
    data class Measure(
        val distanceMeters: Double,
        val climbMeters: Double?,
        val descentMeters: Double?,
        val pointCount: Int,
        /** Null only for a route with no points and no waypoints, which the import never keeps. */
        val bounds: RouteBounds?,
    )

    fun measure(lines: List<RouteLine>, waypoints: List<RouteWaypoint>): Measure {
        var climb: Double? = null
        var descent: Double? = null
        for (line in lines) {
            val (lineClimb, lineDescent) = climbAndDescent(line) ?: continue
            climb = (climb ?: 0.0) + lineClimb
            descent = (descent ?: 0.0) + lineDescent
        }
        return Measure(
            distanceMeters = lines.sumOf { lengthMeters(it) },
            climbMeters = climb,
            descentMeters = descent,
            pointCount = lines.sumOf { it.pointCount },
            bounds = bounds(lines, waypoints),
        )
    }

    /** The length of [line]: each segment end to end; the gaps between segments don't count. */
    fun lengthMeters(line: RouteLine): Double = line.segments.sumOf { segment ->
        var length = 0.0
        for (i in 1 until segment.size) {
            length += RouteMath.haversineMeters(segment.latitudes[i - 1], segment.longitudes[i - 1], segment.latitudes[i], segment.longitudes[i])
        }
        length
    }

    private fun climbAndDescent(line: RouteLine): Pair<Double, Double>? {
        // Each segment's heights with their distance along the line (which a gap doesn't advance).
        val distances = mutableListOf<DoubleArray>()
        val heights = mutableListOf<DoubleArray>()
        var along = 0.0
        for (segment in line.segments) {
            val segmentDistances = ArrayList<Double>()
            val segmentHeights = ArrayList<Double>()
            for (i in 0 until segment.size) {
                if (i > 0) along += RouteMath.haversineMeters(segment.latitudes[i - 1], segment.longitudes[i - 1], segment.latitudes[i], segment.longitudes[i])
                val height = segment.elevations[i]
                if (height.isFinite()) {
                    segmentDistances += along
                    segmentHeights += height
                }
            }
            if (segmentHeights.isNotEmpty()) {
                distances += segmentDistances.toDoubleArray()
                heights += segmentHeights.toDoubleArray()
            }
        }
        val heightCount = heights.sumOf { it.size }
        if (heightCount < TripElevation.MIN_POINTS_WITH_HEIGHT || heightCount < line.pointCount * TripElevation.MIN_SHARE_WITH_HEIGHT) return null
        if (distances.last().last() - distances.first().first() < TripElevation.MIN_PROFILE_METERS) return null
        var climb = 0.0
        var descent = 0.0
        for ((segmentDistances, segmentHeights) in distances.zip(heights)) {
            val smoothed = TripElevation.smooth(segmentDistances, segmentHeights, TripElevation.smoothingSigmaMeters(segmentDistances))
            val (segmentClimb, segmentDescent) = TripElevation.climbAndDescent(smoothed)
            climb += segmentClimb
            descent += segmentDescent
        }
        return climb to descent
    }

    private fun bounds(lines: List<RouteLine>, waypoints: List<RouteWaypoint>): RouteBounds? {
        var minLatitude = Double.POSITIVE_INFINITY
        var minLongitude = Double.POSITIVE_INFINITY
        var maxLatitude = Double.NEGATIVE_INFINITY
        var maxLongitude = Double.NEGATIVE_INFINITY
        fun add(latitude: Double, longitude: Double) {
            if (latitude < minLatitude) minLatitude = latitude
            if (latitude > maxLatitude) maxLatitude = latitude
            if (longitude < minLongitude) minLongitude = longitude
            if (longitude > maxLongitude) maxLongitude = longitude
        }
        for (line in lines) for (segment in line.segments) for (i in 0 until segment.size) add(segment.latitudes[i], segment.longitudes[i])
        for (waypoint in waypoints) add(waypoint.latitude, waypoint.longitude)
        return if (minLatitude.isInfinite()) null else RouteBounds(minLatitude, minLongitude, maxLatitude, maxLongitude)
    }
}
