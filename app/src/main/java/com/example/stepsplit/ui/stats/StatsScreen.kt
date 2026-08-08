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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.ui.common.StatCard
import com.example.stepsplit.ui.common.formatDateLabel
import java.time.LocalDate

/** Below this, an Earth-progress percentage would round to a misleading "0%" - see [formatEarthPercent]. */
private const val EARTH_PERCENT_DISPLAY_FLOOR = 0.01

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

    val stats = uiState.stats

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = stringResource(R.string.stats_title), style = MaterialTheme.typography.titleLarge) }

        if (!stats.hasData) {
            item { ZeroDataCard() }
        }

        item {
            StatCard(
                label = stringResource(R.string.stats_lifetime_steps_label),
                value = "${stats.lifetimeSteps}",
            )
        }

        item { DistanceCard(stats.estimatedKilometers) }

        item { EarthProgressCard(stats.earthProgressPercent) }

        item {
            StatCard(
                label = stringResource(R.string.stats_marathon_label),
                value = stringResource(R.string.stats_marathon_format, stats.marathonEquivalents),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_active_days_label),
                    value = "${stats.activeDays}",
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.stats_average_label),
                    value = "${stats.averageStepsPerActiveDay}",
                )
            }
        }

        item { BestDayCard(stats.bestDayDate, stats.bestDaySteps) }

        if (stats.hasData) {
            item { EncouragementCard(stats.kilometersToNextMarathon) }
        }

        if (uiState.legacyStats.lifetimeSteps > 0) {
            item { LegacyStepsCard(uiState.legacyStats.lifetimeSteps, uiState.legacyEstimatedKilometers) }
        }
    }
}

/**
 * Pre-existing history recorded before this app collected motion evidence at all - see
 * [StatsUiState.legacyStats]'s own doc comment. Deliberately its own compact card, never merged
 * into the verified lifetime steps/distance/globe-progress/achievement cards above - those must
 * only ever reflect vehicle-verified walking.
 */
@Composable
private fun LegacyStepsCard(steps: Long, estimatedKilometers: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.stats_legacy_title), style = MaterialTheme.typography.labelLarge)
            Text(text = "$steps ${stringResource(R.string.unit_steps)}", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.stats_legacy_distance_format, estimatedKilometers),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = stringResource(R.string.stats_legacy_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DistanceCard(estimatedKilometers: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.stats_distance_label), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.stats_distance_km_format, estimatedKilometers),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(text = stringResource(R.string.stats_distance_assumption), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EarthProgressCard(percent: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.stats_earth_progress_label), style = MaterialTheme.typography.labelLarge)
            Text(text = formatEarthPercent(percent), style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(
                // The number above is never capped, but a bar has nowhere further to go past full -
                // this mirrors GoalProgress.clampedFraction's own convention.
                progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
    }
}

/** Formats an uncapped Earth-progress percentage, avoiding a misleading "0%" for a genuinely nonzero but tiny value. */
@Composable
private fun formatEarthPercent(percent: Double): String = when {
    percent <= 0.0 -> stringResource(R.string.stats_earth_percent_zero)
    percent < EARTH_PERCENT_DISPLAY_FLOOR -> stringResource(R.string.stats_earth_percent_below_min)
    else -> stringResource(R.string.stats_earth_percent_format, percent)
}

@Composable
private fun BestDayCard(date: LocalDate?, steps: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.stats_best_day_label), style = MaterialTheme.typography.labelLarge)
            if (date != null) {
                Text(
                    text = "${formatDateLabel(date)} – $steps ${stringResource(R.string.unit_steps)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            } else {
                Text(text = stringResource(R.string.stats_best_day_none), style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
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

@Composable
private fun EncouragementCard(kilometersToNextMarathon: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = stringResource(R.string.stats_encouragement_label), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.stats_next_marathon_format, kilometersToNextMarathon),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
