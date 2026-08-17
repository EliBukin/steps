package com.example.stepsplit.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.GoalProgress
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.ui.common.CollectionStatusBanner
import com.example.stepsplit.ui.common.GoalProgressSection
import com.example.stepsplit.ui.common.StaleSourceBanner
import com.example.stepsplit.ui.common.StatCard
import com.example.stepsplit.ui.common.SyncFailureBanner
import com.example.stepsplit.ui.common.WaitingForFirstSampleBanner
import com.example.stepsplit.ui.common.formatSyncTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often this screen re-imports from Health Connect while it stays visibly active - see the product requirement for a roughly-once-a-minute foreground refresh. */
private const val FOREGROUND_REFRESH_INTERVAL_MILLIS = 60_000L

@Composable
fun TodayScreen(
    uiState: TodayUiState,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
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
        add(TodayItem.Availability)
        if (uiState.syncFailure != null) add(TodayItem.SyncFailure)
        if (uiState.collectionHealth == StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE) add(TodayItem.WaitingForFirstSample)
        if (uiState.collectionHealth == StepCollectionHealth.STALE) add(TodayItem.StaleSource)
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

                TodayItem.SyncFailure -> uiState.syncFailure?.let { failure -> SyncFailureBanner(failure) }

                TodayItem.WaitingForFirstSample -> WaitingForFirstSampleBanner()

                TodayItem.StaleSource -> StaleSourceBanner()

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

private enum class TodayItem { Availability, SyncFailure, WaitingForFirstSample, StaleSource, Totals, DailyGoal, WeeklyGoal, LastSync }

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
