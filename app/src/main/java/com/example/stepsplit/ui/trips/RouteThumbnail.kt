package com.example.stepsplit.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.domain.trip.RouteTraceGeometry

/** Same fixed "start" green as the map and [RouteTraceCanvas]. */
private val StartMarkerColor = Color(0xFF2E7D32)

/**
 * A small, map-less picture of a route's shape for the trip list: the line in its real proportions
 * (see [RouteTraceGeometry.fitKeepingShape]), with start and finish dots. Decorative - the card's
 * text already says everything a screen reader needs.
 */
@Composable
fun RouteThumbnail(points: List<RoutePoint>, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val lineColor = MaterialTheme.colorScheme.primary
    val finishColor = MaterialTheme.colorScheme.error
    Canvas(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val offsets = RouteTraceGeometry.fitKeepingShape(points, this.size.width, this.size.height, paddingFraction = 0.16f)
        if (offsets.isEmpty()) return@Canvas
        if (offsets.size > 1) {
            val path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val dotRadius = 3.dp.toPx()
        drawCircle(color = StartMarkerColor, radius = dotRadius, center = offsets.first())
        if (offsets.size > 1) drawCircle(color = finishColor, radius = dotRadius, center = offsets.last())
    }
}
