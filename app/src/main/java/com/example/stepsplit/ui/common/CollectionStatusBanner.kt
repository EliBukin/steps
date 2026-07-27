package com.example.stepsplit.ui.common

import androidx.compose.foundation.layout.Arrangement
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

/** Explicit, honest collection-status surface - never silently shows zero as though it were real. */
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
