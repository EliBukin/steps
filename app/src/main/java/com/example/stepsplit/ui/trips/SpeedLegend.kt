package com.example.stepsplit.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import java.util.Locale
import kotlin.math.roundToInt

private const val METERS_PER_SECOND_TO_KMH = 3.6

/** The legend's color steps - enough to read as a smooth red-yellow-green blend at this size. */
private const val LEGEND_COLOR_STEPS = 16

/**
 * "2.1 km/h ▬▬▬ 6.0 km/h": the trip's own speed scale, on the map. Slow is on the start side, like
 * every bar in the app fills from the start: the left in English, the right in Hebrew. A Canvas
 * isn't mirrored for right-to-left on its own, so the colors are reversed here.
 *
 * The bar is drawn as small solid steps, not with a gradient brush: on the emulator's software
 * graphics, a multi-color gradient drawn over the map crashed the whole emulator.
 */
@Composable
fun SpeedLegend(scale: RouteSpeedColoring.SpeedScale, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val slowText = stringResource(R.string.trip_map_speed_format, formatLegendSpeed(scale.slowMetersPerSecond * METERS_PER_SECOND_TO_KMH, locale))
    val fastText = stringResource(R.string.trip_map_speed_format, formatLegendSpeed(scale.fastMetersPerSecond * METERS_PER_SECOND_TO_KMH, locale))
    val description = stringResource(R.string.cd_trip_speed_legend, slowText, fastText)
    val colors = List(LEGEND_COLOR_STEPS) { Color(RouteSpeedColoring.colorAt(it / (LEGEND_COLOR_STEPS - 1).toDouble())) }
        .let { if (LocalLayoutDirection.current == LayoutDirection.Rtl) it.reversed() else it }

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = slowText, style = MaterialTheme.typography.labelSmall)
            Canvas(modifier = Modifier.size(width = 48.dp, height = 6.dp).clip(RoundedCornerShape(3.dp))) {
                val stepWidth = size.width / colors.size
                colors.forEachIndexed { i, color ->
                    // A hair wider than the step, so no seam shows between neighbours.
                    drawRect(color = color, topLeft = Offset(i * stepWidth, 0f), size = Size(stepWidth + 0.5f, size.height))
                }
            }
            Text(text = fastText, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A legend speed: one decimal below 10 km/h ("2.4"), whole numbers from there ("37"). */
internal fun formatLegendSpeed(kmh: Double, locale: Locale): String =
    if (kmh < 9.95) String.format(locale, "%.1f", kmh) else String.format(locale, "%d", kmh.roundToInt())
