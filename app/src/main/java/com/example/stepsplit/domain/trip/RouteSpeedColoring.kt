package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The trip-detail map's speed colors: which stretches of a route are drawn in which color, and
 * which are gaps. Pure and JVM-testable, like [RouteSanitizer] and [RouteSmoother], and always fed
 * their output - the same cleaned-up, smoothed points the map draws - never the raw stored points.
 * The colors are worked out from the points' positions and times, never from the phone's own
 * reported speed; that is only used to tell which speeds can be trusted ([trustedSpeeds]).
 *
 * A *stretch* is the line between two consecutive points. For each one:
 *
 * 1. **Gap or not.** A stretch is a [gap][isGap] when more than [GAP_MIN_SECONDS] passed between its
 *    two points *and* they are more than [GAP_MIN_METERS] apart: you moved, but nothing was recorded
 *    on the way (recording was interrupted and resumed, or the GPS lost its fix). The straight line
 *    there is a guess, so it is drawn gray and dashed and has no speed. A long wait with little
 *    movement is not a gap: it is a stop, slow, and red. Location updates only come every 8 m, so a
 *    stop always shows up as one long, short stretch.
 * 2. **Speed.** Distance over time, averaged over about [SMOOTHING_WINDOW_SECONDS] around the
 *    stretch's middle (see [smoothedSpeeds]), so the color doesn't flicker from one point to the
 *    next. The window never reaches across a gap.
 * 3. **Scale.** Per trip: the [LOW_PERCENTILE] of the trip's stretch speeds is the red end and the
 *    [HIGH_PERCENTILE] the green end (see [scaleFor]). A walk at 2-6 km/h and a jeep drive at
 *    10-60 km/h both use the full range, and one odd stretch can't squeeze everything else into a
 *    single color. Speeds outside the ends get the end color. Only [trusted][trustedSpeeds] speeds
 *    set the scale, as they do the top speed ([TripMotion]), so the legend's fast end stays at or
 *    below it; when no speed is trusted, all of them do.
 * 4. **Color.** Red, yellow, green ([colorAt]), with clearly different brightness at each so slow and
 *    fast still differ for someone who can't tell red from green.
 */
object RouteSpeedColoring {
    /** A stretch longer than this in time *and* longer than [GAP_MIN_METERS] in distance is a gap. Six times the 10 s location interval, the same limit [RouteSmoother] uses to stop smoothing across a pause. */
    const val GAP_MIN_SECONDS = 60L

    /** See [GAP_MIN_SECONDS]: about a minute and a half of walking, so a slow walk with a late fix isn't called a gap. */
    const val GAP_MIN_METERS = 100.0

    /** Speeds are averaged over this many seconds, centered on each stretch - about three points at the usual 10 s interval. */
    const val SMOOTHING_WINDOW_SECONDS = 30.0

    /**
     * How much faster than the phone's own reported speed a stretch may be before [trustedSpeeds]
     * stops trusting the speeds around it: 2x plus 1 m/s, the same margin [RouteSanitizer] uses for
     * an out-and-back excursion's legs.
     */
    const val TRUSTED_SPEED_MULTIPLIER = 2.0
    const val TRUSTED_SPEED_MARGIN_MPS = 1.0

    /** The red end of the scale: this share of the trip's stretches are at or below it. */
    const val LOW_PERCENTILE = 0.05

    /** The green end of the scale: this share of the trip's stretches are at or above it. */
    const val HIGH_PERCENTILE = 0.95

    /** The scale never spans less than 1 km/h, so a trip at one steady speed shows as one color, not as noise stretched across red to green. */
    const val MIN_SCALE_SPAN_METERS_PER_SECOND = 1.0 / 3.6

    /** How many color steps [colorize] uses. Neighbouring stretches with the same step are merged into one line, which keeps the map's feature count down without a visible change. */
    const val COLOR_LEVELS = 32

    /** Slow: dark red (CIE lightness about 40). */
    const val SLOW_COLOR = 0xFFB71C1C.toInt()

    /** Middle: yellow (lightness about 83, the brightest of the three). */
    const val MIDDLE_COLOR = 0xFFF9C80E.toInt()

    /** Fast: green (lightness about 65). */
    const val FAST_COLOR = 0xFF3CB44B.toInt()

    /** The red and green ends of one trip's scale, in meters per second. */
    data class SpeedScale(val slowMetersPerSecond: Double, val fastMetersPerSecond: Double) {
        /** Where [metersPerSecond] falls between the ends: 0 at or below slow, 1 at or above fast. */
        fun fractionOf(metersPerSecond: Double): Double =
            ((metersPerSecond - slowMetersPerSecond) / (fastMetersPerSecond - slowMetersPerSecond)).coerceIn(0.0, 1.0)
    }

    /** Consecutive stretches drawn as one line in one [color] (ARGB). [points] has at least two points. */
    data class ColoredRun(val points: List<RoutePoint>, val color: Int)

