package com.example.stepsplit.domain.route

import kotlin.math.cos
import kotlin.math.sin

/** Made-up route shapes for the route tests, around a made-up place (31.0, 35.0). */
internal object RouteTestShapes {
    const val METERS_PER_DEGREE = 111_320.0
    const val LAT0 = 31.0
    const val LON0 = 35.0

    /** [lengthMeters] straight north from (31.0, 35.0), a point every [stepMeters], heights from [heights]. */
    fun north(lengthMeters: Double, stepMeters: Double = 10.0, heights: (Double) -> Double = { Double.NaN }): RouteSegment {
        val n = (lengthMeters / stepMeters).toInt() + 1
        return RouteSegment(
            DoubleArray(n) { LAT0 + it * stepMeters / METERS_PER_DEGREE },
            DoubleArray(n) { LON0 },
            DoubleArray(n) { heights(it * stepMeters) },
        )
    }

    /** A circle of [radiusMeters] starting and ending at its east point, [n] steps round. */
    fun circle(radiusMeters: Double, n: Int): RouteSegment {
        val xScale = METERS_PER_DEGREE * cos(Math.toRadians(LAT0))
        return RouteSegment(
            DoubleArray(n + 1) { LAT0 + radiusMeters * sin(2 * Math.PI * it / n) / METERS_PER_DEGREE },
            DoubleArray(n + 1) { LON0 + radiusMeters * cos(2 * Math.PI * it / n) / xScale },
            DoubleArray(n + 1) { Double.NaN },
        )
    }

    /** Out [lengthMeters] north and back the same way. */
    fun outAndBack(lengthMeters: Double, stepMeters: Double = 10.0): RouteSegment {
        val out = north(lengthMeters, stepMeters)
        val back = (out.size - 2 downTo 0).toList()
        return RouteSegment(
            out.latitudes + DoubleArray(back.size) { out.latitudes[back[it]] },
            out.longitudes + DoubleArray(back.size) { out.longitudes[back[it]] },
            DoubleArray(out.size + back.size) { Double.NaN },
        )
    }

    fun northOf(meters: Double) = LAT0 + meters / METERS_PER_DEGREE

    fun eastOf(meters: Double) = LON0 + meters / (METERS_PER_DEGREE * cos(Math.toRadians(LAT0)))
}
