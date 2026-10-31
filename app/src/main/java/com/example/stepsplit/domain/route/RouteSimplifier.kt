package com.example.stepsplit.domain.route

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Fewer points for the same shape (Douglas-Peucker): a point is left out when the line without it
 * stays within a tolerance of it. Pure and JVM-testable.
 *
 * - **Drawing** ([forDrawing]): a planned route can have tens of thousands of points, and the map
 *   has to hand all of them to the map library each time it changes. At [DRAWING_TOLERANCE_METERS]
 *   the line moves by less than its own width at any zoom; a longer line gets a bigger tolerance,
 *   so it draws with at most [MAX_DRAWN_POINTS_PER_LINE] points.
 * - **The import limit** ([fitToLimit]): a file with more points than the app keeps is thinned
 *   the same way, with the smallest tolerance that fits, so its shape stays.
 */
object RouteSimplifier {
    const val DRAWING_TOLERANCE_METERS = 2.0
    const val MAX_DRAWN_POINTS_PER_LINE = 5_000

    /** Meters per degree of latitude (and of longitude at the equator), close enough for a tolerance. */
    private const val METERS_PER_DEGREE = 111_320.0

    /** Past this, a file's points are all but straight lines: what's still over the limit is cut off. */
    private const val MAX_TOLERANCE_METERS = 100_000.0

    /** [line] as the map draws it - see the class doc comment. */
    fun forDrawing(line: RouteLine): RouteLine {
        if (line.pointCount <= MAX_DRAWN_POINTS_PER_LINE / 2) return line
        var tolerance = DRAWING_TOLERANCE_METERS
        while (true) {
            val simplified = line.segments.map { simplify(it, tolerance) }
            if (simplified.sumOf { it.size } <= MAX_DRAWN_POINTS_PER_LINE || tolerance >= MAX_TOLERANCE_METERS) return RouteLine(simplified)
            tolerance *= 2
        }
    }

    /**
     * [lines] with at most [maxPoints] points in all - unchanged when they already fit. Each pass
     * doubles the tolerance, starting from [DRAWING_TOLERANCE_METERS]. A file of thousands of
     * two-point segments can't fit that way; then the segments past the limit are left out.
     */
    fun fitToLimit(lines: List<RouteLine>, maxPoints: Int): List<RouteLine> {
        if (lines.sumOf { it.pointCount } <= maxPoints) return lines
        var tolerance = DRAWING_TOLERANCE_METERS
        var simplified: List<RouteLine>
        while (true) {
            simplified = lines.map { line -> RouteLine(line.segments.map { simplify(it, tolerance) }) }
            if (simplified.sumOf { it.pointCount } <= maxPoints || tolerance >= MAX_TOLERANCE_METERS) break
            tolerance *= 2
        }
        if (simplified.sumOf { it.pointCount } <= maxPoints) return simplified
        var left = maxPoints
        val kept = mutableListOf<RouteLine>()
        for (line in simplified) {
            val segments = mutableListOf<RouteSegment>()
            for (segment in line.segments) {
                if (segment.size > left) break
                segments += segment
                left -= segment.size
            }
            if (segments.isNotEmpty()) kept += RouteLine(segments)
            if (segments.size < line.segments.size) break
        }
        return kept
    }

    /** [segment] without the points within [toleranceMeters] of the line through its neighbors that stay. */
    fun simplify(segment: RouteSegment, toleranceMeters: Double): RouteSegment {
        val kept = keptIndices(segment, toleranceMeters)
        return if (kept.size == segment.size) segment else segment.pick(kept)
    }

    /** The indices Douglas-Peucker keeps at [toleranceMeters], in order; always the first and the last. */
    fun keptIndices(segment: RouteSegment, toleranceMeters: Double): IntArray {
        val n = segment.size
        if (n <= 2) return IntArray(n) { it }
        // Flat x/y in meters around the segment's middle latitude: plenty for distances of a few meters.
        val midLatitude = segment.latitudes[n / 2]
        val xScale = METERS_PER_DEGREE * cos(Math.toRadians(midLatitude))
        val x = DoubleArray(n) { (segment.longitudes[it] - segment.longitudes[0]) * xScale }
        val y = DoubleArray(n) { (segment.latitudes[it] - segment.latitudes[0]) * METERS_PER_DEGREE }

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        // An explicit stack: a long, winding track would overflow a recursive one.
        val stack = ArrayDeque<Long>()
        stack.addLast(pack(0, n - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = (range ushr 32).toInt()
            val last = range.toInt()
            var farthest = -1
            var farthestDistance = toleranceMeters
            for (i in first + 1 until last) {
                val distance = distanceToSegment(x[i], y[i], x[first], y[first], x[last], y[last])
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = i
                }
            }
            if (farthest >= 0) {
                keep[farthest] = true
                if (farthest - first > 1) stack.addLast(pack(first, farthest))
                if (last - farthest > 1) stack.addLast(pack(farthest, last))
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val indices = IntArray(count)
        var j = 0
        for (i in 0 until n) if (keep[i]) indices[j++] = i
        return indices
    }

    private fun pack(first: Int, last: Int): Long = (first.toLong() shl 32) or (last.toLong() and 0xFFFFFFFFL)

    /** Distance from (px, py) to the segment from (ax, ay) to (bx, by), not to the infinite line through them. */
    internal fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }
}
