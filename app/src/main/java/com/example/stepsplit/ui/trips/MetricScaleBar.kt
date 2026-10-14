package com.example.stepsplit.ui.trips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepsplit.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.pow

/** One state of the map's scale bar: a round distance and the width that distance draws at. */
internal data class MetricScaleStep(val meters: Int, val widthDp: Float)

internal enum class ScaleUnit { METERS, KILOMETERS }

/** A scale bar label split into its locale-formatted number and its unit, e.g. "2.5" + [ScaleUnit.KILOMETERS]. */
internal data class ScaleLabel(val number: String, val unit: ScaleUnit)

/**
 * Pure step and label logic for [MetricScaleBar], kept free of Compose so it is directly
 * unit-testable. MapLibre's own scale bar picks feet or meters from the device's region (feet on a
 * US-English phone) and maplibre-compose exposes no setting for it, so the app draws its own copy
 * of that bar, always metric.
 */
internal object MetricScale {
    /** Round distances the bar can span: 1, 2 and 5 times each power of ten, from 1 m up to 5,000 km. */
    private val ROUND_METERS: List<Int> = (0..6).flatMap { exponent ->
        listOf(1, 2, 5).map { it * 10.0.pow(exponent).toInt() }
    }

    /**
     * The largest round distance whose bar fits in [maxWidthDp] at [metersPerDp], or null when there
     * is nothing sensible to draw: the map has not reported a scale yet (0, negative or non-finite),
     * or it is zoomed in so far that even 1 m is wider than [maxWidthDp].
     */
    fun choose(metersPerDp: Double, maxWidthDp: Float): MetricScaleStep? {
        if (!metersPerDp.isFinite() || metersPerDp <= 0.0 || maxWidthDp <= 0f) return null
        val maxMeters = metersPerDp * maxWidthDp
        val meters = ROUND_METERS.lastOrNull { it <= maxMeters } ?: return null
        return MetricScaleStep(meters, (meters / metersPerDp).toFloat())
    }

    /**
     * How a distance on the bar is labelled: meters below 1 km, kilometers from 1 km, with at most
     * one decimal - the bar's midpoint can be a half step, e.g. 2.5 m or 2.5 km.
     */
    fun label(meters: Double, locale: Locale): ScaleLabel {
        val (value, unit) = if (meters >= 1000.0) meters / 1000.0 to ScaleUnit.KILOMETERS else meters to ScaleUnit.METERS
        val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
        return ScaleLabel(format.format(value), unit)
    }
}

private val LabelStyle = TextStyle(fontSize = 9.sp)
private val LabelHaloWidth = 2.dp
private val LabelBarGap = 2.dp
private val BarHeight = 4.dp
private val BarBorderWidth = 0.75.dp

/**
 * A metric copy of MapLibre's own scale bar, in the same black-and-white design: a bar split into a
 * black and a white half inside a thin black outline, with "0", the midpoint and the full distance
 * above it in black text with a white halo. At most [maxWidth] wide; draws nothing while
 * [MetricScale.choose] has no step to show. Always drawn left to right, as MapLibre's is.
 */
@Composable
internal fun MetricScaleBar(metersPerDp: Double, maxWidth: Dp, modifier: Modifier = Modifier) {
    val step = MetricScale.choose(metersPerDp, maxWidth.value) ?: return
    val locale = LocalConfiguration.current.locales[0]
    val labels = listOf(
        "0",
        labelText(MetricScale.label(step.meters / 2.0, locale)),
        labelText(MetricScale.label(step.meters.toDouble(), locale)),
    )
    val textMeasurer = rememberTextMeasurer()
    val layouts = labels.map { textMeasurer.measure(it, LabelStyle) }

    val density = LocalDensity.current
    val barWidthPx = with(density) { step.widthDp.dp.toPx() }
    val haloPx = with(density) { LabelHaloWidth.toPx() }
    // The bar sits just under the labels' baseline, as in MapLibre's bar - not under the bottom of
    // the text box, which leaves descender space and makes the labels float.
    val baselinePx = layouts.maxOf { it.firstBaseline } + haloPx / 2f
    // Labels are centered on their ticks, so the last one hangs half its width past the bar's end.
    val overhangPx = layouts.last().size.width / 2f + haloPx
    val barTopPx = baselinePx + with(density) { LabelBarGap.toPx() }
    val totalHeightPx = barTopPx + with(density) { BarHeight.toPx() }

    Canvas(
        modifier = modifier.size(
            width = with(density) { (barWidthPx + overhangPx).toDp() },
            height = with(density) { totalHeightPx.toDp() },
        ),
    ) {
        val border = BarBorderWidth.toPx()
        drawRect(Color.Black, topLeft = Offset(0f, barTopPx), size = Size(barWidthPx, BarHeight.toPx()))
        drawRect(
            Color.White,
            topLeft = Offset(barWidthPx / 2f, barTopPx + border),
            size = Size(barWidthPx / 2f - border, BarHeight.toPx() - 2 * border),
        )

        layouts.forEachIndexed { index, layout ->
            val tickX = barWidthPx * index / 2f
            val topLeft = Offset((tickX - layout.size.width / 2f).coerceAtLeast(haloPx / 2f), haloPx / 2f)
            drawText(layout, color = Color.White, topLeft = topLeft, drawStyle = Stroke(width = haloPx))
            // Fill must be explicit: left unset, the text paint keeps the Stroke from the halo pass
            // and the black text comes out as heavy outlines.
            drawText(layout, color = Color.Black, topLeft = topLeft, drawStyle = Fill)
        }
    }
}

@Composable
private fun labelText(label: ScaleLabel): String = when (label.unit) {
    ScaleUnit.METERS -> stringResource(R.string.trip_map_scale_meters_format, label.number)
    ScaleUnit.KILOMETERS -> stringResource(R.string.trip_map_scale_kilometers_format, label.number)
}
