package com.example.stepsplit.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.stats.LifetimeWalkingStats
import com.example.stepsplit.ui.common.formatDateLabel

/** Below this, a progress percentage would round to a misleading "0%" - see [formatTieredPercent]. */
private const val PERCENT_DISPLAY_FLOOR = 0.01

/** The grid needs at least this much content width to keep two columns of tile text readable. */
private val TwoColumnMinContentWidth = 320.dp

/** Above this font scale, even a wide screen falls back to one column so tile text has room to wrap. */
private const val TwoColumnMaxFontScale = 1.3f

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
            StatsTilesGrid(
                tiles = listOf(
                    { TotalStepsTile(stats) },
                    { DistanceTile(stats) },
                    { MarathonTile(stats) },
                    { ActiveDaysTile(stats) },
                    { AverageTile(stats) },
                    { BestDayTile(stats) },
                    { EarthProgressTile(stats) },
                    { MoonProgressTile(stats) },
                ),
            )
        }

        if (stats.hasData) {
            item { EncouragementCard(stats.kilometersToNextMarathon) }
        }
    }
}

/**
 * Adaptive layout for the eight stat tiles: two equal-height columns (each row stretched to its
 * tallest tile via [IntrinsicSize.Max]) when there's enough width and the font scale is small
 * enough to keep tile text readable without truncation, otherwise a single column.
 */
@Composable
private fun StatsTilesGrid(tiles: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val useTwoColumns = maxWidth >= TwoColumnMinContentWidth && fontScale <= TwoColumnMaxFontScale
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (useTwoColumns) {
                tiles.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { tile ->
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { tile() }
                        }
                        if (row.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                tiles.forEach { tile -> tile() }
            }
        }
    }
}

@Composable
private fun TotalStepsTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_lifetime_steps_label)
    val value = "${stats.lifetimeSteps}"
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
    }
}

@Composable
private fun DistanceTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_distance_label)
    val value = stringResource(R.string.stats_distance_km_format, stats.estimatedKilometers)
    val assumption = stringResource(R.string.stats_distance_assumption)
    StatsTile(contentDescription = "$label $value. $assumption", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
        TileCaption(assumption)
    }
}

@Composable
private fun MarathonTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_marathon_label)
    val value = stringResource(R.string.stats_marathon_format, stats.marathonEquivalents)
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
    }
}

@Composable
private fun ActiveDaysTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_active_days_label)
    val value = "${stats.activeDays}"
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
    }
}

@Composable
private fun AverageTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_average_label)
    val value = "${stats.averageStepsPerActiveDay}"
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
    }
}

@Composable
private fun BestDayTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_best_day_label)
    val value = stats.bestDayDate?.let { date ->
        "${formatDateLabel(date)} – ${stats.bestDaySteps} ${stringResource(R.string.unit_steps)}"
    } ?: stringResource(R.string.stats_best_day_none)
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
    }
}

@Composable
private fun EarthProgressTile(stats: LifetimeWalkingStats) {
    val label = stringResource(R.string.stats_earth_progress_label)
    val value = formatTieredPercent(
        percent = stats.earthProgressPercent,
        zero = R.string.stats_earth_percent_zero,
        belowMin = R.string.stats_earth_percent_below_min,
        format = R.string.stats_earth_percent_format,
    )
    StatsTile(contentDescription = "$label $value", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
        LinearProgressIndicator(
            progress = { (stats.earthProgressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp),
        )
    }
}

@Composable
private fun MoonProgressTile(stats: LifetimeWalkingStats) {
    val moonPercent = MoonProgress.percent(stats.estimatedKilometers)
    val label = stringResource(R.string.stats_moon_progress_label)
    val value = formatTieredPercent(
        percent = moonPercent,
        zero = R.string.stats_moon_percent_zero,
        belowMin = R.string.stats_moon_percent_below_min,
        format = R.string.stats_moon_percent_format,
    )
    val context = stringResource(
        R.string.stats_moon_progress_context_format,
        stats.estimatedKilometers,
        MoonProgress.EARTH_MOON_DISTANCE_KM,
    )
    StatsTile(contentDescription = "$label $value. $context", modifier = Modifier.fillMaxWidth()) {
        TileLabel(label)
        TileValue(value)
        LinearProgressIndicator(
            progress = { (moonPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp),
        )
        TileCaption(context)
    }
}

@Composable
private fun TileLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun TileValue(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun TileCaption(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

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
