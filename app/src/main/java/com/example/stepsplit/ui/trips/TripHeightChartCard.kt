package com.example.stepsplit.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.trip.TripElevation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The chart never stretches less than this many meters of height across its full height, so a flat walk's few meters of leftover wobble stay a nearly flat line. */
private const val MIN_CHART_RANGE_METERS = 20.0

/** The height chart card's height. */
internal val TripHeightChartHeight = 72.dp

/**
 * The trip's height chart, at the bottom of the open trip details panel: the smoothed heights along
 * the trip's distance, with the highest and lowest point at the end side, next to the top and bottom
 * of the chart they belong to. The climb and descent are in the numbers card above it.
 *
 * The highest and lowest point are absolute heights, so they show only when [aboveSeaLevel] (Android
 * 14 and newer converted them - see `SeaLevelHeights`). Otherwise the heights are as stored, above
 * the WGS84 ellipsoid: the chart's shape is right, but the numbers would be off by the geoid height
 * (about 20 m too high in Israel), so they are left out.
 *
 * The chart reads from the trip's start like everything else in the app: left to right in English,
 * right to left in Hebrew. It is a plain line over a solid fill, no gradient brush (see [SpeedLegend]
 * for why).
 */
@Composable
fun TripHeightChartCard(elevation: TripElevation.Elevation, aboveSeaLevel: Boolean, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val highest = stringResource(R.string.trip_height_meters_format, formatHeightNumber(elevation.highestMeters, locale))
    val lowest = stringResource(R.string.trip_height_meters_format, formatHeightNumber(elevation.lowestMeters, locale))
    val description = stringResource(R.string.cd_trip_height_chart) +
        if (aboveSeaLevel) " " + stringResource(R.string.cd_trip_height_extremes, highest, lowest) else ""

    Card(
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(TripHeightChartHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeightChart(elevation = elevation, modifier = Modifier.weight(1f).fillMaxHeight())
            if (aboveSeaLevel) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(text = highest, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(text = lowest, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The smoothed heights along the distance: a line in the app's primary color over a light fill of the
 * same color. The chart's top is [TripElevation.Elevation.highestMeters] and its bottom
 * [TripElevation.Elevation.lowestMeters], unless they are closer than [MIN_CHART_RANGE_METERS], when
 * the line sits in the middle of that range instead.
 */
@Composable
private fun HeightChart(elevation: TripElevation.Elevation, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.18f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier = modifier) {
        val profile = elevation.profile
        val strokeWidth = 2.dp.toPx()
        val middle = (elevation.highestMeters + elevation.lowestMeters) / 2
        val range = maxOf(elevation.highestMeters - elevation.lowestMeters, MIN_CHART_RANGE_METERS)
        val bottomMeters = middle - range / 2
        val startMeters = profile.first().distanceMeters
        val spanMeters = (profile.last().distanceMeters - startMeters).takeIf { it > 0.0 } ?: 1.0
        val top = strokeWidth / 2
        val drawHeight = size.height - strokeWidth
        fun x(distanceMeters: Double): Float {
            val fromStart = ((distanceMeters - startMeters) / spanMeters * size.width).toFloat()
            return if (rtl) size.width - fromStart else fromStart
        }
        fun y(heightMeters: Double): Float = top + ((1.0 - (heightMeters - bottomMeters) / range) * drawHeight).toFloat()

        val line = Path().apply {
            moveTo(x(profile.first().distanceMeters), y(profile.first().heightMeters))
            for (point in profile.drop(1)) lineTo(x(point.distanceMeters), y(point.heightMeters))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(profile.last().distanceMeters), size.height)
            lineTo(x(profile.first().distanceMeters), size.height)
            close()
        }
        drawPath(path = area, color = fillColor)
        drawPath(path = line, color = lineColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A whole number of meters with the locale's thousands separator: "1,240", or "−412" below sea
 * level. A negative number starts with a left-to-right mark, as the locale data itself does for
 * Hebrew, so in right-to-left text the minus stays in front of the digits instead of jumping behind
 * them.
 */
internal fun formatHeightNumber(meters: Double, locale: Locale): String {
    val rounded = meters.roundToLong()
    val digits = String.format(locale, "%,d", abs(rounded))
    return if (rounded < 0) "‎−$digits" else digits
}