    /** A [gap][isGap]: drawn gray and dashed from [from] to [to]. */
    data class GapLine(val from: RoutePoint, val to: RoutePoint)

    /** Everything the map needs. [scale] is null when the route has no stretch to color (fewer than two points, or only gaps). */
    data class SpeedColoredRoute(val runs: List<ColoredRun>, val gaps: List<GapLine>, val scale: SpeedScale?)

    fun isGap(from: TripPoint, to: TripPoint): Boolean =
        to.capturedAtEpochSecond - from.capturedAtEpochSecond > GAP_MIN_SECONDS && distanceMeters(from, to) > GAP_MIN_METERS

    /**
     * One speed per stretch (`points.size - 1` of them), in meters per second; null for a gap.
     *
     * Each stretch's speed is the distance covered divided by the time taken within a
     * [SMOOTHING_WINDOW_SECONDS]-long window centered on the stretch's middle. Stretches that only
     * partly overlap the window count in proportion to the overlap. A long stretch, like a stop,
     * fills its own window and keeps its own speed, while its short neighbours are pulled only a
     * little toward it.
     */
    fun smoothedSpeeds(points: List<TripPoint>): List<Double?> {
        if (points.size < 2) return emptyList()
        val stretches = stretchesOf(points)
        val halfWindow = SMOOTHING_WINDOW_SECONDS / 2
        return stretches.indices.map { i ->
            val stretch = stretches[i]
            if (stretch.isGap) return@map null
            val middle = (stretch.start + stretch.end) / 2
            val windowStart = middle - halfWindow
            val windowEnd = middle + halfWindow
            var meters = 0.0
            var seconds = 0.0
            fun add(other: Stretch) {
                val duration = other.end - other.start
                if (duration <= 0.0) {
                    // Two fixes with the same timestamp: count the distance, no time.
                    if (other.start in windowStart..windowEnd) meters += other.meters
                    return
                }
                val overlap = min(other.end, windowEnd) - max(other.start, windowStart)
                if (overlap > 0.0) {
                    meters += other.meters * overlap / duration
                    seconds += overlap
                }
            }
            add(stretch)
            var j = i - 1
            while (j >= 0 && !stretches[j].isGap && stretches[j].end > windowStart) add(stretches[j--])
            j = i + 1
            while (j < stretches.size && !stretches[j].isGap && stretches[j].start < windowEnd) add(stretches[j++])
            if (seconds > 0.0) meters / seconds else 0.0
        }
    }

    /**
     * The trip's own scale from its stretch [speeds] (gaps already left out): the [LOW_PERCENTILE]
     * and [HIGH_PERCENTILE], linearly interpolated between the sorted values, then widened to at least
     * [MIN_SCALE_SPAN_METERS_PER_SECOND] around their middle (never below zero). Null for no speeds.
     *
     * The widening never pushes the fast end past the fastest stretch, which is the trip's top speed
     * ([TripMotion]), so the legend never shows a fast end the trip didn't reach; a trip at one steady
     * speed ends up at the fast end of its scale instead of in the middle. The one exception is a trip
     * that never went faster than [MIN_SCALE_SPAN_METERS_PER_SECOND] (1 km/h), which has no top speed
     * to disagree with.
     */
    fun scaleFor(speeds: List<Double>): SpeedScale? {
        if (speeds.isEmpty()) return null
        val sorted = speeds.sorted()
        var slow = percentile(sorted, LOW_PERCENTILE)
        var fast = percentile(sorted, HIGH_PERCENTILE)
        if (fast - slow < MIN_SCALE_SPAN_METERS_PER_SECOND) {
            val middle = (slow + fast) / 2
            fast = min(middle + MIN_SCALE_SPAN_METERS_PER_SECOND / 2, sorted.last())
            slow = max(0.0, fast - MIN_SCALE_SPAN_METERS_PER_SECOND)
            fast = slow + MIN_SCALE_SPAN_METERS_PER_SECOND
        }
        return SpeedScale(slow, fast)
    }

    /** The color (ARGB) at [fraction] of the way from slow (0) to fast (1): red, through yellow at 0.5, to green. */
    fun colorAt(fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        return if (f <= 0.5) blend(SLOW_COLOR, MIDDLE_COLOR, f * 2) else blend(MIDDLE_COLOR, FAST_COLOR, (f - 0.5) * 2)
    }

