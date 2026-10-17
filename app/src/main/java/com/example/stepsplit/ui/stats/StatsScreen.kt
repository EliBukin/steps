package com.example.stepsplit.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripType
import com.example.stepsplit.domain.stats.LifetimeWalkingStats
import com.example.stepsplit.domain.trip.TripTotals
import com.example.stepsplit.domain.trip.TripTotalsByType
import com.example.stepsplit.ui.common.PlainProgressBar
import com.example.stepsplit.ui.common.ScreenHeader
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.trips.TripTypeIcon
import com.example.stepsplit.ui.trips.formatDistance
import com.example.stepsplit.ui.trips.formatElapsed

/** Below this, a progress percentage would round to a misleading "0%" - see [formatTieredPercent]. */
private const val PERCENT_DISPLAY_FLOOR = 0.01

private const val EARTH_ICON = "🌍"
private const val MOON_ICON = "🚀"
private const val BURGER_ICON = "🍔"

/**
 * Lifetime stats as a summary list: one row per stat (label on one side, value on the other) in two
 * sections, steps and GPS trips. Percentage rows keep a progress bar under them. GPS trips have a
 * card for walks and one for drives, each shown only when it has trips.
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
        item { ScreenHeader(title = stringResource(R.string.nav_stats), subtitle = stringResource(R.string.stats_subtitle)) }

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
private fun TripsSection(trips: TripTotalsByType) {
    StatsSection(title = stringResource(R.string.stats_section_trips), footnote = stringResource(R.string.stats_trips_note)) {
        if (!trips.hasTrips) {
            StatsCard(rows = listOf({ EmptyRow(stringResource(R.string.trips_history_empty)) }))
        } else {
            if (trips.walks.hasTrips) TripTypeCard(TripType.WALK, trips.walks)
            if (trips.drives.hasTrips) TripTypeCard(TripType.DRIVE, trips.drives)
        }
    }
}

/** One trip type's totals, under its icon and name. Pace (minutes per km) only means something on foot, so drives show speed alone. */
@Composable
private fun TripTypeCard(type: TripType, totals: TripTotals) {
    val unavailable = stringResource(R.string.stats_value_unavailable)
    val rows = buildList<@Composable () -> Unit> {
        add {
            StatRow(
                stringResource(R.string.stats_trips_count_label),
                number(totals.tripCount.toLong()),
                caption = if (totals.deletedTripCount > 0) {
                    pluralStringResource(R.plurals.stats_trips_deleted_included, totals.deletedTripCount, totals.deletedTripCount)
                } else {
                    null
                },
            )
        }
        add { StatRow(stringResource(R.string.stats_trips_time_label), formatElapsed(totals.totalDurationSeconds)) }
        add { StatRow(stringResource(R.string.stats_trips_distance_label), formatDistance(totals.totalDistanceMeters)) }
        add {
            StatRow(
                stringResource(R.string.stats_trips_speed_label),
                totals.averageSpeedKmh?.let { stringResource(R.string.stats_speed_format, it) } ?: unavailable,
            )
        }
        if (type == TripType.WALK) {
            add {
                StatRow(
                    stringResource(R.string.stats_trips_pace_label),
                    totals.averagePaceSecondsPerKm?.let {
                        stringResource(R.string.stats_pace_format, (it / 60).toInt(), (it % 60).toInt())
                    } ?: unavailable,
                )
            }
        }
    }
    StatsCard(
        header = {
            Row(
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp).semantics(mergeDescendants = true) { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TripTypeIcon(type, contentDescription = null)
                Text(
                    text = stringResource(
                        when (type) {
                            TripType.WALK -> R.string.stats_trips_walks_title
                            TripType.DRIVE -> R.string.stats_trips_drives_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
        rows = rows,
    )
}

/** A section title, then its rows in one card with thin dividers between them, then a small note. */
@Composable
private fun StatsSection(title: String, footnote: String, rows: List<@Composable () -> Unit>) {
    StatsSection(title, footnote) { StatsCard(rows) }
}

/** A section title, then its cards, then a small note. */
@Composable
private fun StatsSection(title: String, footnote: String, cards: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        cards()
        Text(text = footnote, style = MaterialTheme.typography.bodySmall)
    }
}

/** Rows in one card with thin dividers between them, under an optional [header]. */
@Composable
private fun StatsCard(rows: List<@Composable () -> Unit>, header: (@Composable () -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            header?.invoke()
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                row()
            }
        }
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
            PlainProgressBar(fraction = progress, modifier = Modifier.padding(top = 8.dp), height = 6.dp)
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
