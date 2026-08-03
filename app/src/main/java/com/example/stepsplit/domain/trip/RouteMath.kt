package com.example.stepsplit.domain.trip

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure great-circle distance math shared by acceptance-policy jump detection and trip distance accumulation. */
object RouteMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Always returns a finite, non-negative distance for any two valid coordinates - in particular
     * for antipodal/near-antipodal pairs. Mathematically `a` (the squared half-chord length) is
     * always in `[0, 1]`, but floating-point rounding can push it fractionally above `1.0` (or,
     * near the poles/antipode, fractionally below `0.0`), which would make `sqrt(1 - a)` (or
     * `sqrt(a)`) receive a negative argument and return `NaN`. A `NaN` distance is not just a
     * display bug: [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy]'s implausible-jump
     * check compares `impliedSpeed > MAX_PLAUSIBLE_SPEED...`, and *any* comparison against `NaN` is
     * `false` in IEEE 754 - so an unclamped `NaN` here would silently defeat that rejection and let
     * a GPS-teleport artifact through with a poisoned, non-finite persisted trip distance.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = (
            sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2) * sin(deltaLon / 2)
            ).coerceIn(0.0, 1.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
