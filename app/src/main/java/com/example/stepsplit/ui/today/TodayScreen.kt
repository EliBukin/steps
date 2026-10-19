package com.example.stepsplit.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.ui.common.CollectionStatusBanner
import com.example.stepsplit.ui.common.LegendDot
import com.example.stepsplit.ui.common.PlainProgressBar
import com.example.stepsplit.ui.common.ScreenHeader
import com.example.stepsplit.ui.common.StaleSourceBanner
import com.example.stepsplit.ui.common.SyncFailureBanner
import com.example.stepsplit.ui.common.WaitingForFirstSampleBanner
import com.example.stepsplit.ui.common.formatCount
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.formatDayLabel
import com.example.stepsplit.ui.common.formatSyncTime
import com.example.stepsplit.ui.theme.IncidentalSegmentColor
import com.example.stepsplit.ui.theme.WorkoutSegmentColor
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often this screen re-imports from Health Connect while it stays visibly active - see the product requirement for a roughly-once-a-minute foreground refresh. */
private const val FOREGROUND_REFRESH_INTERVAL_MILLIS = 60_000L

private val RingSize = 200.dp
private val RingStroke = 16.dp

@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        // Covers both "whenever the application resumes" and the immediate refresh right after
        // permission is granted (see onGrantPermission's own wiring in StepSplitApp) - this effect
        // re-runs every time the screen becomes resumed again.
        onRefresh()
        val periodicRefreshJob = coroutineScope.launch {
            while (isActive) {
                delay(FOREGROUND_REFRESH_INTERVAL_MILLIS)
                onRefresh()
            }
        }
        onPauseOrDispose { periodicRefreshJob.cancel() }
    }

    val items = buildList {
        add(TodayItem.Header)
        add(TodayItem.Availability)
        if (uiState.syncFailure != null) add(TodayItem.SyncFailure)
        if (uiState.collectionHealth == StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE) add(TodayItem.WaitingForFirstSample)
        if (uiState.collectionHealth == StepCollectionHealth.STALE) add(TodayItem.StaleSource)
        // Nothing until the first load: the defaults (0 steps, goal 0) would flash as real numbers.
        if (!uiState.isLoading) {
            add(TodayItem.DailyRing)
            add(TodayItem.StepKinds)
            add(TodayItem.Week)
        }
        add(TodayItem.LastSync)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items) { item ->
            when (item) {
                TodayItem.Header -> {
                    val today = LocalDate.now()
                    ScreenHeader(
                        title = stringResource(R.string.nav_today),
                        subtitle = "${formatDayLabel(today)} ${formatDateLabel(today)}",
                    )
                }

                TodayItem.Availability -> uiState.availability?.let { availability ->
                    CollectionStatusBanner(
                        availability = availability,
                        permissionAction = uiState.permissionAction,
                        onGrantPermission = onGrantPermission,
                        onOpenHealthConnect = onOpenHealthConnect,
                    )
                }

                TodayItem.SyncFailure -> uiState.syncFailure?.let { failure -> SyncFailureBanner(failure) }

                TodayItem.WaitingForFirstSample -> WaitingForFirstSampleBanner()

                TodayItem.StaleSource -> StaleSourceBanner()

                TodayItem.DailyRing -> DailyRing(totalSteps = uiState.today?.totalSteps ?: 0, progress = uiState.dailyProgress)

                TodayItem.StepKinds -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepKindChip(
                        color = WorkoutSegmentColor,
                        value = uiState.today?.workoutSteps ?: 0,
                        label = stringResource(R.string.label_workout_steps),
                        modifier = Modifier.weight(1f),
                    )
                    StepKindChip(
                        color = IncidentalSegmentColor,
                        value = uiState.today?.incidentalSteps ?: 0,
                        label = stringResource(R.string.label_incidental_steps),
                        modifier = Modifier.weight(1f),
                    )
                }

                TodayItem.Week -> WeekCard(uiState.weeklyTotalSteps, uiState.weeklyProgress)

                TodayItem.LastSync -> Text(
                    text = uiState.lastSuccessfulSync?.let { formatSyncTime(it) }
                        ?.let { stringResource(R.string.last_sync_label, it) }
                        ?: stringResource(R.string.last_sync_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private enum class TodayItem { Header, Availability, SyncFailure, WaitingForFirstSample, StaleSource, DailyRing, StepKinds, Week, LastSync }

/**
 * Today's total inside a ring that fills toward the daily goal. The ring stops at a full circle, but
 * the percentage under the number is never capped - 18,000 of a 15,000 goal reads "120%".
 */
@Composable
private fun DailyRing(totalSteps: Long, progress: GoalProgress) {
    val percent = progress.percent.roundToInt()
    val trackColor = MaterialTheme.colorScheme.secondaryContainer
    val progressColor = MaterialTheme.colorScheme.primary
    val description = buildString {
        append(stringResource(R.string.cd_total_steps, formatCount(totalSteps)))
        if (progress.isGoalValid) append(", ").append(stringResource(R.string.cd_daily_progress, percent))
    }
    Box(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = RingStroke.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                if (progress.clampedFraction > 0f) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.clampedFraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = formatCount(totalSteps), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                if (progress.isGoalValid) {
                    Text(
                        text = stringResource(R.string.today_of_goal_steps_format, formatCount(progress.goalSteps)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.percent_format, percent),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.today_steps_unit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Workout or incidental steps, with the same color key as the History chart. */
@Composable
private fun StepKindChip(color: Color, value: Long, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LegendDot(color = color, modifier = Modifier.size(12.dp))
            Column {
                Text(text = formatCount(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** This week's total (weeks start on Sunday) against the weekly goal, on one line with a thin bar. */
@Composable
private fun WeekCard(weeklyTotal: Long, progress: GoalProgress) {
    val percent = progress.percent.roundToInt()
    val progressDescription = stringResource(R.string.cd_weekly_progress, percent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_weekly_total),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (progress.isGoalValid) {
                    Text(text = stringResource(R.string.percent_format, percent), style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = formatCount(weeklyTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (progress.isGoalValid) {
                    Text(
                        text = stringResource(R.string.today_of_goal_format, formatCount(progress.goalSteps)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            if (progress.isGoalValid) {
                PlainProgressBar(
                    fraction = progress.clampedFraction,
                    modifier = Modifier.semantics { contentDescription = progressDescription },
                )
            }
        }
    }
}
