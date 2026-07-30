package com.example.stepsplit.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.ui.common.CollectionStatusBanner
import com.example.stepsplit.ui.common.GoalProgressSection
import com.example.stepsplit.ui.common.StatCard
import com.example.stepsplit.ui.common.formatClockTime
import com.example.stepsplit.ui.common.formatSyncTime

@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
    onStartWalk: () -> Unit,
    onFinishWalk: () -> Unit,
    onCancelStaleWalk: () -> Unit,
    onFinishStaleWalkNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        onRefresh()
        onPauseOrDispose { }
    }

    // Composition-scoped, not persisted: if the underlying condition still holds (the walk is
    // still zero-step and stale), the recovery card is expected to reappear on a later visit -
    // this only suppresses it for the remainder of the current visit to this screen.
    var staleWalkDismissedFor by remember { mutableStateOf<Long?>(null) }
    val staleWalkStart = uiState.staleZeroStepWalkStartEpochSecond
        ?.takeIf { it != staleWalkDismissedFor }

    val items = buildList {
        add(TodayItem.Availability)
        add(TodayItem.ManualWalk)
        if (staleWalkStart != null) add(TodayItem.StaleWalkRecovery)
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

                TodayItem.StaleWalkRecovery -> staleWalkStart?.let { start ->
                    StaleWalkRecoveryCard(
                        startEpochSecond = start,
                        onCancel = onCancelStaleWalk,
                        onFinishNow = onFinishStaleWalkNow,
                        onKeepOngoing = { staleWalkDismissedFor = start },
                    )
                }

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

private enum class TodayItem { Availability, ManualWalk, StaleWalkRecovery, Totals, DailyGoal, WeeklyGoal, LastSync }

@Composable
private fun StaleWalkRecoveryCard(
    startEpochSecond: Long,
    onCancel: () -> Unit,
    onFinishNow: () -> Unit,
    onKeepOngoing: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.stale_walk_recovery_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.stale_walk_recovery_message, formatClockTime(startEpochSecond)),
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.stale_walk_action_cancel)) }
                TextButton(onClick = onFinishNow) { Text(stringResource(R.string.stale_walk_action_finish_now)) }
                TextButton(onClick = onKeepOngoing) { Text(stringResource(R.string.stale_walk_action_keep_ongoing)) }
            }
        }
    }
}

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
