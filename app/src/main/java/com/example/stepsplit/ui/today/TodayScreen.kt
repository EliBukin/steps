package com.example.stepsplit.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.ui.common.CollectionStatusBanner
import com.example.stepsplit.ui.common.GoalProgressSection
import com.example.stepsplit.ui.common.StatCard
import com.example.stepsplit.ui.common.formatSyncTime

@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
    onStartWalk: () -> Unit,
    onFinishWalk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    val items = buildList {
        add(TodayItem.Availability)
        add(TodayItem.ManualWalk)
        add(TodayItem.Totals)
        add(TodayItem.DailyGoal)
        add(TodayItem.WeeklyGoal)
        add(TodayItem.LastSync)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items) { item ->
            when (item) {
                TodayItem.Availability -> uiState.availability?.let { availability ->
                    CollectionStatusBanner(availability = availability, onGrantPermission = onGrantPermission)
                }

                TodayItem.ManualWalk -> ManualWalkControl(
                    hasOngoingManualWalk = uiState.hasOngoingManualWalk,
                    onStartWalk = onStartWalk,
                    onFinishWalk = onFinishWalk,
                )

                TodayItem.Totals -> TotalsSection(uiState)
                TodayItem.DailyGoal -> GoalProgressSection(
                    title = stringResource(R.string.label_daily_goal),
                    progress = uiState.dailyProgress,
                    contentDescriptionRes = R.string.cd_daily_progress,
                )

                TodayItem.WeeklyGoal -> WeeklySection(uiState.weeklyTotalSteps, uiState.weeklyProgress)

                TodayItem.LastSync -> Text(
                    text = uiState.lastSuccessfulSync?.let { formatSyncTime(it) }
                        ?.let { stringResource(R.string.last_sync_label, it) }
                        ?: stringResource(R.string.last_sync_never),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private enum class TodayItem { Availability, ManualWalk, Totals, DailyGoal, WeeklyGoal, LastSync }

@Composable
private fun ManualWalkControl(
    hasOngoingManualWalk: Boolean,
    onStartWalk: () -> Unit,
    onFinishWalk: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasOngoingManualWalk) {
            Text(text = stringResource(R.string.ongoing_walk_banner), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onFinishWalk) { Text(stringResource(R.string.action_finish_walk)) }
        } else {
            OutlinedButton(onClick = onStartWalk) { Text(stringResource(R.string.action_start_walk)) }
        }
    }
}

@Composable
private fun TotalsSection(uiState: TodayUiState) {
    val today = uiState.today
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(label = stringResource(R.string.label_total_steps), value = "${today?.totalSteps ?: 0}")
        StatCard(label = stringResource(R.string.label_workout_steps), value = "${today?.workoutSteps ?: 0}")
        StatCard(label = stringResource(R.string.label_incidental_steps), value = "${today?.incidentalSteps ?: 0}")
    }
}

@Composable
private fun WeeklySection(weeklyTotal: Long, progress: GoalProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(label = stringResource(R.string.label_weekly_total), value = "$weeklyTotal")
        GoalProgressSection(
            title = stringResource(R.string.label_weekly_goal),
            progress = progress,
            contentDescriptionRes = R.string.cd_weekly_progress,
        )
    }
}
