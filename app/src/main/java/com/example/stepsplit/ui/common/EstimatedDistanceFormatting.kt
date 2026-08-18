package com.example.stepsplit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.stepsplit.R
import kotlin.math.roundToInt

/** Which unit and value [formatEstimatedDistanceMeters] should display, pre-computed and plain-JVM testable. */
internal sealed interface EstimatedDistanceDisplay {
    data class Kilometers(val value: Double) : EstimatedDistanceDisplay
    data class Meters(val value: Int) : EstimatedDistanceDisplay
}

/** Meters below 1 km, kilometers at or above - pure decision logic, no Android/Compose dependency. */
internal fun estimatedDistanceDisplay(meters: Double): EstimatedDistanceDisplay = if (meters >= 1000.0) {
    EstimatedDistanceDisplay.Kilometers(meters / 1000.0)
} else {
    EstimatedDistanceDisplay.Meters(meters.roundToInt())
}

/**
 * Formats a step-count-estimated distance for display: meters below 1 km, kilometers above.
 * Presentation-only formatting - the caller computes [meters] from
 * [com.example.stepsplit.domain.aggregation.DateStepBreakdown.totalSteps] times
 * [com.example.stepsplit.domain.stats.LifetimeStatsCalculator.METERS_PER_STEP], never from GPS
 * trip distance. Always paired with an explicit "estimated" label at the call site (see
 * `history_estimated_distance_label`), since this function only formats the number itself.
 */
@Composable
fun formatEstimatedDistanceMeters(meters: Double): String = when (val display = estimatedDistanceDisplay(meters)) {
    is EstimatedDistanceDisplay.Kilometers -> stringResource(R.string.history_estimated_distance_km_format, display.value)
    is EstimatedDistanceDisplay.Meters -> stringResource(R.string.history_estimated_distance_meters_format, display.value)
}
