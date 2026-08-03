package com.example.stepsplit.domain.trip

import androidx.compose.ui.geometry.Offset

/** A route point reduced to just what the offline trace needs to draw - decoupled from [com.example.stepsplit.data.local.trip.TripPointEntity]. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/**
 * Normalizes accepted route points into pixel offsets within a `width` x `height` drawing area,
 * independent of any Compose drawing/composition context so the boundary cases can be unit-tested
 * directly: empty (returns nothing to draw), one-point (centered dot), and horizontal/vertical
 * routes (zero span on one axis, which would otherwise divide by zero) are all handled explicitly
 * rather than assumed away. This is a simple linear degree-based fit, not a real map projection -
 * adequate for a single short hike's local extent, not intended for anything wider.
 */
object RouteTraceGeometry {
    private const val DEFAULT_PADDING_FRACTION = 0.1f

    fun normalize(
        points: List<RoutePoint>,
        width: Float,
        height: Float,
        paddingFraction: Float = DEFAULT_PADDING_FRACTION,
    ): List<Offset> {
        if (points.isEmpty() || width <= 0f || height <= 0f) return emptyList()

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon

        val paddingX = width * paddingFraction
        val paddingY = height * paddingFraction
        val drawableWidth = width - 2 * paddingX
        val drawableHeight = height - 2 * paddingY

        return points.map { point ->
            val xFraction = if (lonSpan == 0.0) 0.5 else (point.longitude - minLon) / lonSpan
            // Latitude increases northward; canvas Y increases downward, so this is inverted.
            val yFraction = if (latSpan == 0.0) 0.5 else (maxLat - point.latitude) / latSpan
            Offset(
                x = paddingX + xFraction.toFloat() * drawableWidth,
                y = paddingY + yFraction.toFloat() * drawableHeight,
            )
        }
    }
}