    /**
     * For each stretch of [smoothedSpeeds]: whether its speed can count as one the trip really went
     * at - a top speed, or the speed legend's fast end. Not when its [SMOOTHING_WINDOW_SECONDS] window
     * takes in a stretch the phone's own speed contradicts: one between two points that both report a
     * speed, faster than [TRUSTED_SPEED_MULTIPLIER] times the higher of the two plus
     * [TRUSTED_SPEED_MARGIN_MPS] - 13 km/h while the phone reports 4.8 km/h. That is a GPS position
     * catching up after lagging behind (8 m in 14 s, then 110 m in 23 s, straight ahead), which a
     * route's shape and length survive but its speed doesn't: the average spreads it to the
     * stretches around it too, which is why the whole window counts. The phone measures its speed
     * from the satellite signal itself, separately from the position, so it doesn't share the lag.
     *
     * Nothing else changes: the stretch keeps its color, moving time and distance. Driving reports a
     * high speed, so a real fast stretch is never contradicted, and a point without a reported speed
     * contradicts nothing, as before. Gaps have no speed and count as trusted.
     */
    fun trustedSpeeds(points: List<TripPoint>): List<Boolean> {
        if (points.size < 2) return emptyList()
        val stretches = stretchesOf(points)
        val contradicted = points.zipWithNext(::contradictsReportedSpeed)
        val halfWindow = SMOOTHING_WINDOW_SECONDS / 2
        return stretches.indices.map { i ->
            val stretch = stretches[i]
            if (stretch.isGap) return@map true
            if (contradicted[i]) return@map false
            val middle = (stretch.start + stretch.end) / 2
            val windowStart = middle - halfWindow
            val windowEnd = middle + halfWindow
            // The same stretches smoothedSpeeds averages over for this one.
            var j = i - 1
            while (j >= 0 && !stretches[j].isGap && stretches[j].end > windowStart) if (contradicted[j--]) return@map false
            j = i + 1
            while (j < stretches.size && !stretches[j].isGap && stretches[j].start < windowEnd) if (contradicted[j++]) return@map false
            true
        }
    }

    private fun contradictsReportedSpeed(from: TripPoint, to: TripPoint): Boolean {
        val fromReported = from.speedMetersPerSecond ?: return false
        val toReported = to.speedMetersPerSecond ?: return false
        val seconds = to.capturedAtEpochSecond - from.capturedAtEpochSecond
        if (seconds <= 0L) return false
        val reported = max(fromReported, toReported).toDouble()
        return distanceMeters(from, to) / seconds > reported * TRUSTED_SPEED_MULTIPLIER + TRUSTED_SPEED_MARGIN_MPS
    }

    /** The whole route ready to draw: colored runs, gap lines and the trip's scale. */
    fun colorize(points: List<TripPoint>): SpeedColoredRoute {
        val speeds = smoothedSpeeds(points)
        val trusted = trustedSpeeds(points)
        val trustedValues = speeds.filterIndexed { i, speed -> speed != null && trusted[i] }.filterNotNull()
        val scale = scaleFor(trustedValues.ifEmpty { speeds.filterNotNull() }) ?: return SpeedColoredRoute(
            runs = emptyList(),
            gaps = speeds.indices.map { GapLine(points[it].toRoutePoint(), points[it + 1].toRoutePoint()) },
            scale = null,
        )

        val runs = mutableListOf<ColoredRun>()
        val gaps = mutableListOf<GapLine>()
        var runPoints = mutableListOf<RoutePoint>()
        var runLevel = -1
        fun closeRun() {
            if (runPoints.size >= 2) runs += ColoredRun(runPoints, colorAt(runLevel.toDouble() / (COLOR_LEVELS - 1)))
            runPoints = mutableListOf()
            runLevel = -1
        }
        speeds.forEachIndexed { i, speed ->
            val from = points[i].toRoutePoint()
            val to = points[i + 1].toRoutePoint()
            if (speed == null) {
                closeRun()
                gaps += GapLine(from, to)
                return@forEachIndexed
            }
            val level = (scale.fractionOf(speed) * (COLOR_LEVELS - 1)).roundToInt()
            if (level != runLevel) {
                closeRun()
                runPoints += from
                runLevel = level
            }
            runPoints += to
        }
        closeRun()
        return SpeedColoredRoute(runs, gaps, scale)
    }

    private class Stretch(val start: Double, val end: Double, val meters: Double, val isGap: Boolean)

    private fun stretchesOf(points: List<TripPoint>): List<Stretch> = points.zipWithNext { from, to ->
        Stretch(
            start = from.capturedAtEpochSecond.toDouble(),
            end = to.capturedAtEpochSecond.toDouble(),
            meters = distanceMeters(from, to),
            isGap = isGap(from, to),
        )
    }

    /** The [p] percentile (0..1) of [sorted], linearly interpolated between neighbouring values. [sorted] must not be empty. */
    internal fun percentile(sorted: List<Double>, p: Double): Double {
        val position = p * (sorted.size - 1)
        val lower = position.toInt()
        val upper = min(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun blend(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt() and 0xFF
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun distanceMeters(from: TripPoint, to: TripPoint): Double =
        RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    private fun TripPoint.toRoutePoint() = RoutePoint(latitude = latitude, longitude = longitude)
}
