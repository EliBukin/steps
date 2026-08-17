package com.example.stepsplit.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.domain.model.SyncFailure

/**
 * Explicit, honest source-availability surface (permission/API state) - never silently shows
 * zero as though it were real. This is a separate concern from [SyncFailureBanner] below: a
 * device can be fully available (permission granted, API present) while sync attempts still fail
 * for other reasons, and vice versa a temporarily unavailable source doesn't erase a past failure.
 */
@Composable
fun CollectionStatusBanner(
    availability: StepSourceAvailability,
    onGrantPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availability is StepSourceAvailability.Available) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = availability.statusText(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (availability is StepSourceAvailability.PermissionNotGranted) {
                TextButton(onClick = onGrantPermission) {
                    Text(stringResource(R.string.action_grant_permission))
                }
            }
        }
    }
}

/**
 * Shown only for [StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE]: the source is available and
 * syncing without error, but no positive sample has ever actually been read from it yet - the
 * exact gap that used to let the UI claim "Step collection is active" while the app had never
 * observed a single step (a successful empty read looks identical to a healthy one to both
 * [StepSourceAvailability] and [SyncFailure] alone). Deliberately styled as informational
 * (`secondaryContainer`), not an error - this is an expected, normal state before the user's first
 * recorded step of the day, not a problem to alarm about.
 */
@Composable
fun WaitingForFirstSampleBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = stringResource(R.string.status_waiting_for_first_sample),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Shown only for [StepCollectionHealth.STALE]: available, no recorded sync failure, at least one
 * sample has been observed at some point - but the newest sample is older than the app is willing
 * to call healthy. This is exactly the gap that let a real device show a fresh "Last synced" time
 * while the underlying step data was seven days old - see [StepCollectionHealth.STALE]'s own doc
 * comment. Styled as an error (like [SyncFailureBanner]), not informational like
 * [WaitingForFirstSampleBanner]: this is a genuine, actionable problem, not an expected transient
 * state.
 */
@Composable
fun StaleSourceBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = stringResource(R.string.status_stale_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * Surfaces the most recent sync/collection-health failure - distinct from [CollectionStatusBanner]
 * above, which only reflects source availability/permission state. The source can be fully
 * available and this can still show: availability being fine does not mean the last sync actually
 * succeeded. Cleared automatically the next time a sync genuinely succeeds (see
 * [com.example.stepsplit.data.repository.StepRepository]), so it never keeps showing a stale
 * failure once collection is working again.
 */
@Composable
fun SyncFailureBanner(syncFailure: SyncFailure, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = syncFailure.category.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.sync_failure_time_label, formatClockTime(syncFailure.atEpochSecond)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
