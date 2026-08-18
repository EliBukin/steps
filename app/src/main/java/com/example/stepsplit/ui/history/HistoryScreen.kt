package com.example.stepsplit.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.domain.stats.LifetimeStatsCalculator
import com.example.stepsplit.ui.common.LegendDot
import com.example.stepsplit.ui.common.WeeklyStackedBarChart
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.formatDayLabel
import com.example.stepsplit.ui.common.formatEstimatedDistanceMeters
import com.example.stepsplit.ui.theme.IncidentalSegmentColor
import com.example.stepsplit.ui.theme.WorkoutSegmentColor
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
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
        item {
            Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                WeeklyStackedBarChart(
                    days = uiState.days,
                    dailyGoal = uiState.dailyGoal,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        items(uiState.days.reversed()) { day ->
            DayCard(day = day, dailyGoal = uiState.dailyGoal)
        }
    }
}

@Composable
private fun DayCard(day: DateStepBreakdown, dailyGoal: Long) {
    val progress = GoalProgress(day.totalSteps, dailyGoal)
    val isToday = day.date == LocalDate.now()
    val estimatedMeters = day.totalSteps * LifetimeStatsCalculator.METERS_PER_STEP
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = if (isToday) stringResource(R.string.history_today_label) else formatDayLabel(day.date),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = formatDateLabel(day.date), style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "${day.totalSteps}", style = MaterialTheme.typography.titleLarge)
                    if (dailyGoal > 0) {
                        Text(
                            text = stringResource(R.string.percent_of_goal_format, progress.percent.roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StepKindStat(
                    color = WorkoutSegmentColor,
                    label = stringResource(R.string.label_workout_steps),
                    value = "${day.workoutSteps}",
                )
                StepKindStat(
                    color = IncidentalSegmentColor,
                    label = stringResource(R.string.label_incidental_steps),
                    value = "${day.incidentalSteps}",
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = stringResource(R.string.history_estimated_distance_label), style = MaterialTheme.typography.bodyMedium)
                Text(text = formatEstimatedDistanceMeters(estimatedMeters), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StepKindStat(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LegendDot(color = color)
        Column {
            Text(text = value, style = MaterialTheme.typography.titleSmall)
            Text(text = label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
