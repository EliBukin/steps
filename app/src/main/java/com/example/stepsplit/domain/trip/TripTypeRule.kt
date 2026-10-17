package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripType

/**
 * The automatic Walk/Drive suggestion for a trip. Pure and JVM-testable, like [RouteSpeedColoring],
 * and always fed the same cleaned-up, smoothed route the trip's map draws
 * (`RouteSanitizer -> RouteSmoother`), never the raw stored points or the phone's reported speed.
 *
 * **The rule.** Take one speed per stretch from [RouteSpeedColoring.smoothedSpeeds] (gaps left out).
 * The trip is a [TripType.DRIVE] when its [PERCENTILE] stretch speed is above [DRIVE_SPEED_KMH],
 * otherwise a [TripType.WALK]. In words: a drive is a trip where at least a tenth of the stretches go
 * faster than anyone walks or jogs.
 *
 * Why these numbers:
 * - **15 km/h.** A slow hike is 2-4 km/h, a brisk walk 5-7 km/h and a jog 8-12 km/h, while a jeep on
 *   a dirt road is usually 20-50 km/h. 15 km/h sits between the two with room on both sides.
 * - **The 90th percentile, by stretch.** Fixes come every 10 s while moving, but a stop is one long
 *   stretch (no new point until you have moved 8 m), so counting stretches measures how you move when
 *   you move: a drive with long stops at viewpoints is still a drive. A few stretches a GPS glitch
 *   made look fast can't turn a walk into a drive, as long as they are under a tenth of the trip.
 * - **At least [MIN_STRETCHES] stretches.** Fewer is too little to tell, so a tiny or empty trip is a
 *   walk.
 *
 * It can be wrong: a 4x4 crawl that never goes over 15 km/h is suggested as a walk, and a fast bike
 * ride as a drive. The owner can change any trip's type, and their choice always wins.
 */
object TripTypeRule {
    /** The stretch speed that decides: this share of the trip's stretches are at or below it. */
    const val PERCENTILE = 0.90

    /** A trip whose [PERCENTILE] stretch speed is above this is a drive. */
    const val DRIVE_SPEED_KMH = 15.0

    /** Below this many stretches with a speed, a trip is too short to tell and counts as a walk. */
    const val MIN_STRETCHES = 5

    /**
     * For a trip whose route is gone (a deleted trip kept in Stats from before trip types existed):
     * a drive when its average speed, distance over the whole duration with stops included, is at or
     * above this. Lower than [DRIVE_SPEED_KMH] because stops pull an average down: a hike averages
     * 2-5 km/h even with a fast stretch, while a jeep trip that is half stops still averages more
     * than 10 km/h. Used only by the database migration to version 9.
     */
    const val DRIVE_AVERAGE_SPEED_KMH = 10.0

    /** The suggested type for [route], the trip's `RouteSanitizer -> RouteSmoother` points. */
    fun suggest(route: List<TripPoint>): TripType {
        val speeds = RouteSpeedColoring.smoothedSpeeds(route).filterNotNull()
        if (speeds.size < MIN_STRETCHES) return TripType.WALK
        val metersPerSecond = RouteSpeedColoring.percentile(speeds.sorted(), PERCENTILE)
        return if (metersPerSecond * KMH_PER_METER_PER_SECOND > DRIVE_SPEED_KMH) TripType.DRIVE else TripType.WALK
    }

    /** The type from an average speed alone - see [DRIVE_AVERAGE_SPEED_KMH]. No time or no distance is a walk. */
    fun fromAverageSpeed(distanceMeters: Double, durationSeconds: Long): TripType {
        if (durationSeconds <= 0L || distanceMeters <= 0.0) return TripType.WALK
        val kmh = distanceMeters / durationSeconds * KMH_PER_METER_PER_SECOND
        return if (kmh >= DRIVE_AVERAGE_SPEED_KMH) TripType.DRIVE else TripType.WALK
    }

    private const val KMH_PER_METER_PER_SECOND = 3.6
}
