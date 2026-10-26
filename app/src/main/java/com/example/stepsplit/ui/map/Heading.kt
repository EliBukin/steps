package com.example.stepsplit.ui.map

import com.example.stepsplit.data.trip.MapFix
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure heading rules for the Map tab's compass cone and its "map turns with me" mode, kept apart
 * from the sensor and the screen so the JVM tests can check them. Angles are degrees clockwise from
 * north, from 0 up to 360.
 */
internal object HeadingMath {
    /**
     * Which way the user faces, from the phone's rotation matrix [r] (3x3, row-major, from phone axes
     * to east, north and up - as `SensorManager.getRotationMatrixFromVector` gives it) and the screen's
     * [displayRotation] (0 to 3, as `Surface.ROTATION_0` to `ROTATION_270`). Relative to whatever
     * north the matrix uses: magnetic north for the rotation vector sensor.
     *
     * It's the direction of the screen's top edge plus the direction out of the phone's back: lying
     * flat in the hand only the first counts, held upright like a camera only the second, and in
     * between both point the same way - so the heading doesn't jump as the phone tilts. Null when
     * that direction is (nearly) straight up or down, e.g. a phone held overhead, screen down: then
     * there's no telling.
     */
    fun azimuthDegrees(r: FloatArray, displayRotation: Int): Float? {
        require(r.size == 9) { "A 3x3 rotation matrix has 9 values" }
        // The screen's top edge, in phone axes: Android draws the screen turned against the phone.
        val (upX, upY) = when (displayRotation) {
            1 -> 1f to 0f
            2 -> 0f to -1f
            3 -> -1f to 0f
            else -> 0f to 1f
        }
        // Plus the phone's back (minus its z axis), turned into east and north.
        val east = r[0] * upX + r[1] * upY - r[2]
        val north = r[3] * upX + r[4] * upY - r[5]
        if (hypot(east, north) < MIN_HORIZONTAL) return null
        return normalize(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
    }

    /** [degrees] brought into 0 up to 360. */
    fun normalize(degrees: Float): Float {
        val d = degrees % 360f
        return if (d < 0f) d + 360f else d
    }

    /** The shortest turn from [from] to [to]: -180 up to 180, positive clockwise. */
    fun shortestTurn(from: Float, to: Float): Float {
        val d = normalize(to - from)
        return if (d > 180f) d - 360f else d
    }

    /**
     * One step of smoothing toward a new reading: [weight] of the way from [previous] (none yet:
     * straight to [reading]) along the shortest turn, so 359 and 1 average to 0, not 180.
     */
    fun smooth(previous: Float?, reading: Float, weight: Float): Float =
        if (previous == null) reading else normalize(previous + shortestTurn(previous, reading) * weight)

    /** Below this (out of about 1.4), the facing direction is too close to vertical to have a heading. */
    private const val MIN_HORIZONTAL = 0.2f
}

/**
 * What "map turns with me" turns the map by: the phone's compass while walking or standing, the GPS
 * direction of travel ("course") while moving fast.
 *
 * - In a car or on a bike the phone's compass is unreliable (a holder at any angle, metal, magnets)
 *   and the course is good, so from [COURSE_FROM_METERS_PER_SECOND] (15 km/h) the map follows the
 *   course; back below [COMPASS_BELOW_METERS_PER_SECOND] (10 km/h) it follows the compass again. The
 *   gap between the two keeps it from switching back and forth around one speed.
 * - The course counts only from a fresh fix ([MAX_COURSE_AGE_NANOS]); the location client gives one
 *   only while moving (see `FusedMapLocationClient`).
 * - A phone without a compass uses the course at any speed it has one.
 * - With neither, the map stays turned as it is.
 */
internal object MapBearing {
    const val COURSE_FROM_METERS_PER_SECOND = 15f / 3.6f
    const val COMPASS_BELOW_METERS_PER_SECOND = 10f / 3.6f
    const val MAX_COURSE_AGE_NANOS = 5_000_000_000L

    enum class Source { COMPASS, COURSE }

    /** Turn the map to [bearing] (null: leave it as it is), taken from [source]. */
    data class Choice(val bearing: Float?, val source: Source)

    /**
     * Which way to turn the map now, from the phone's [compass] heading (null when it has none), the
     * newest [fix], the time [nowElapsedNanos] (on the fix's clock), and the [previous] choice's source.
     */
    fun choose(compass: Float?, fix: MapFix?, nowElapsedNanos: Long, previous: Source): Choice {
        val course = fix?.bearingDegrees?.takeIf { nowElapsedNanos - fix.elapsedRealtimeNanos <= MAX_COURSE_AGE_NANOS }
        val speed = fix?.speedMetersPerSecond ?: 0f
        val fast = speed >= if (previous == Source.COURSE) COMPASS_BELOW_METERS_PER_SECOND else COURSE_FROM_METERS_PER_SECOND
        return when {
            course != null && (fast || compass == null) -> Choice(HeadingMath.normalize(course), Source.COURSE)
            compass != null -> Choice(compass, Source.COMPASS)
            else -> Choice(null, previous)
        }
    }
}
