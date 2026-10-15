package com.example.stepsplit.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.stats.LifetimeWalkingStats
import com.example.stepsplit.domain.trip.TripTotals
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.trips.formatDistance
import com.example.stepsplit.ui.trips.formatElapsed

/** Below this, a progress percentage would round to a misleading "0%" - see [formatTieredPercent]. */
private const val PERCENT_DISPLAY_FLOOR = 0.01

private const val EARTH_ICON = "🌍"
private const val MOON_ICON = "🚀"
private const val BURGER_ICON = "🍔"

/**
 * Lifetime stats as a summary list: one row per stat (label on one side, value on the other) in two
 * sections, steps and GPS trips. Percentage rows keep a progress bar under them.
 */
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge) }

        if (!uiState.stats.hasData) {
            item { ZeroDataCard() }
        }

        item { StepsSection(uiState.stats) }
        item { TripsSection(uiState.trips) }
    }
}

@Composable
private fun StepsSection(stats: LifetimeWalkingStats) {
    val moonPercent = MoonProgress.percent(stats.estimatedKilometers)
    StatsSection(
        title = stringResource(R.string.stats_section_steps),
        footnote = stringResource(R.string.stats_estimates_note),
        rows = listOf(
            { StatRow(stringResource(R.string.stats_lifetime_steps_label), number(stats.lifetimeSteps)) },
            { StatRow(stringResource(R.string.stats_distance_label), stringResource(R.string.stats_distance_km_format, stats.estimatedKilometers)) },
            { StatRow(stringResource(R.string.stats_workouts_label), number(stats.workoutCount.toLong())) },
            { StatRow(stringResource(R.string.stats_workout_time_label), formatElapsed(stats.workoutMinutes * 60)) },
            { StatRow(stringResource(R.string.stats_active_days_label), number(stats.activeDays.toLong())) },
            { StatRow(stringResource(R.string.stats_average_label), number(stats.averageStepsPerActiveDay)) },
            {
                StatRow(
                    stringResource(R.string.stats_best_day_label),
                    stats.bestDayDate?.let { date ->
                        stringResource(R.string.stats_best_day_value_format, formatDateLabel(date), stats.bestDaySteps)
                    } ?: stringResource(R.string.stats_best_day_none),
                )
            },
            { StatRow(stringResource(R.string.stats_marathon_label), stringResource(R.string.stats_marathon_format, stats.marathonEquivalents)) },
            {
                StatRow(
                    stringResource(R.string.stats_next_marathon_label),
                    stringResource(R.string.stats_next_marathon_value_format, stats.kilometersToNextMarathon),
                )
            },
            {
                StatRow(
                    label = stringResource(R.string.stats_earth_progress_label),
                    value = formatTieredPercent(
                        percent = stats.earthProgressPercent,
                        zero = R.string.stats_earth_percent_zero,
                        belowMin = R.string.stats_earth_percent_below_min,
                        format = R.string.stats_earth_percent_format,
                    ),
                    icon = EARTH_ICON,
                    progress = (stats.earthProgressPercent / 100.0).toFloat(),
                )
            },
            {
                StatRow(
                    label = stringResource(R.string.stats_moon_progress_label),
                    value = formatTieredPercent(
                        percent = moonPercent,
                        zero = R.string.stats_moon_percent_zero,
                        belowMin = R.string.stats_moon_percent_below_min,
                        format = R.string.stats_moon_percent_format,
                    ),
                    icon = MOON_ICON,
                    progress = (moonPercent / 100.0).toFloat(),
                    caption = stringResource(
                        R.string.stats_moon_progress_context_format,
                        stats.estimatedKilometers,
                        MoonProgress.EARTH_MOON_DISTANCE_KM,
                    ),
                )
            },
            { StatRow(stringResource(R.string.stats_calories_label), stringResource(R.string.stats_calories_format, stats.estimatedKilocalories)) },
            { StatRow(stringResource(R.string.stats_burgers_label), number(stats.burgersBurned), icon = BURGER_ICON) },
        ),
    )
}

@Composable
private fun TripsSection(trips: TripTotals) {
    val unavailable = stringResource(R.string.stats_value_unavailable)
    StatsSection(
        title = stringResource(R.string.stats_section_trips),
        footnote = stringResource(R.string.stats_trips_note),
        rows = if (!trips.hasTrips) {
            listOf({ EmptyRow(stringResource(R.string.trips_history_empty)) })
        } else {
            listOf(
                {
                    StatRow(
                        stringResource(R.string.stats_trips_count_label),
                        number(trips.tripCount.toLong()),
                        caption = if (trips.deletedTripCount > 0) {
                            pluralStringResource(R.plurals.stats_trips_deleted_included, trips.deletedTripCount, trips.deletedTripCount)
                        } else {
                            null
                        },
                    )
                },
                { StatRow(stringResource(R.string.stats_trips_time_label), formatElapsed(trips.totalDurationSeconds)) },
                { StatRow(stringResource(R.string.stats_trips_distance_label), formatDistance(trips.totalDistanceMeters)) },
                {
                    StatRow(
                        stringResource(R.string.stats_trips_speed_label),
                        trips.averageSpeedKmh?.let { stringResource(R.string.stats_speed_format, it) } ?: unavailable,
                    )
                },
                {
                    StatRow(
                        stringResource(R.string.stats_trips_pace_label),
                        trips.averagePaceSecondsPerKm?.let {
                            stringResource(R.string.stats_pace_format, (it / 60).toInt(), (it % 60).toInt())
                        } ?: unavailable,
                    )
                },
            )
        },
    )
}

/** A section title, then its rows in one card with thin dividers between them, then a small note. */
@Composable
private fun StatsSection(title: String, footnote: String, rows: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    row()
                }
            }
        }
        Text(text = footnote, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One stat: [label] (after an optional emoji [icon]) on the start side, [value] on the end side.
 * [progress] (0..1, clamped) adds a bar underneath, and [caption] a line of small text below that.
 * Merged into a single node so a screen reader reads the row as one item.
 */
@Composable
private fun StatRow(label: String, value: String, icon: String? = null, progress: Float? = null, caption: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(text = icon, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
            )
        }
        if (caption != null) {
            Text(text = caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun number(value: Long): String = stringResource(R.string.stats_number_format, value)

@Composable
private fun formatTieredPercent(percent: Double, zero: Int, belowMin: Int, format: Int): String = when {
    percent <= 0.0 -> stringResource(zero)
    percent < PERCENT_DISPLAY_FLOOR -> stringResource(belowMin)
    else -> stringResource(format, percent)
}

@Composable
private fun ZeroDataCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.stats_zero_state_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.stats_zero_state_message), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
