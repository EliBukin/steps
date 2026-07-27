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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.aggregation.DateStepBreakdown
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.ui.common.LegendDot
import com.example.stepsplit.ui.common.WeeklyStackedBarChart
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.formatDayLabel
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WeeklyStackedBarChart(days = uiState.days)
                ChartLegend()
            }
        }
        items(uiState.days.reversed()) { day ->
            DayRow(day = day, dailyGoal = uiState.dailyGoal)
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(color = WorkoutSegmentColor)
            Text(stringResource(R.string.label_workout_steps), style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendDot(color = IncidentalSegmentColor)
            Text(stringResource(R.string.label_incidental_steps), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DayRow(day: DateStepBreakdown, dailyGoal: Long) {
    val progress = GoalProgress(day.totalSteps, dailyGoal)
    val isToday = day.date == LocalDate.now()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = if (isToday) stringResource(R.string.history_today_label) else formatDayLabel(day.date),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(text = formatDateLabel(day.date), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(text = "${day.totalSteps}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.percent_of_goal_format, progress.percent.roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
