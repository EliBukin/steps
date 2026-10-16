package com.example.stepsplit.domain.trip

import kotlin.math.cos

/**
 * The shape of the standing-still drift seen on the A55 (trip 4, 2026-10-16), at synthetic
 * coordinates (open sea, nowhere near the real route): a walk north at 1.4 m/s, a stop, five fixes
 * reporting exactly 0 m/s whose positions drift 10-21m apart at 15-40s intervals (implied 0.33-0.97
 * m/s) with 12-17m accuracy, then the walk resumes. Shared by the live-policy and read-time tests so
 * both layers are held to the same route. See
 * [RouteMovementPlausibility.STANDING_STILL_ACCURACY_FRACTION].
 */
internal object StandingStillDriftFixture {
    /** Offsets in metres east/north of the fixture's base position, so the drift shape stays readable. */
    data class Fix(val eastMeters: Double, val northMeters: Double, val t: Long, val accuracy: Float, val speed: Float)

    private const val BASE_LATITUDE = 10.0
    private const val BASE_LONGITUDE = 20.0
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val WALK_SPEED = 1.4f

    val walkIn = listOf(
        Fix(0.0, -42.0, 1_000L, 10f, WALK_SPEED),
        Fix(0.0, -28.0, 1_010L, 10f, WALK_SPEED),
        Fix(0.0, -14.0, 1_020L, 10f, WALK_SPEED),
    )

    /** The first fix after stopping: compared with a fix that still reported walking, so it is kept. */
    val stop = Fix(3.0, -4.0, 1_030L, 12f, 0f)

    /** Each at least 8m from the one before - the location request's minimum distance. */
    val drift = listOf(
        Fix(-6.0, 5.0, 1_050L, 15f, 0f),
        Fix(8.0, 9.0, 1_065L, 17f, 0f),
        Fix(-9.0, -3.0, 1_105L, 14f, 0f),
        Fix(-1.0, -9.0, 1_135L, 13f, 0f),
    )

    val walkOut = listOf(
        Fix(0.0, 14.0, 1_145L, 10f, WALK_SPEED),
        Fix(0.0, 28.0, 1_155L, 10f, WALK_SPEED),
        Fix(0.0, 42.0, 1_165L, 10f, WALK_SPEED),
    )

    val all = walkIn + stop + drift + walkOut

    /** What was actually walked: 42m in, 42m out, standing still in between. */
    const val WALKED_METERS = 84.0

    fun latitude(fix: Fix): Double = BASE_LATITUDE + Math.toDegrees(fix.northMeters / EARTH_RADIUS_METERS)

    fun longitude(fix: Fix): Double =
        BASE_LONGITUDE + Math.toDegrees(fix.eastMeters / (EARTH_RADIUS_METERS * cos(Math.toRadians(BASE_LATITUDE))))
}
