package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType

/**
 * A trip's moving time and top speed, for the trip detail screen. Pure and JVM-testable, like
 * [RouteSpeedColoring], and always fed the same cleaned-up, smoothed route the trip's map draws
 * (`RouteSanitizer -> RouteSmoother`), with the same per-stretch speeds the map's colors use
 * ([RouteSpeedColoring.smoothedSpeeds]) - never the raw stored points or the phone's reported speed.
 *
 * **Moving time.** The trip's duration counts every stop, like a 20-minute break on a hike. Moving
 * time counts only the stretches (the line between two consecutive points) whose smoothed speed is
 * above the trip type's threshold, each with its whole duration:
 * - **Walk: above [WALK_MOVING_SPEED_KMH].** Location updates only come every 8 m, so a stop is one
 *   long stretch with almost no distance: a 5-minute break with 10 m of GPS drift is 0.1 km/h, and
 *   drift on a longer break stays well under 1 km/h. Even a very slow scramble up rocks is faster
 *   than 1 km/h, so walking always counts.
 * - **Drive: above [DRIVE_MOVING_SPEED_KMH].** In a vehicle, the stretch that holds a stop also
 *   holds the drive-off up to the next point, tens of meters at vehicle acceleration, which lifts a
 *   stop's speed: a 2-minute stop plus 50 m of drive-off is 1.5 km/h. 3 km/h keeps those out while a
 *   4x4 crawling over rocks, usually faster than a walking pace, still counts as moving.
 * - A short pause, under about half a minute, is averaged with the moving around it and counts as
 *   moving, as in most sports apps.
 *
 * **Gaps.** A [gap][RouteSpeedColoring.isGap] (more than a minute *and* 100 m with no point in between,
 * from an interrupted recording or a lost GPS fix) has no smoothed speed. It counts as moving when its
 * straight-line speed is above the threshold: the straight line is the least ground you covered in
 * that time, so its speed is the least you averaged. A short dropout while walking or driving counts;
 * a long interruption spent mostly standing, whose straight line is short for its time, doesn't. A
 * gap that was partly a stop counts whole: there is nothing recorded to tell the parts apart.
 *
 * **Top speed.** The highest smoothed stretch speed, never a single GPS reading: each speed is
 * averaged over about [RouteSpeedColoring.SMOOTHING_WINDOW_SECONDS], so one bad fix can't make a silly
 * number, and gaps have no speed. Only a [trusted][RouteSpeedColoring.trustedSpeeds] speed counts: not
 * one whose average takes in a stretch far faster than the phone's own reported speed, like a GPS
 * position catching up after lagging behind. It is the same data the speed legend's scale comes
 * from, and [RouteSpeedColoring.scaleFor] never puts the legend's fast end above it. A trip with no
 * trusted moving stretch has no top speed.
 */
object TripMotion {
    /** A walk's stretch is moving above this speed. */
    const val WALK_MOVING_SPEED_KMH = 1.0

    /** A drive's stretch is moving above this speed. */
    const val DRIVE_MOVING_SPEED_KMH = 3.0

    /**
     * [movingSeconds]: the time spent moving, never more than the time between the route's first and
     * last point. [topSpeedMetersPerSecond]: the highest smoothed stretch speed, or null when no
     * stretch was moving.
     */
    data class Motion(val movingSeconds: Long, val topSpeedMetersPerSecond: Double?)

    fun movingSpeedKmh(type: TripType): Double = when (type) {
        TripType.WALK -> WALK_MOVING_SPEED_KMH
        TripType.DRIVE -> DRIVE_MOVING_SPEED_KMH
    }

    /** Moving time and top speed of [route], the trip's `RouteSanitizer -> RouteSmoother` points, counted as a [type] trip. */
    fun measure(route: List<TripPoint>, type: TripType): Motion {
        val threshold = movingSpeedKmh(type) / KMH_PER_METER_PER_SECOND
        val speeds = RouteSpeedColoring.smoothedSpeeds(route)
        val trusted = RouteSpeedColoring.trustedSpeeds(route)
        var movingSeconds = 0L
        var top: Double? = null
        speeds.forEachIndexed { i, smoothed ->
            val from = route[i]
            val to = route[i + 1]
            val seconds = to.capturedAtEpochSecond - from.capturedAtEpochSecond
            if (seconds <= 0L) return@forEachIndexed
            val speed = smoothed ?: (RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude) / seconds)
            if (speed > threshold) movingSeconds += seconds
            if (smoothed != null && trusted[i] && smoothed > threshold && smoothed > (top ?: 0.0)) top = smoothed
        }
        return Motion(movingSeconds, top)
    }

    private const val KMH_PER_METER_PER_SECOND = 3.6
}
