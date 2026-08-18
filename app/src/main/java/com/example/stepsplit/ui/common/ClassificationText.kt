package com.example.stepsplit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.stepsplit.R
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.model.SyncFailureCategory

@Composable
fun StepSourceAvailability.statusText(): String = when (this) {
    StepSourceAvailability.Available -> stringResource(R.string.status_available)
    StepSourceAvailability.PermissionNotGranted -> stringResource(R.string.status_permission_not_granted)
    StepSourceAvailability.PlayServicesUpdateRequired -> stringResource(R.string.status_play_services_update_required)
    StepSourceAvailability.ApiUnavailable -> stringResource(R.string.status_api_unavailable)
    is StepSourceAvailability.Error -> stringResource(R.string.status_error, message)
}

/** Structured, localized category text - never the raw exception message a failure was recorded with. */
@Composable
fun SyncFailureCategory.text(): String = when (this) {
    SyncFailureCategory.SUBSCRIPTION_FAILED -> stringResource(R.string.sync_failure_subscription_message)
    SyncFailureCategory.READ_FAILED -> stringResource(R.string.sync_failure_read_message)
    SyncFailureCategory.UNKNOWN -> stringResource(R.string.sync_failure_unknown_message)
}
