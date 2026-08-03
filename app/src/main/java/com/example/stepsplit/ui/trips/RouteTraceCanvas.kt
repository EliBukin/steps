package com.example.stepsplit.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.stepsplit.R
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.domain.trip.RouteTraceGeometry

/**
 * Offline route trace - a simple degree-normalized polyline (see [RouteTraceGeometry]), not a real
 * map. Safe for any input: an empty list draws nothing (callers should show a text fallback
 * instead - see `TripDetailScreen`), a single point draws just a start/finish marker with no line,
 * and a route with zero span on one axis (a perfectly horizontal or vertical path) still renders
 * correctly rather than collapsing to a point or crashing.
 */
@Composable
fun RouteTraceCanvas(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val startColor = Color(0xFF2E7D32) // fixed green "start" marker, independent of theme accent
    val endColor = MaterialTheme.colorScheme.error
    val routeTraceDescription = stringResource(R.string.cd_trip_route_trace)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { contentDescription = routeTraceDescription },
    ) {
        val offsets = RouteTraceGeometry.normalize(points, size.width, size.height)
        if (offsets.isEmpty()) return@Canvas

        if (offsets.size > 1) {
            val path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color = lineColor, style = Stroke(width = 6f))
        }

        drawCircle(color = startColor, radius = 10f, center = offsets.first())
        drawCircle(color = endColor, radius = 10f, center = offsets.last())
    }
}
