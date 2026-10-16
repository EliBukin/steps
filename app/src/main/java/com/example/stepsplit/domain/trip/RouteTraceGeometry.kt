package com.example.stepsplit.domain.trip

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

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

    /**
     * Like [normalize], but keeps the route's real shape: one scale for both axes, with longitude
     * narrowed by the cosine of the route's middle latitude (a degree of longitude is shorter away
     * from the equator), and the result centered. [normalize] stretches each axis to fill the area,
     * which turns a long, thin route into a square blob - fine for a full-size trace, wrong for a
     * small preview whose whole point is to be recognizable.
     */
    fun fitKeepingShape(
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
        val lonScale = cos((minLat + maxLat) / 2 * PI / 180)
        val spanX = (maxLon - minLon) * lonScale
        val spanY = maxLat - minLat

        val drawableWidth = width * (1 - 2 * paddingFraction)
        val drawableHeight = height * (1 - 2 * paddingFraction)
        val scale = when {
            spanX == 0.0 && spanY == 0.0 -> 0.0
            spanX == 0.0 -> drawableHeight / spanY
            spanY == 0.0 -> drawableWidth / spanX
            else -> min(drawableWidth / spanX, drawableHeight / spanY)
        }
        val offsetX = (width - spanX * scale) / 2
        val offsetY = (height - spanY * scale) / 2

        return points.map { point ->
            Offset(
                x = (offsetX + (point.longitude - minLon) * lonScale * scale).toFloat(),
                // Latitude increases northward; canvas Y increases downward, so this is inverted.
                y = (offsetY + (maxLat - point.latitude) * scale).toFloat(),
            )
        }
    }

    /**
     * At most [maxPoints] of [points], evenly spread, always keeping the first and the last - enough
     * for a thumbnail, without holding thousands of points per trip in the trip list.
     */
    fun downsample(points: List<RoutePoint>, maxPoints: Int): List<RoutePoint> {
        require(maxPoints >= 2) { "maxPoints must keep at least the start and the finish" }
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { i -> points[Math.round(i * step).toInt()] }
    }
}
