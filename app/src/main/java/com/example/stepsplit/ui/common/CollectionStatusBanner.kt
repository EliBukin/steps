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
