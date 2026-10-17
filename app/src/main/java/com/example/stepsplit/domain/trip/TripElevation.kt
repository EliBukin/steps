package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.exp
import kotlin.math.max

/**
 * A trip's total climb and descent, its highest and lowest point, and a height chart along its
 * distance. Pure and JVM-testable, like [RouteSpeedColoring], and fed the same cleaned-up, smoothed
 * route the trip's map draws (`RouteSanitizer -> RouteSmoother`), with whatever heights the caller
 * put on the points: above sea level where the phone can convert them, otherwise as stored (see
 * `TripDetailViewModel`). Only differences between heights matter here, so the climb is the same
 * either way.
 *
 * GPS height is noisy: a phone's fixes scatter 10-20 m up and down around the true height, so
 * adding up every small rise and fall turns a flat 4 km walk into well over a kilometer of "climb".
 * Two well-known steps keep the noise out:
 *
 * 1. **Smoothing along the distance.** Each height is replaced by a Gaussian-weighted average of the
 *    heights around it along the route, with a spread ([smoothingSigmaMeters]) of
 *    [MIN_SMOOTHING_SIGMA_METERS], or [SMOOTHING_SPACINGS] times the usual distance between points if
 *    that is more (a drive's points are farther apart). Along the distance, not the time: the points
 *    from a long stop are all at one place, so they are averaged together instead of their drift
 *    being drawn as hills. Smoothing rounds off sharp peaks and the two ends a little - about 5 m on
 *    a 10% slope.
 * 2. **Counting with a dead band (hysteresis).** Climb and descent come from the smoothed heights'
 *    peaks and valleys, but a turn only counts once the height has come back by at least
 *    [HYSTERESIS_METERS] from the last peak or valley. Smaller ups and downs are taken as noise and
 *    left out; a real climb is counted whole, from valley to peak.
 *
 * In tests with up to ±15 m of random height noise on every fix, a flat 4 km walk comes out at
 * 1 m of climb on average and at most 12 m (adding up every rise: about 1,500 m), a 300 m climb
 * within 20 m, and 15 m rolling hills keep most of their climb. What smoothing can't remove is a
 * slow swell of the GPS height over minutes, which looks just like a real gentle hill: a ±3 m swell
 * on top of that noise already makes the flat walk 10 m of climb on average. Telling the two apart
 * needs a barometer. So the numbers are estimates, good to about 10-20%.
 *
 * The highest and lowest point are those of the smoothed heights, so a single fix 20 m too high
 * doesn't become the summit. They are still only as good as GPS height: often 5-10 m off.
 *
 * Points without a height (`TripPoint.altitudeMeters` is null) are left out. When fewer than
 * [MIN_POINTS_WITH_HEIGHT] points, or under [MIN_SHARE_WITH_HEIGHT] of the route's points, have one,
 * or they span less than [MIN_PROFILE_METERS] of the route, there is no elevation at all ([measure]
 * returns null), rather than numbers made from scraps.
 */
object TripElevation {
    /** Fewer points with a height than this, and the trip has no elevation. */
    const val MIN_POINTS_WITH_HEIGHT = 10

    /** Under this share of the route's points with a height, and the trip has no elevation. */
    const val MIN_SHARE_WITH_HEIGHT = 0.5

    /** Points with a height spanning less of the route than this (in meters), and the trip has no elevation: too short for a height chart. */
    const val MIN_PROFILE_METERS = 100.0

    /**
     * The smallest smoothing spread (the Gaussian's standard deviation), in meters along the route. A
     * walk's points are about 13 m apart, so this averages about 18 points' worth, and ±15 m of noise on
     * each fix shrinks to about ±2 m. Wider loses real hills; narrower lets noise through.
     */
    const val MIN_SMOOTHING_SIGMA_METERS = 65.0

    /** The smoothing spread is at least this many times the median distance between points with a height, so a drive with points 50-150 m apart is smoothed over about as many points as a walk. */
    const val SMOOTHING_SPACINGS = 2.0

    /** A rise or fall smaller than this (in meters) after smoothing is noise: about five times the noise left after smoothing ±15 m of fix noise, and still well under a real hill. */
    const val HYSTERESIS_METERS = 10.0

    /** The height chart gets at most this many points, evenly spaced along the distance: plenty for a chart a phone's width across. */
    const val PROFILE_SAMPLES = 100

    /** One point of the height chart: [heightMeters] (smoothed) at [distanceMeters] along the route from its start. */
    data class ProfilePoint(val distanceMeters: Double, val heightMeters: Double)

    /**
     * A trip's elevation, all in meters and from the smoothed heights. [profile] has at least two
     * points, in order of distance, and stays between [lowestMeters] and [highestMeters].
     */
    data class Elevation(
        val climbMeters: Double,
        val descentMeters: Double,
        val highestMeters: Double,
        val lowestMeters: Double,
        val profile: List<ProfilePoint>,
    )

