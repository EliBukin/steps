package com.example.stepsplit.domain.route

/**
 * One unbroken piece of an imported route: its points in order, as three parallel arrays - a
 * planned route can have tens of thousands of points, and arrays keep them compact. A height is
 * [Double.NaN] where the file gave none. At least two points.
 */
class RouteSegment(val latitudes: DoubleArray, val longitudes: DoubleArray, val elevations: DoubleArray) {
    init {
        require(latitudes.size == longitudes.size && latitudes.size == elevations.size) { "Mismatched point arrays" }
        require(latitudes.size >= 2) { "A segment needs at least two points" }
    }

    val size: Int get() = latitudes.size

    /** The points at [indices] (in order), as a new segment. */
    fun pick(indices: IntArray): RouteSegment = RouteSegment(
        DoubleArray(indices.size) { latitudes[indices[it]] },
        DoubleArray(indices.size) { longitudes[indices[it]] },
        DoubleArray(indices.size) { elevations[indices[it]] },
    )
}

/**
 * One track (`trk`) or route (`rte`) of a GPX file: its segments in order - a track's `trkseg`s,
 * or a route's one list of points. Nothing joins two segments: GPX splits a track where the
 * recording stopped, so the map leaves the gap open, and the distance doesn't count it.
 */
class RouteLine(val segments: List<RouteSegment>) {
    init {
        require(segments.isNotEmpty()) { "A line needs at least one segment" }
    }

    val pointCount: Int get() = segments.sumOf { it.size }
}

/** A GPX waypoint (`wpt`): a named place along or near the route - a spring, a camp, a turn. */
data class RouteWaypoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val name: String?,
    /** The file's description (`desc`) or comment (`cmt`) for it. */
    val note: String?,
)

/** An imported route's lines and waypoints - what the map draws and the route strip follows. */
class RouteGeometry(val lines: List<RouteLine>, val waypoints: List<RouteWaypoint>)

/** The smallest latitude/longitude box around a route's points and waypoints. */
data class RouteBounds(val minLatitude: Double, val minLongitude: Double, val maxLatitude: Double, val maxLongitude: Double)