    /** The elevation of [route], the trip's `RouteSanitizer -> RouteSmoother` points; null when too few of them have a height. */
    fun measure(route: List<TripPoint>): Elevation? {
        val withHeight = route.indices.filter { route[it].altitudeMeters?.isFinite() == true }
        if (withHeight.size < MIN_POINTS_WITH_HEIGHT || withHeight.size < route.size * MIN_SHARE_WITH_HEIGHT) return null

        // Distance along the whole route, points without a height included, so the chart's distance
        // matches the trip's.
        val along = DoubleArray(route.size)
        for (i in 1 until route.size) {
            along[i] = along[i - 1] + RouteMath.haversineMeters(route[i - 1].latitude, route[i - 1].longitude, route[i].latitude, route[i].longitude)
        }
        val distances = DoubleArray(withHeight.size) { along[withHeight[it]] }
        val heights = DoubleArray(withHeight.size) { route[withHeight[it]].altitudeMeters!! }
        if (distances.last() - distances.first() < MIN_PROFILE_METERS) return null

        val smoothed = smooth(distances, heights, smoothingSigmaMeters(distances))
        val (climb, descent) = climbAndDescent(smoothed)
        return Elevation(
            climbMeters = climb,
            descentMeters = descent,
            highestMeters = smoothed.max(),
            lowestMeters = smoothed.min(),
            profile = profile(distances, smoothed),
        )
    }

    /** See [MIN_SMOOTHING_SIGMA_METERS] and [SMOOTHING_SPACINGS]. [distances] are in route order. */
    internal fun smoothingSigmaMeters(distances: DoubleArray): Double {
        val spacings = (1 until distances.size).map { distances[it] - distances[it - 1] }.filter { it > 0.0 }.sorted()
        val median = if (spacings.isEmpty()) 0.0 else spacings[spacings.size / 2]
        return max(MIN_SMOOTHING_SIGMA_METERS, SMOOTHING_SPACINGS * median)
    }

    /** Each height replaced by the Gaussian-weighted average of the heights within three [sigma] of it along the route. */
    internal fun smooth(distances: DoubleArray, heights: DoubleArray, sigma: Double): DoubleArray {
        val reach = 3 * sigma
        val twoSigmaSquared = 2 * sigma * sigma
        val smoothed = DoubleArray(heights.size)
        var first = 0
        for (i in heights.indices) {
            while (distances[i] - distances[first] > reach) first++
            var weights = 0.0
            var sum = 0.0
            var j = first
            while (j < heights.size && distances[j] - distances[i] <= reach) {
                val offset = distances[j] - distances[i]
                val weight = exp(-offset * offset / twoSigmaSquared)
                weights += weight
                sum += weight * heights[j]
                j++
            }
            smoothed[i] = sum / weights
        }
        return smoothed
    }

    /**
     * Total climb and descent of [heights] with a [deadBand]: follow the heights up (or down) to a peak
     * (or valley), and count the stretch from the last turn to it once the heights have come back from
     * it by at least the dead band. Until the first such move, the lowest and highest heights so far
     * are the candidate turns, so a first climb counts from the bottom of any small dip before it. The
     * last stretch, from the last turn to the highest or lowest height after it, counts once it is out
     * of the dead band.
     */
    internal fun climbAndDescent(heights: DoubleArray, deadBand: Double = HYSTERESIS_METERS): Pair<Double, Double> {
        var climb = 0.0
        var descent = 0.0
        var lowestSoFar = heights.first()
        var highestSoFar = heights.first()
        var lastTurn = heights.first()
        var extreme = lastTurn
        var direction = 0 // 0 until the heights first move by the dead band, then 1 going up, -1 going down
        for (height in heights) {
            when (direction) {
                0 -> {
                    lowestSoFar = minOf(lowestSoFar, height)
                    highestSoFar = maxOf(highestSoFar, height)
                    if (height - lowestSoFar >= deadBand) {
                        direction = 1
                        lastTurn = lowestSoFar
                        extreme = height
                    } else if (highestSoFar - height >= deadBand) {
                        direction = -1
                        lastTurn = highestSoFar
                        extreme = height
                    }
                }
                1 -> if (height > extreme) {
                    extreme = height
                } else if (extreme - height >= deadBand) {
                    climb += extreme - lastTurn
                    lastTurn = extreme
                    direction = -1
                    extreme = height
                }
                else -> if (height < extreme) {
                    extreme = height
                } else if (height - extreme >= deadBand) {
                    descent += lastTurn - extreme
                    lastTurn = extreme
                    direction = 1
                    extreme = height
                }
            }
        }
        when (direction) {
            1 -> climb += extreme - lastTurn
            -1 -> descent += lastTurn - extreme
        }
        return climb to descent
    }

    /** [smoothed] at [PROFILE_SAMPLES] evenly spaced distances (or at every point, if there are fewer), linearly interpolated. */
    private fun profile(distances: DoubleArray, smoothed: DoubleArray): List<ProfilePoint> {
        if (smoothed.size <= PROFILE_SAMPLES) return distances.indices.map { ProfilePoint(distances[it], smoothed[it]) }
        val start = distances.first()
        val step = (distances.last() - start) / (PROFILE_SAMPLES - 1)
        var k = 0
        return List(PROFILE_SAMPLES) { n ->
            val at = if (n == PROFILE_SAMPLES - 1) distances.last() else start + n * step
            while (k < distances.size - 2 && distances[k + 1] < at) k++
            val span = distances[k + 1] - distances[k]
            val t = if (span > 0.0) ((at - distances[k]) / span).coerceIn(0.0, 1.0) else 0.0
            ProfilePoint(at, smoothed[k] + (smoothed[k + 1] - smoothed[k]) * t)
        }
    }
}
